package com.example.flowable_app.config;

import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
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
public class GlobalExceptionHandler {

    @ExceptionHandler(FlowableObjectNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(FlowableObjectNotFoundException e) {
        log.warn("⚠️ Resource Not Found: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Resource not found: " + e.getMessage());
    }

    @ExceptionHandler(FlowableTaskAlreadyClaimedException.class)
    public ResponseEntity<Map<String, Object>> handleTaskClaimed(FlowableTaskAlreadyClaimedException e) {
        log.warn("⚠️ Task Conflict: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "Task is already claimed by another user.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        log.warn("⛔ Access Denied: {}", e.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN,
                "Access Denied: You do not have permission to perform this action.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        log.warn("⚠️ Upload Size Exceeded: {}", exc.getMessage());
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "File too large! Please upload a smaller file.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception e) {
        // Log the full stack trace for debugging in production
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