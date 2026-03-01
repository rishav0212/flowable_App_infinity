package com.example.flowable_app.service;

import org.flowable.common.engine.impl.context.Context;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 🟢 USER CONTEXT SERVICE
 * Provides identity and tenant details. Supports both REST requests (via SecurityContext)
 * and Background Async Tasks (via Flowable CommandContext).
 */
@Service
public class UserContextService {

    public String getCurrentTenantId() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            String tenantId = (String) claims.get("tenantId");
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                return tenantId;
            }
        } catch (Exception e) {
            // Fallback for Async Tasks: Extract tenant from Flowable's engine context.
            String flowableTenantId = getTenantFromFlowableContext();
            if (flowableTenantId != null) return flowableTenantId;
        }
        throw new SecurityException("No Tenant context available in Security or Engine.");
    }

    public String getCurrentUserId() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            Object id = claims.get("id");
            return id != null ? id.toString() : "anonymous";
        } catch (Exception e) {
            return "system"; // Background tasks are performed by the system
        }
    }

    /**
     * 🟢 FIXED: RESTORED MISSING METHOD
     * @return The User's Email from the JWT or null if not available.
     */
    public String getCurrentUserEmail() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            return (String) claims.get("email");
        } catch (Exception e) {
            // Async threads do not have an email context
            return null;
        }
    }

    public String getCurrentTenantSchema() {
        try {
            Map<String, Object> claims = getPrincipalClaims();
            String schema = (String) claims.get("schemaName");
            if (schema != null) return schema;
        } catch (Exception e) {
            // Complex Functionality: In background threads, we use the tenantId as the schema name.
            // This is critical for the Multi-tenant DB routing in FlowableDataService.
            String flowableTenantId = getTenantFromFlowableContext();
            if (flowableTenantId != null) return flowableTenantId;
        }
        return "public"; // Final safety fallback
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPrincipalClaims() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Map)) {
            throw new SecurityException("No authentication data found.");
        }
        return (Map<String, Object>) auth.getPrincipal();
    }

    /**
     * Extracts tenant info from the active engine thread.
     * Handles Async Service Tasks by checking Executions and Job attributes.
     */
    private String getTenantFromFlowableContext() {
        try {
            CommandContext commandContext = Context.getCommandContext();
            if (commandContext != null) {
                // 1. Try to find an active execution in the current command scope
                var executionEntityManager = CommandContextUtil.getExecutionEntityManager(commandContext);
                if (executionEntityManager != null) {
                    // Check the current execution's tenant
                    String tenantId = executionEntityManager.findChildExecutionsByProcessInstanceId(null)
                            .stream()
                            .map(e -> e.getTenantId())
                            .filter(t -> t != null && !t.isEmpty())
                            .findFirst()
                            .orElse(null);
                    if (tenantId != null) return tenantId;
                }

                // 2. Check if a Job triggered this (typical for Async Service Tasks)
                Object job = commandContext.getAttribute("job");
                if (job instanceof JobEntity) {
                    return ((JobEntity) job).getTenantId();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}