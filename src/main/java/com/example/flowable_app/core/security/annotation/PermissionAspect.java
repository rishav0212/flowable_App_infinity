package com.example.flowable_app.core.security.annotation;

import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.features.iam.service.CasbinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j // 🟢 Added for logging
public class PermissionAspect {

    private final CasbinService casbinService;
    private final UserContextService userContextService;

    @Before("@annotation(perm)")
    public void check(JoinPoint jp, RequiresPermission perm) {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        // =====================================================================
        // 🚨 TEMPORARY SUPER ADMIN BYPASS
        // Replace "YOUR_EMAIL_OR_ID_HERE" with your actual logged-in userId
        // =====================================================================
        if ("Rishab_J".equals(userId)) {
            log.warn("🔓 CASBIN BYPASS ACTIVE: Granting absolute access to super admin: {}", userId);
            return; // Exit the method early. No exception thrown = Access Granted!
        }

        // Standard Security Check
        if (!casbinService.canDo(userId, tenantId, schema, perm.resource(), perm.action())) {
            throw new AccessDeniedException("Casbin Security: You do not have permission (" +
                    perm.action() +
                    ") for resource: " +
                    perm.resource());
        }
    }


    @Before("@annotation(anyPerm)")
    public void checkAny(JoinPoint jp, RequiresAnyPermission anyPerm) {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        // Super Admin Bypass
        if ("Rishab_J".equals(userId)) return;

        for (String permString : anyPerm.value()) {
            // Split by the LAST colon so "module:users:read" becomes "module:users" and "read"
            int lastColonIdx = permString.lastIndexOf(":");

            if (lastColonIdx > 0) {
                String resource = permString.substring(0, lastColonIdx);
                String action = permString.substring(lastColonIdx + 1);

                // If the user has ANY of the permissions in the array, grant access and exit!
                if (casbinService.canDo(userId, tenantId, schema, resource, action)) {
                    return;
                }
            }
        }

        // If it finishes the loop without returning, they don't have any of the required permissions
        throw new AccessDeniedException("Casbin Security: You need at least one of the required permissions: " +
                String.join(", ", anyPerm.value()));
    }
}