/*
* Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License").
* You may not use this file except in compliance with the License.
* A copy of the License is located at
*
*  http://aws.amazon.com/apache2.0
*
* or in the "license" file accompanying this file. This file is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
* express or implied. See the License for the specific language governing
* permissions and limitations under the License.
*/
package software.amazon.cloudformation.proxy;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.AmazonWebServiceResult;
import com.amazonaws.ResponseMetadata;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.cloudformation.proxy.delay.Constant;

/**
 * This implements the proxying mechanism to inject appropriate scoped
 * credentials into a service call when making Amazon Webservice calls.
 *
 * @see CallChain
 * @see ProxyClient
 */
public class AmazonWebServicesClientProxy implements CallChain {

    public static final int HTTP_STATUS_NETWORK_AUTHN_REQUIRED = 511;
    public static final int HTTP_STATUS_GONE = 410;

    private final AWSCredentialsProvider v1CredentialsProvider;
    private final AwsCredentialsProvider v2CredentialsProvider;
    private final Supplier<Long> remainingTimeInMillis;
    private final boolean inHandshakeMode;
    private LoggerProxy loggerProxy;

    public AmazonWebServicesClientProxy(final LoggerProxy loggerProxy,
                                        final Credentials credentials,
                                        final Supplier<Long> remainingTimeToExecute) {
        this(false, loggerProxy, credentials, remainingTimeToExecute);
    }

    public AmazonWebServicesClientProxy(final boolean inHandshakeMode,
                                        final LoggerProxy loggerProxy,
                                        final Credentials credentials,
                                        final Supplier<Long> remainingTimeToExecute) {
        this.inHandshakeMode = inHandshakeMode;
        this.loggerProxy = loggerProxy;
        this.remainingTimeInMillis = remainingTimeToExecute;

        BasicSessionCredentials basicSessionCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(),
                                                                                      credentials.getSecretAccessKey(),
                                                                                      credentials.getSessionToken());
        this.v1CredentialsProvider = new AWSStaticCredentialsProvider(basicSessionCredentials);

