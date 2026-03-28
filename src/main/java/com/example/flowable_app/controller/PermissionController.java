package com.example.flowable_app.controller;

import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

        // Dynamically fetch all resources AND their supported actions from the relational database.
        // This completely eliminates hardcoded checks for "view", "execute", "create", etc.,
        // allowing the system to scale to infinite custom action types seamlessly.
        Result<Record2<String, String>> resourceActions = dsl.select(
                        field("resource_key", String.class),
                        field("action_name", String.class))
                .from(table(name(schema, "tbl_resource_actions")))
                .fetch();

        Map<String, Boolean> permissions = new LinkedHashMap<>();

        // Loop through the precise action map defined in the database
        for (Record2<String, String> record : resourceActions) {
            String key = record.value1();
            String action = record.value2();

            // Check Casbin only for the actions that are explicitly registered for this resource
            boolean canDoAction = casbinService.canDo(userId, tenantId, schema, key, action);
            if (canDoAction) {
                permissions.put(key + ":" + action, true); // ✅ Matches frontend format exactly
            }
        }

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "tenantId", tenantId,
                "permissions", permissions
        ));
    }

    @GetMapping("/resource/{resourceKey}")
    public ResponseEntity<?> getResourcePermissions(@PathVariable String resourceKey) {
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema(); // 🟢 Get the schema

        // 🟢 Pass the schema to the service
        List<List<String>> policies = casbinService.getPoliciesForResource(tenantId, schema, resourceKey);
        return ResponseEntity.ok(policies);
    }
}