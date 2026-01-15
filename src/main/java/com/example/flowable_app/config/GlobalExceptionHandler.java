package com.example.flowable_app.config;

import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.springframework.core.Ordered; // 🟢 Import Ordered
import org.springframework.core.annotation.Order; // 🟢 Import Order
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE) // 🟢 FORCE THIS HANDLER TO RUN FIRST
public class GlobalExceptionHandler {

    // 1. Resource Not Found (404)
    @ExceptionHandler(FlowableObjectNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(FlowableObjectNotFoundException e) {
        log.warn("⚠️ Resource Not Found: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Resource not found: " + e.getMessage());
    }

    // 2. Task Already Claimed (409)
    @ExceptionHandler(FlowableTaskAlreadyClaimedException.class)
    public ResponseEntity<Map<String, Object>> handleTaskClaimed(FlowableTaskAlreadyClaimedException e) {
        log.warn("⚠️ Task Conflict: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Task is already claimed by another user.");
    }

    // 3. Optimistic Locking / Concurrency Issues (409)
    @ExceptionHandler(FlowableOptimisticLockingException.class)
    public ResponseEntity<Map<String, Object>> handleConcurrency(FlowableOptimisticLockingException ex) {
        log.warn("⚠️ Optimistic Locking Failure: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "The data was modified by someone else. Please refresh.");
    }

    // 4. Invalid Arguments / Bad API Usage (400)
    @ExceptionHandler(FlowableIllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(FlowableIllegalArgumentException ex) {
        log.warn("⚠️ Invalid Argument: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // 5. General Flowable Exception (Includes Deployment Validation)
    @ExceptionHandler(FlowableException.class)
    public ResponseEntity<Map<String, Object>> handleGenericFlowable(FlowableException ex) {
        // Check for our custom validator error
        if (ex.getMessage() != null && ex.getMessage().contains("DEPLOYMENT REJECTED")) {
            log.warn("⚠️ Deployment Rejected: {}", ex.getMessage());
            // Return 400 so the frontend treats it as a validation error, not a crash
            return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        // For all other Flowable runtime errors, log full stack trace and return 500
        log.error("❌ Flowable System Error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Flowable Engine Error: " + ex.getMessage());
    }

    // 6. Security / Access Denied (403)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        log.warn("⛔ Access Denied: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN,
                "Access Denied: You do not have permission to perform this action.");
    }

    // 7. File Upload Size (413)
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        log.warn("⚠️ Upload Size Exceeded: {}", exc.getMessage());
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "File too large! Please upload a smaller file.");
    }

    // 8. Catch-All for any other Java Exceptions (500)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception e) {
        log.error("❌ Unexpected Error: {}", e.getMessage(), e);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred: " + e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", status.getReasonPhrase());
        response.put("message", message);
        response.put("status", status.value());
        response.put("timestamp", System.currentTimeMillis());
        return new ResponseEntity<>(response, status);
    }
}