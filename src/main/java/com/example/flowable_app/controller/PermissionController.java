package com.example.flowable_app.controller;

import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@Slf4j
public class PermissionController {

    private final CasbinService casbinService;
    private final UserContextService userContextService;
    private final DSLContext dsl; // Swapped to jOOQ

    /**
     * ToolJet calls this ON PAGE LOAD to build its UI variables.
     */
    @GetMapping("/my-permissions")
    public ResponseEntity<?> getMyPermissions() {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        // 1. Fetch all available resource keys dynamically using jOOQ
        List<String> allKeys = dsl.select(field("resource_key", String.class))
                .from(table(name(schema, "tbl_resources")))
                .fetchInto(String.class);

        // 2. Evaluate them against Casbin
        Map<String, Boolean> permMap = casbinService.getPermissionMap(userId, tenantId, schema, allKeys);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "tenantId", tenantId,
                "permissions", permMap
        ));
    }
}