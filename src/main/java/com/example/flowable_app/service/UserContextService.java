package com.example.flowable_app.service;

import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
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
        try {
            Map<String, Object> claims = getPrincipalClaims();
            String tenantId = (String) claims.get("tenantId");

            if (tenantId == null || tenantId.trim().isEmpty()) {
                throw new SecurityException("Access Denied: No Tenant ID found in current session.");
            }
            return tenantId;
        } catch (SecurityException e) {
            // Fallback: If executed by a Flowable background thread (Async Executor),
            // the Spring Security context will be empty. We extract the tenant from the engine.
            String flowableTenantId = getTenantFromFlowableContext();
            if (flowableTenantId != null && !flowableTenantId.trim().isEmpty()) {
                return flowableTenantId;
            }
            throw e;
        }
    }

    /**
     * @return The User ID (e.g., "Rishab_J") from the JWT.
     */
    public String getCurrentUserId() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            Object id = claims.get("id");
            return id != null ? id.toString() : "anonymous";
        } catch (SecurityException e) {
            // Background threads do not have an active user session.
            return "system";
        }
    }

    /**
     * @return The User's Email from the JWT.
     */
    public String getCurrentUserEmail() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            return (String) claims.get("email");
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * @return The schema name associated with the tenant, if available in the JWT.
     */
    public String getCurrentTenantSchema() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            return (String) claims.get("schemaName");
        } catch (SecurityException e) {
            // Complex Functionality: During background tasks (like async service tasks),
            // we use the flowable tenant ID as the schema name to ensure correct DB routing.
            String flowableTenantId = getTenantFromFlowableContext();
            if (flowableTenantId != null && !flowableTenantId.trim().isEmpty()) {
                return flowableTenantId;
            }
            throw e;
        }
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
            throw new SecurityException("Invalid Principal Type. Expected Map from JWT.");
        }

        return (Map<String, Object>) auth.getPrincipal();
    }

    /**
     * Extracts the tenant ID directly from Flowable's engine context.
     * This uses a multi-layered approach to check for an active Task context
     * or a BPMN Execution context.
     */
    private String getTenantFromFlowableContext() {
        try {
            CommandContext commandContext = Context.getCommandContext();
            if (commandContext != null) {
                // Layer 1: Check if we are inside a User Task context
                // This utilizes the standard Attribute map in CommandContext
                Object task = commandContext.getAttribute("task");
                if (task instanceof TaskEntity) {
                    return ((TaskEntity) task).getTenantId();
                }

                // Layer 2: Check for a BPMN Execution context (Service Tasks, Listeners)
                // CommandContextUtil provides access to the engine's internal entity managers
                var executionEntityManager = CommandContextUtil.getExecutionEntityManager(commandContext);
                if (executionEntityManager != null) {
                    // We attempt to find a tenant ID from the current scope
                    return executionEntityManager.findChildExecutionsByProcessInstanceId(null)
                            .stream()
                            .map(e -> e.getTenantId())
                            .filter(t -> t != null && !t.isEmpty())
                            .findFirst()
                            .orElse(null);
                }
            }
        } catch (Exception ignored) {
            // Context might not be initialized if not called from a Flowable thread
        }
        return null;
    }
}