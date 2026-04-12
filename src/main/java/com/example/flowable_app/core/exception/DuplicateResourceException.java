// DuplicateResourceException.java
package com.example.flowable_app.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when trying to create something that already exists.
 * E.g., user with same email, role with same ID.
 */
public class DuplicateResourceException extends BusinessException {
    public DuplicateResourceException(String resourceName, String identifier) {
        super(
                resourceName + " already exists: " + identifier,
                ErrorCode.USER_ALREADY_EXISTS,
                HttpStatus.CONFLICT
        );
    }
}