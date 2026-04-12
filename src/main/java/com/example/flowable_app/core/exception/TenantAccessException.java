package com.example.flowable_app.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a user tries to access a resource belonging to a different tenant.
 * Results in HTTP 403.
 */
public class TenantAccessException extends BusinessException {

    public TenantAccessException() {
        super(
                "Access denied: you do not have permission to access this resource",
                "TENANT_ACCESS_DENIED",
                HttpStatus.FORBIDDEN
        );
    }

    public TenantAccessException(String details) {
        super(
                "Access denied: " + details,
                "TENANT_ACCESS_DENIED",
                HttpStatus.FORBIDDEN
        );
    }
}
