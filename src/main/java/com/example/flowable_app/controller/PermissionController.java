package com.example.flowable_app.controller;

import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        String userId   = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema   = userContextService.getCurrentTenantSchema();

        // 1. Fetch ALL resource keys registered for this tenant
        List<String> allKeys = dsl.select(field("resource_key", String.class))
                .from(table(name(schema, "tbl_resources")))
                .fetchInto(String.class);

        // 2. Run Casbin enforce() for each key with action "view"
        Map<String, Boolean> permissions = casbinService.getPermissionMap(userId, tenantId, schema, allKeys);

        // 3. ADVANCED: Check CRUD actions for tables
        List<String> tableKeys = allKeys.stream()
                .filter(k -> k.startsWith("table:") && !k.contains(":col:"))
                .collect(Collectors.toList());

        for (String tableKey : tableKeys) {
            for (String action : List.of("create", "edit", "execute", "delete")) {
                String permKey = tableKey + ":" + action;
                boolean canAct = casbinService.canDo(userId, tenantId, schema, tableKey, action);
                permissions.put(permKey, canAct);
            }
        }

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "tenantId", tenantId,
                "permissions", permissions
        ));
    }
}