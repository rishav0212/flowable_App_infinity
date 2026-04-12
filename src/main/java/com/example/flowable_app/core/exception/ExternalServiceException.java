// ExternalServiceException.java
package com.example.flowable_app.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when an external service call fails.
 * E.g., Form.io down, Google Drive unreachable.
 */
public class ExternalServiceException extends BusinessException {
    public ExternalServiceException(String serviceName, String details) {
        super(
                "External service '" + serviceName + "' failed: " + details,
                ErrorCode.INTERNAL_ERROR,
                HttpStatus.BAD_GATEWAY
        );
    }
}