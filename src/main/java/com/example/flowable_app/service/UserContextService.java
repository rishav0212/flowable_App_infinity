package com.example.flowable_app.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 🟢 USER CONTEXT SERVICE
 * Retrieves the currently authenticated user's identity and tenant details.
 * Supports both HTTP web requests and automated Flowable background jobs.
 */
@Service
public class UserContextService {

    // 🟢 Background-job fallback: set by TenantAwareCommandInterceptor before async execution
    private static final ThreadLocal<String> BACKGROUND_SCHEMA = new ThreadLocal<>();

    /** Called by TenantAwareCommandInterceptor to inject schema for async threads */
    public static void setBackgroundSchema(String schema) {
        BACKGROUND_SCHEMA.set(schema);
    }

    /** Always called in finally{} by TenantAwareCommandInterceptor */
    public static void clearBackgroundSchema() {
        BACKGROUND_SCHEMA.remove();
    }

    /**
     * @return The schema name associated with the tenant.
     */
    public String getCurrentTenantSchema() {
        // 1. Background job (timer, scheduler) — schema injected by interceptor
        String backgroundSchema = BACKGROUND_SCHEMA.get();
        if (backgroundSchema != null && !backgroundSchema.trim().isEmpty()) {
            return backgroundSchema;
        }

        // 2. Normal HTTP request with JWT
        Map<String, Object> claims = getPrincipalClaims();
        return (String) claims.get("schemaName");
    }

    public String getCurrentTenantId() {
        Map<String, Object> claims = getPrincipalClaims();
        String tenantId = (String) claims.get("tenantId");

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new SecurityException("Access Denied: No Tenant ID found in current session.");
        }
        return tenantId;
    }

    public String getCurrentUserId() {
        Map<String, Object> claims = getPrincipalClaims();
        Object id = claims.get("id");
        return id != null ? id.toString() : "anonymous";
    }

    public String getCurrentUserEmail() {
        Map<String, Object> claims = getPrincipalClaims();
        return (String) claims.get("email");
    }

    public String getCurrentTenantSlug() {
        return (String) getClaim("tenantSlug");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPrincipalClaims() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new SecurityException("No authentication data found.");
        }

        if (!(auth.getPrincipal() instanceof Map)) {
            throw new SecurityException("Invalid Principal Type. Expected Map from JWT.");
        }

        return (Map<String, Object>) auth.getPrincipal();
    }

    @SuppressWarnings("unchecked")
    private Object getClaim(String key) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (!(auth.getPrincipal() instanceof Map)) return null;
        return ((Map<String, Object>) auth.getPrincipal()).get(key);
    }
}