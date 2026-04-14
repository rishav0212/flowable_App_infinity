package com.example.flowable_app.features.iam;

import com.example.flowable_app.core.response.ApiResponse;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.core.security.annotation.RequiresPermission;
import com.example.flowable_app.features.iam.service.CasbinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

/**
 * REST Controller for Permission management.
 *
 * Two URL prefixes used:
 *  - /api/permissions/...        ← accessible to ALL authenticated users (e.g., my-permissions)
 *  - /api/tenant/admin/permissions/... ← admin-only operations
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "IAM - Permissions", description = "Endpoints for querying and mapping Roles to Resources via Casbin")
public class PermissionController {

    private final CasbinService casbinService;
    private final UserContextService userContextService;
    private final DSLContext dsl;

    // ─── PUBLIC: All authenticated users ───────────────────────────────────

    /**
     * Returns the flat permission map for the currently logged-in user.
     * Called by PermissionContext.tsx on every page load.
     * Response shape: { "module:users:view": true, "module:access_control:manage": true, ... }
     */
    @Operation(summary = "Get my permissions",
            description = "Returns a flat map of all permissions for the current user. Used by the frontend to show/hide UI elements.")
    @GetMapping("/api/permissions/my-permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPermissions() {
        String userId = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        // Fetch every resource:action combination registered in this tenant
        Result<Record2<String, String>> resourceActions = dsl
                .select(field("resource_key", String.class), field("action_name", String.class))
                .from(table(name(schema, "tbl_resource_actions")))
                .fetch();

        // Check Casbin for each combination — only include what the user actually has
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (Record2<String, String> record : resourceActions) {
            String key = record.value1();
            String action = record.value2();
            if (casbinService.canDo(userId, tenantId, schema, key, action)) {
                permissions.put(key + ":" + action, true);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("tenantId", tenantId);
        result.put("permissions", permissions);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Returns all Casbin policies for a specific resource (all roles that have access to it).
     * Used by the Permissions Matrix "Group by Resource" view.
     */
    @Operation(summary = "Get permissions for a resource",
            description = "Returns all role:action policies associated with a specific resource key.")
    @GetMapping("/api/permissions/resource/{resourceKey}")
    public ResponseEntity<ApiResponse<List<List<String>>>> getResourcePermissions(
            @PathVariable String resourceKey) {

        String tenantId = userContextService.getCurrentTenantId();
        String schema = userContextService.getCurrentTenantSchema();

        List<List<String>> policies = casbinService.getPoliciesForResource(tenantId, schema, resourceKey);
        return ResponseEntity.ok(ApiResponse.ok(policies));
    }

    // ─── ADMIN: Grant / Revoke ──────────────────────────────────────────────

    @Operation(summary = "Grant a permission",
            description = "Writes a new Casbin policy rule granting a role access to a resource/action combination.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/api/tenant/admin/permissions/grant")
    public ResponseEntity<ApiResponse<Void>> grantPermission(
            @Valid @RequestBody IamDto.Permission.GrantRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        casbinService.grantPermissionToRole(
                request.getRoleId(), tenantId, schema,
                request.getResource(), request.getAction()
        );

        return ResponseEntity.ok(ApiResponse.message("Permission granted successfully"));
    }

    /**
     * Revokes a permission.
     * NOTE: Uses POST (not DELETE) because the request body contains the policy details.
     * DELETE with a body is technically allowed but causes problems with some HTTP clients.
     */
    @Operation(summary = "Revoke a permission",
            description = "Deletes a specific Casbin policy rule for a role.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/api/tenant/admin/permissions/revoke")
    public ResponseEntity<ApiResponse<Void>> revokePermission(
            @Valid @RequestBody IamDto.Permission.GrantRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        casbinService.revokePermissionFromRole(
                request.getRoleId(), tenantId, schema,
                request.getResource(), request.getAction()
        );

        return ResponseEntity.ok(ApiResponse.message("Permission revoked successfully"));
    }
}