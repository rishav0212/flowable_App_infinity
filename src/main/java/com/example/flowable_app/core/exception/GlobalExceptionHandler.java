package com.example.flowable_app.core.exception;

import com.example.flowable_app.core.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    // ─── 1. Our Custom Business Exceptions (most specific, caught first) ───

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    // ─── 2. Validation Errors from @Valid DTOs ──────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        log.warn("Validation failed: {}", fieldErrors);

        // We return the field errors as the data payload
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .message("Validation failed. Please check the fields.")
                        .data(fieldErrors)
                        .errorCode("VALIDATION_ERROR")
                        .build());
    }

    // ─── 3. Flowable Engine Exceptions ─────────────────────────────────────

    @ExceptionHandler(FlowableObjectNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowableNotFound(FlowableObjectNotFoundException ex) {
        log.warn("Flowable resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("The requested process or task was not found.", "FLOWABLE_NOT_FOUND"));
    }

    @ExceptionHandler(FlowableTaskAlreadyClaimedException.class)
    public ResponseEntity<ApiResponse<Void>> handleTaskClaimed(FlowableTaskAlreadyClaimedException ex) {
        log.warn("Task already claimed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("This task is already claimed by another user.", "TASK_ALREADY_CLAIMED"));
    }

    @ExceptionHandler(FlowableOptimisticLockingException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(FlowableOptimisticLockingException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Data was modified by another user. Please refresh.", "CONCURRENCY_CONFLICT"));
    }

    @ExceptionHandler(FlowableIllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowableIllegalArg(FlowableIllegalArgumentException ex) {
        log.warn("Flowable illegal argument: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), "INVALID_ARGUMENT"));
    }

    @ExceptionHandler(FlowableException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowableException(FlowableException ex) {
        // Check if it's a deployment validation rejection (our custom validator)
        if (ex.getMessage() != null && ex.getMessage().contains("DEPLOYMENT REJECTED")) {
            log.warn("Deployment rejected by validator: {}", ex.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(ex.getMessage(), "DEPLOYMENT_REJECTED"));
        }

        log.error("Flowable engine error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Workflow engine encountered an error: " + ex.getMessage(),
                        "FLOWABLE_ENGINE_ERROR"));
    }

    // ─── 4. Security Exceptions ─────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action.", "ACCESS_DENIED"));
    }

    // ─── 5. Infrastructure Exceptions ───────────────────────────────────────

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("File is too large. Please upload a smaller file.", "FILE_TOO_LARGE"));
    }

    // ─── 6. Catch-All (last resort, always keep this) ───────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // Log full stack trace — this is unexpected so we need all details
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please try again later.", "INTERNAL_ERROR"));
    }
}