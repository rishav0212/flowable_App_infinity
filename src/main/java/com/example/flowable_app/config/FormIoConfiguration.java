package com.example.flowable_app.config;

import com.example.flowable_app.service.FormIoAuthService;
import feign.RequestInterceptor;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

public class FormIoConfiguration {

    private final FormIoAuthService authService;

    public FormIoConfiguration(FormIoAuthService authService) {
        this.authService = authService;
    }

    // 1. Interceptor: Always attach the current token
    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            String token = authService.getAccessToken();
            requestTemplate.header("x-jwt-token", token);
        };
    }

    // 2. Retryer: Tell Feign it is ALLOWED to retry
    // (100ms wait, max 1 second wait, max 3 attempts)
    @Bean
    public Retryer retryer() {
        return new Retryer.Default(100L, 1000L, 3);
    }

    // 3. Error Decoder: The Magic Logic
    @Bean
    public ErrorDecoder errorDecoder() {
        return new ErrorDecoder() {
            private final ErrorDecoder defaultDecoder = new Default();

            @Override
            public Exception decode(String methodKey, Response response) {
                // Check for 440 (Session Expired) or 401 (Unauthorized)
                if (response.status() == 440 || response.status() == 401) {
                    System.out.println("🔄 Feign: Token Expired (" + response.status() + "). Invalidating and Retrying...");

                    // A. Clear the bad token from memory
                    authService.invalidateToken();

                    // B. Throw RetryableException
                    // This forces Feign to loop back, run the RequestInterceptor again
                    // (which fetches a NEW token), and resend the request.
                    return new RetryableException(
                            response.status(),
                            "Token expired",
                            response.request().httpMethod(),
                            (Long) null,
                            response.request()
                    );
                }
                // For all other errors, behave normally
                return defaultDecoder.decode(methodKey, response);
            }
        };
    }
}