        AwsSessionCredentials awsSessionCredentials = AwsSessionCredentials.create(credentials.getAccessKeyId(),
            credentials.getSecretAccessKey(), credentials.getSessionToken());
        this.v2CredentialsProvider = StaticCredentialsProvider.create(awsSessionCredentials);
    }

    public <ClientT> ProxyClient<ClientT> newProxy(@Nonnull Supplier<ClientT> client) {
        return new ProxyClient<ClientT>() {
            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                ResponseT
                injectCredentialsAndInvokeV2(RequestT request, Function<RequestT, ResponseT> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeV2(request, requestFunction);
            }

            @Override
            public <RequestT extends AwsRequest, ResponseT extends AwsResponse>
                CompletableFuture<ResponseT>
                injectCredentialsAndInvokeV2Aync(RequestT request,
                                                 Function<RequestT, CompletableFuture<ResponseT>> requestFunction) {
                return AmazonWebServicesClientProxy.this.injectCredentialsAndInvokeV2Async(request, requestFunction);
            }

            @Override
            public ClientT client() {
                return client.get();
            }
        };
    }

    @Override
    public <ClientT, ModelT, CallbackT extends StdCallbackContext>
        RequestMaker<ClientT, ModelT, CallbackT>
        initiate(String callGraph, ProxyClient<ClientT> client, ModelT model, CallbackT cxt) {
        Preconditions.checkNotNull(callGraph, "callGraph can not be null");
        Preconditions.checkNotNull(client, "ProxyClient can not be null");
        Preconditions.checkNotNull(model, "Resource Model can not be null");
        Preconditions.checkNotNull(cxt, "cxt can not be null");
        return new CallContext<>(callGraph, client, model, cxt);
    }

    class CallContext<ClientT, ModelT, CallbackT extends StdCallbackContext>
        implements CallChain.RequestMaker<ClientT, ModelT, CallbackT> {

        private final String callGraph;
        private final ProxyClient<ClientT> client;
        private final ModelT model;
        private final CallbackT context;
        //
        // Default delay context and retries for all web service calls when
        // handling errors, throttles and more. The handler can influence this
        // using retry method.
        //
        private Delay delay = Constant.of().delay(Duration.ofSeconds(5)).timeout(Duration.ofMinutes(20)).build();

        CallContext(String callGraph,
                    ProxyClient<ClientT> client,
                    ModelT model,
                    CallbackT context) {
            this.callGraph = Preconditions.checkNotNull(callGraph);
            this.client = Preconditions.checkNotNull(client);
            this.model = Preconditions.checkNotNull(model);
            this.context = context;
        }

        @Override
        public <RequestT> Caller<RequestT, ClientT, ModelT, CallbackT> request(Function<ModelT, RequestT> maker) {
            return new Caller<RequestT, ClientT, ModelT, CallbackT>() {

                @Override
                public Caller<RequestT, ClientT, ModelT, CallbackT> retry(Delay delay) {
                    CallContext.this.delay = delay;
                    return this;
                }

                @Override
                public <ResponseT>
                    Stabilizer<RequestT, ResponseT, ClientT, ModelT, CallbackT>
                    call(BiFunction<RequestT, ProxyClient<ClientT>, ResponseT> caller) {
                    return new Stabilizer<RequestT, ResponseT, ClientT, ModelT, CallbackT>() {

                        private Callback<RequestT, ResponseT, ClientT, ModelT, CallbackT, Boolean> waitFor;
                        // default exception handler, reports failure.
                        private Callback<? super RequestT, Exception, ClientT, ModelT, CallbackT,
                            ProgressEvent<ModelT, CallbackT>> exceptHandler;

                        @Override
                        public Exceptional<RequestT, ResponseT, ClientT, ModelT, CallbackT>
                            stabilize(Callback<RequestT, ResponseT, ClientT, ModelT, CallbackT, Boolean> callback) {
                            this.waitFor = callback;
                            return this;
                        }

                        @Override
                        public Completed<RequestT, ResponseT, ClientT, ModelT, CallbackT>
                            exceptFilter(Callback<? super RequestT, Exception, ClientT, ModelT, CallbackT, Boolean> handler) {
                            return exceptHandler((request, exception, client1, model1, context1) -> {
                                if (handler.invoke(request, exception, client1, model1, context1)) {
                                    return ProgressEvent.progress(model1, context1);
                                }
                                return defaultHandler(request, exception, client1, model1, context1);
                            });
                        }

                        @Override
                        public Completed<RequestT, ResponseT, ClientT, ModelT, CallbackT> exceptHandler(Callback<? super RequestT,
                            Exception, ClientT, ModelT, CallbackT, ProgressEvent<ModelT, CallbackT>> handler) {
                            this.exceptHandler = handler;
                            return this;
                        }

                        @Override
                        public ProgressEvent<ModelT, CallbackT> done(Callback<RequestT, ResponseT, ClientT, ModelT, CallbackT,
                            ProgressEvent<ModelT, CallbackT>> callback) {
                            //
                            // StdCallbackContext memoization wrappers for request, response, and
                            // stabilization
                            // lambdas. This ensures that we call demux as necessary.
                            //
                            Function<ModelT, RequestT> reqMaker = context.request(callGraph, maker);
                            BiFunction<RequestT, ProxyClient<ClientT>, ResponseT> resMaker = context.response(callGraph, caller);
                            if (waitFor != null) {
                                waitFor = context.stabilize(callGraph, waitFor);
                            }
                            Callback<? super RequestT, Exception, ClientT, ModelT, CallbackT,
                                ProgressEvent<ModelT, CallbackT>> exceptHandler = this.exceptHandler != null
                                    ? this.exceptHandler
                                    : AmazonWebServicesClientProxy.this::defaultHandler;
                            int attempt = context.attempts(callGraph);
                            RequestT req = null;
                            ResponseT res = null;
                            ProgressEvent<ModelT, CallbackT> event = null;
                            try {
                                for (;;) {
                                    Instant now = Instant.now();
                                    try {
                                        req = req == null ? reqMaker.apply(model) : req;
                                        res = res == null ? resMaker.apply(req, client) : res;
                                        if (waitFor != null) {
                                            if (waitFor.invoke(req, res, client, model, context)) {
                                                event = callback.invoke(req, res, client, model, context);
                                            }
                                        } else {
                                            event = callback.invoke(req, res, client, model, context);
                                        }
                                    } catch (Exception e) {
                                        event = exceptHandler.invoke(req, e, client, model, context);
                                        if (event.canContinueProgress()) {
                                            event = null; // wait
                                        }
                                    }

                                    if (event != null && (event.isFailed() || event.isSuccess())) {
                                        return event;
                                    }

                                    if (inHandshakeMode) {
                                        return ProgressEvent.defaultInProgressHandler(context, 60, model);
                                    }

                                    if (event != null) {
                                        return event;
                                    }

                                    //
                                    // The logic to wait is if next delay + 2 * time to run the operation sequence +
                                    // 100ms
                                    // is less than time remaining time to run inside Lambda then we locally wait
                                    // else we bail out. Assuming 3 DAYS for a DB to restore, that would be total of
                                    // 3 x 24 x 60 x 60 x 1000 ms, fits in 32 bit int.
                                    //
                                    Instant opTime = Instant.now();
                                    long elapsed = ChronoUnit.MILLIS.between(now, opTime);
                                    Duration next = delay.nextDelay(attempt++);
                                    context.attempts(callGraph, attempt);
                                    if (next == Duration.ZERO) {
                                        return ProgressEvent.failed(model, context, HandlerErrorCode.NotStabilized,
                                            "Exceeded attempts to wait");
                                    }
                                    long remainingTime = getRemainingTimeInMillis();
                                    long localWait = next.toMillis() + 2 * elapsed + 100;
                                    if (remainingTime > localWait) {
                                        Uninterruptibles.sleepUninterruptibly(next.getSeconds(), TimeUnit.SECONDS);
                                        continue;
                                    }
                                    return ProgressEvent.defaultInProgressHandler(context, Math.max((int) next.getSeconds(), 60),
                                        model);
                                }
                            } finally {
                                //
                                // only set request if response was successful. Otherwise we will remember the
                                // the original failed request in the callback. So when we fix and resume from
                                // the error with callback, we will replay the wrong one
                                //
                                if (res == null) {
                                    context.evictRequestRecord(callGraph);
                                }
                            }
                        }

                        @Override
                        public ProgressEvent<ModelT, CallbackT> done(Function<ResponseT, ProgressEvent<ModelT, CallbackT>> func) {
                            return done((request1, response1, client1, model1, context1) -> func.apply(response1));
                        }
                    };
                }
            };
        }

    }

    public final long getRemainingTimeInMillis() {
        return remainingTimeInMillis.get();
    }

    public <RequestT extends AmazonWebServiceRequest, ResultT extends AmazonWebServiceResult<ResponseMetadata>>
        ResultT
        injectCredentialsAndInvoke(final RequestT request, final Function<RequestT, ResultT> requestFunction) {

        request.setRequestCredentialsProvider(v1CredentialsProvider);

        try {
            return requestFunction.apply(request);
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        } finally {
            request.setRequestCredentialsProvider(null);
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse>
        ResultT
        injectCredentialsAndInvokeV2(final RequestT request, final Function<RequestT, ResultT> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            return requestFunction.apply(wrappedRequest);
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse>
        CompletableFuture<ResultT>
        injectCredentialsAndInvokeV2Async(final RequestT request,
                                          final Function<RequestT, CompletableFuture<ResultT>> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            return requestFunction.apply(wrappedRequest);
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT extends AwsRequest, ResultT extends AwsResponse, IterableT extends SdkIterable<ResultT>>
        IterableT
        injectCredentialsAndInvokeIterableV2(final RequestT request, final Function<RequestT, IterableT> requestFunction) {

        AwsRequestOverrideConfiguration overrideConfiguration = AwsRequestOverrideConfiguration.builder()
            .credentialsProvider(v2CredentialsProvider).build();

        @SuppressWarnings("unchecked")
        RequestT wrappedRequest = (RequestT) request.toBuilder().overrideConfiguration(overrideConfiguration).build();

        try {
            return requestFunction.apply(wrappedRequest);
        } catch (final Throwable e) {
            loggerProxy.log(String.format("Failed to execute remote function: {%s}", e.getMessage()));
            throw e;
        }
    }

    public <RequestT, ClientT, ModelT, CallbackT extends StdCallbackContext>
        ProgressEvent<ModelT, CallbackT>
        defaultHandler(RequestT request, Exception e, ClientT client, ModelT model, CallbackT context) {
        //
        // Client side exception, mapping this to InvalidRequest at the moment
        //
        if (e instanceof NonRetryableException) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.InvalidRequest, e.getMessage());
        }

        if (e instanceof AwsServiceException) {
            AwsServiceException sdkException = (AwsServiceException) e;
            AwsErrorDetails details = sdkException.awsErrorDetails();
            String errMsg = "Code(" + details.errorCode() + "),  " + details.errorMessage();
            switch (details.sdkHttpResponse().statusCode()) {
                case HttpStatusCode.BAD_REQUEST:
                    //
                    // BadRequest, wrong values in the request
                    //
                    return ProgressEvent.failed(model, context, HandlerErrorCode.InvalidRequest, errMsg);

                case HttpStatusCode.UNAUTHORIZED:
                case HttpStatusCode.FORBIDDEN:
                case HTTP_STATUS_NETWORK_AUTHN_REQUIRED: // 511 Network Authentication Required, just in case
                    //
                    // Access Denied, AuthN/Z problems
                    //
                    return ProgressEvent.failed(model, context, HandlerErrorCode.AccessDenied, errMsg);

                case HttpStatusCode.NOT_FOUND:
                case HTTP_STATUS_GONE: // 410 Gone
                    //
                    // Resource that we are trying READ/UPDATE/DELETE is not found
                    //
                    return ProgressEvent.failed(model, context, HandlerErrorCode.NotFound, errMsg);

                case HttpStatusCode.SERVICE_UNAVAILABLE:
                    //
                    // Often retries help here as well. IMPORTANT to remember here that
                    // there are retries with the SDK Client itself for these. Verify
                    // what we add extra over the default ones
                    //
                case HttpStatusCode.GATEWAY_TIMEOUT:
                case HttpStatusCode.THROTTLING: // Throttle, TOO many requests
                    AmazonWebServicesClientProxy.this.loggerProxy.log("Retrying for error " + details.errorMessage());
                    return ProgressEvent.progress(model, context);

                default:
                    return ProgressEvent.failed(model, context, HandlerErrorCode.GeneralServiceException, errMsg);
            }
        }
        return ProgressEvent.failed(model, context, HandlerErrorCode.InternalFailure, e.getMessage());

    }
}
