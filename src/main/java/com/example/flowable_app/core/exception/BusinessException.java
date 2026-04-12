package com.example.flowable_app.core.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all application-level business errors.
 * Services throw this instead of returning error maps.
 * GlobalExceptionHandler catches it automatically.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public BusinessException(String message, String errorCode, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }
}

