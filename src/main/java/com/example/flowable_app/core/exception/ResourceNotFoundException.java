package com.example.flowable_app.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested resource (task, process, user, form) does not exist.
 * Results in HTTP 404.
 * <p>
 * Usage:
 * throw new ResourceNotFoundException("Task", taskId);
 * throw new ResourceNotFoundException("User", userId);
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceName, String identifier) {
        super(
                resourceName + " not found: " + identifier,
                "NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }
}
