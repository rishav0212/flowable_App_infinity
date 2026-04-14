package com.example.flowable_app.core.security.annotation;

import com.example.flowable_app.features.iam.service.CasbinService;
import com.example.flowable_app.core.security.UserContextService;
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
}