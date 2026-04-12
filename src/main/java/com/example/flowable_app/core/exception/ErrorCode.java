package com.example.flowable_app.core.exception;

/**
 * All error codes used across the application in one place.
 * Frontend can map these to user-friendly messages.
 */
public final class ErrorCode {

    private ErrorCode() {} // Prevent instantiation

    // ─── General ───────────────────────────────────────────────────────────
    public static final String NOT_FOUND          = "NOT_FOUND";
    public static final String VALIDATION_ERROR   = "VALIDATION_ERROR";
    public static final String INTERNAL_ERROR     = "INTERNAL_ERROR";
    public static final String INVALID_ARGUMENT   = "INVALID_ARGUMENT";

    // ─── Security & Access ─────────────────────────────────────────────────
    public static final String ACCESS_DENIED         = "ACCESS_DENIED";
    public static final String TENANT_ACCESS_DENIED  = "TENANT_ACCESS_DENIED";
    public static final String UNAUTHORIZED          = "UNAUTHORIZED";

    // ─── Task ──────────────────────────────────────────────────────────────
    public static final String TASK_NOT_FOUND        = "TASK_NOT_FOUND";
    public static final String TASK_ALREADY_CLAIMED  = "TASK_ALREADY_CLAIMED";
    public static final String TASK_NOT_ASSIGNED     = "TASK_NOT_ASSIGNED";
    public static final String TASK_ALREADY_COMPLETED = "TASK_ALREADY_COMPLETED";

    // ─── Process ───────────────────────────────────────────────────────────
    public static final String PROCESS_NOT_FOUND     = "PROCESS_NOT_FOUND";
    public static final String DEPLOYMENT_REJECTED   = "DEPLOYMENT_REJECTED";
    public static final String CONCURRENCY_CONFLICT  = "CONCURRENCY_CONFLICT";
    public static final String FLOWABLE_ENGINE_ERROR = "FLOWABLE_ENGINE_ERROR";
    public static final String FLOWABLE_NOT_FOUND    = "FLOWABLE_NOT_FOUND";

    // ─── Form ──────────────────────────────────────────────────────────────
    public static final String FORM_NOT_FOUND        = "FORM_NOT_FOUND";
    public static final String FORM_SUBMISSION_FAILED = "FORM_SUBMISSION_FAILED";

    // ─── User / IAM ────────────────────────────────────────────────────────
    public static final String USER_NOT_FOUND        = "USER_NOT_FOUND";
    public static final String USER_ALREADY_EXISTS   = "USER_ALREADY_EXISTS";
    public static final String ROLE_NOT_FOUND        = "ROLE_NOT_FOUND";
    public static final String ROLE_ALREADY_EXISTS   = "ROLE_ALREADY_EXISTS";
    public static final String CIRCULAR_DEPENDENCY   = "CIRCULAR_DEPENDENCY";

    // ─── File / Storage ────────────────────────────────────────────────────
    public static final String FILE_TOO_LARGE        = "FILE_TOO_LARGE";
    public static final String FILE_UPLOAD_FAILED    = "FILE_UPLOAD_FAILED";
    public static final String FOLDER_NOT_FOUND      = "FOLDER_NOT_FOUND";
}