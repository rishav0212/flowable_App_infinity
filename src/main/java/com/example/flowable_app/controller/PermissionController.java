package com.example.flowable_app.controller;

import com.example.flowable_app.entity.Tenant;
import com.example.flowable_app.repository.TenantRepository;
import com.example.flowable_app.service.AllowedUserService;
import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final AllowedUserService allowedUserService;
    private final TenantRepository tenantRepository;

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


    // ==================================================================================
    // 🟢 NEW: INTERNAL TOOLJET BFF ENDPOINT
    // ==================================================================================

    @GetMapping("/internal/tooljet-permissions")
    public ResponseEntity<?> getToolJetPermissions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String email,
            @RequestParam String organisationId) { // Updated parameter to directly accept organisationId from the URL

        try {
            // 1. Resolve Schema from Organisation ID
            // We use the organisationId to fetch the tenant details because in this architecture,
            // the organisation acts as the tenant, dictating which isolated database schema to query.
            Tenant tenant = tenantRepository.findById(organisationId)
                    .orElseThrow(() -> new RuntimeException("Tenant/Organisation not found"));
            String schema = tenant.getSchemaName();

            // 2. 🟢 SMART LOOKUP: If userId is missing (Developer Mode), find it via email
            if ((userId == null || userId.trim().isEmpty()) && email != null && !email.trim().isEmpty()) {
                userId = allowedUserService.getUserIdByEmail(email, schema);
                if (userId == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body("User with email " + email + " not found in organisation " + organisationId);
                }
            }

            if (userId == null || userId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Either userId or email must be provided");
            }

            // 3. Fetch all registered resources & actions
            Result<Record2<String, String>> resourceActions = dsl.select(
                            field("resource_key", String.class),
                            field("action_name", String.class))
                    .from(table(name(schema, "tbl_resource_actions")))
                    .fetch();

            Map<String, Boolean> permissions = new LinkedHashMap<>();

            // 4. Evaluate Casbin rules for this specific user
            // We pass the organisationId into casbinService.canDo() as the domain/tenant parameter.
            // This ensures the permission check is strictly scoped to the user's specific organisation environment.
            for (Record2<String, String> record : resourceActions) {
                String key = record.value1();
                String action = record.value2();
                if (casbinService.canDo(userId, organisationId, schema, key, action)) {
                    permissions.put(key + ":" + action, true);
                }
            }

            log.info("✅ Internal Permissions fetched for User: {} in Organisation: {}", userId, organisationId);

            return ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "organisationId", organisationId, // Returning organisationId in the payload to maintain consistency with the request
                    "permissions", permissions
            ));

        } catch (Exception e) {
            log.error("❌ Failed to fetch internal permissions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}