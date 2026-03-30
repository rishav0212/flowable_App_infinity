package com.example.flowable_app.controller;

import com.example.flowable_app.entity.Tenant;
import com.example.flowable_app.entity.ToolJetWorkspace;
import com.example.flowable_app.repository.TenantRepository;
import com.example.flowable_app.repository.ToolJetWorkspaceRepository;
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

    private final ToolJetWorkspaceRepository toolJetWorkspaceRepository; // Inject this new repo

    @GetMapping("/internal/tooljet-permissions")
    public ResponseEntity<?> getToolJetPermissions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String email,
            @RequestParam String organisationId) { // This is the workspace_uuid from ToolJet

        try {
            // 1. Bridge Lookup: Find the Tenant mapping via ToolJet Workspace UUID
            // WHY: ToolJet identifies itself with its own UUID. We use our mapping table
            // (tbl_tooljet_workspaces) to resolve which internal tenant this belongs to.
            ToolJetWorkspace mapping = toolJetWorkspaceRepository.findByWorkspaceUuid(organisationId)
                    .orElseThrow(() -> new RuntimeException("No tenant mapping found for Workspace UUID: " + organisationId));

            // 2. Resolve Tenant & Schema
            // WHY: mapping.getTenant() already provides the full Tenant object thanks to the
            // JPA @OneToOne relationship. We extract the ID and Schema name directly from it.
            Tenant tenant = mapping.getTenant();
            if (tenant == null) {
                throw new RuntimeException("Tenant relationship is null for mapping ID: " + mapping.getId());
            }

            String schema = tenant.getSchemaName();
            String internalTenantId = tenant.getId();

            // 3. 🟢 SMART LOOKUP: Find internal userId via email if missing (Common in Developer Mode)
            if ((userId == null || userId.trim().isEmpty()) && email != null && !email.trim().isEmpty()) {
                userId = allowedUserService.getUserIdByEmail(email, schema);
                if (userId == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body("User with email " + email + " not found in tenant schema " + schema);
                }
            }

            if (userId == null || userId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Either userId or email must be provided");
            }

            // 4. Fetch all registered resources & actions from the Resolved Schema
            // WHY: This ensures we check permissions for every single action defined
            // specifically for this tenant's environment.
            Result<Record2<String, String>> resourceActions = dsl.select(
                            field("resource_key", String.class),
                            field("action_name", String.class))
                    .from(table(name(schema, "tbl_resource_actions")))
                    .fetch();

            Map<String, Boolean> permissions = new LinkedHashMap<>();

            // 5. Evaluate Casbin rules
            // We use the internal tenant UUID for the Casbin domain check to ensure isolation.
            for (Record2<String, String> record : resourceActions) {
                String key = record.value1();
                String action = record.value2();
                if (casbinService.canDo(userId, internalTenantId, schema, key, action)) {
                    permissions.put(key + ":" + action, true);
                }
            }

            log.info("✅ Permissions successfully mapped for ToolJet Workspace: {} (Tenant: {})", organisationId, internalTenantId);

            return ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "organisationId", organisationId,
                    "tenantId", internalTenantId,
                    "permissions", permissions
            ));

        } catch (Exception e) {
            log.error("❌ Failed to fetch internal permissions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }}