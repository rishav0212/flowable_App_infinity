package com.example.flowable_app.controller;

import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
    private final DSLContext dsl;

    @GetMapping("/my-permissions")
    public ResponseEntity<?> getMyPermissions() {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        // 1. Fetch ALL resource keys registered for this tenant
        List<String> allKeys = dsl.select(field("resource_key", String.class))
                .from(table(name(schema, "tbl_resources")))
                .fetchInto(String.class);

        Map<String, Boolean> permissions = new LinkedHashMap<>();

        // 2. Loop through all keys and check the exact actions the frontend expects
        for (String key : allKeys) {

            // Check 'view' (Standard for pages and components)
            boolean canView = casbinService.canDo(userId, tenantId, schema, key, "view");
            if (canView) {
                permissions.put(key + ":view", true); // ✅ Matches frontend format exactly
            }

            // Check 'execute' (Standard for actions and buttons)
            boolean canExecute = casbinService.canDo(userId, tenantId, schema, key, "execute");
            if (canExecute) {
                permissions.put(key + ":execute", true); // ✅ Matches frontend format exactly
            }

            // Check extra CRUD actions if it is a table
            if (key.startsWith("table:")) {
                if (casbinService.canDo(userId, tenantId, schema, key, "create"))
                    permissions.put(key + ":create", true);
                if (casbinService.canDo(userId, tenantId, schema, key, "edit")) permissions.put(key + ":edit", true);
                if (casbinService.canDo(userId, tenantId, schema, key, "delete"))
                    permissions.put(key + ":delete", true);
            }
        }

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "tenantId", tenantId,
                "permissions", permissions
        ));
    }
}