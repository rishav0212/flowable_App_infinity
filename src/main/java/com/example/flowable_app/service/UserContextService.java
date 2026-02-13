package com.example.flowable_app.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 🟢 USER CONTEXT SERVICE
 * A centralized service to retrieve the currently authenticated user's identity
 * and tenant details from the Security Context.
 */
@Service
public class UserContextService {

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
}