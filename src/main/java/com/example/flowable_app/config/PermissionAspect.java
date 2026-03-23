package com.example.flowable_app.config;

import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final CasbinService casbinService;
    private final UserContextService userContextService;

    @Before("@annotation(perm)")
    public void check(JoinPoint jp, RequiresPermission perm) {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        if (!casbinService.canDo(userId, tenantId, schema, perm.resource(), perm.action())) {
            throw new AccessDeniedException("Casbin Security: You do not have permission (" +
                    perm.action() +
                    ") for resource: " +
                    perm.resource());
        }
    }
}