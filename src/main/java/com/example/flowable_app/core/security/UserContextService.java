package com.example.flowable_app.core.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 🟢 USER CONTEXT SERVICE
 * A centralized service to retrieve the currently authenticated user's identity
 * and tenant details from the Security Context.
 * Supports both HTTP web requests and automated Flowable background jobs.
 */
@Service
public class UserContextService {

    /*
     * 🟢 Background-job fallback: set by TenantAwareCommandInterceptor before async execution.
     * Why we implemented this: Flowable timer events and async jobs run on background threads without an HTTP request.
     * This ThreadLocal allows our interceptor to temporarily inject the schema so data services won't crash.
     */
    private static final ThreadLocal<String> BACKGROUND_SCHEMA = new ThreadLocal<>();

    /** Called by TenantAwareCommandInterceptor to inject schema for async threads */
    public static void setBackgroundSchema(String schema) {
        BACKGROUND_SCHEMA.set(schema);
    }

    /** Always called in finally{} by TenantAwareCommandInterceptor to prevent memory leaks */
    public static void clearBackgroundSchema() {
        BACKGROUND_SCHEMA.remove();
    }

    /**
     * @return The Tenant ID (e.g., "acme-corp") from the JWT.
     * @throws SecurityException if the user is not authenticated or lacks a tenant.
     */
    public String getCurrentTenantId() {
        Map<String, Object> claims = getPrincipalClaims();
        String tenantId = (String) claims.get("tenantId");

        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new SecurityException("Access Denied: No Tenant ID found in current session.");
        }
        return tenantId;
    }

    /**
     * @return The User ID (e.g., "Rishab_J") from the JWT.
     */
    public String getCurrentUserId() {
        Map<String, Object> claims = getPrincipalClaims();
        Object id = claims.get("id");
        return id != null ? id.toString() : "anonymous";
    }

    /**
     * @return The User's Email from the JWT.
     */
    public String getCurrentUserEmail() {
        Map<String, Object> claims = getPrincipalClaims();
        return (String) claims.get("email");
    }

    /**
     * @return The schema name associated with the tenant.
     */
    public String getCurrentTenantSchema() {
        /*
         * Why we changed the code to this: We must check the BACKGROUND_SCHEMA first.
         * If a Flowable automated timer is running, there is no JWT to decode, so calling
         * getPrincipalClaims() would immediately throw an exception.
         */

        // 1. Background job (timer, scheduler) — schema injected by interceptor
        String backgroundSchema = BACKGROUND_SCHEMA.get();
        if (backgroundSchema != null && !backgroundSchema.trim().isEmpty()) {
            return backgroundSchema;
        }

        // 2. Normal HTTP request with JWT
        Map<String, Object> claims = getPrincipalClaims();
        return (String) claims.get("schemaName"); // Assuming tenantSlug is the schema name
    }

    /** NEW: Returns the URL-safe slug (e.g. "saar-biotech") used as the form path prefix. */
    public String getCurrentTenantSlug() {
        return (String) getClaim("tenantSlug");
    }

    /**
     * Helper to extract the claims Map safely.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getPrincipalClaims() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new SecurityException("No authentication data found.");
        }

        if (!(auth.getPrincipal() instanceof Map)) {
            // This happens if the Principal is a String "anonymousUser" or similar
            throw new SecurityException("Invalid Principal Type. Expected Map from JWT.");
        }

        return (Map<String, Object>) auth.getPrincipal();
    }

    /*
     * Why we implemented this: Allows safe extraction of non-mandatory, specialized
     * claims (like tenantSlug) without failing if they don't exist in the current token.
     */
    @SuppressWarnings("unchecked")
    private Object getClaim(String key) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (!(auth.getPrincipal() instanceof Map)) return null;
        return ((Map<String, Object>) auth.getPrincipal()).get(key);
    }
}