package com.example.flowable_app.features.iam;

import com.example.flowable_app.core.exception.BusinessException;
import com.example.flowable_app.core.exception.ErrorCode;
import com.example.flowable_app.core.response.ApiResponse;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.core.security.annotation.RequiresPermission;
import com.example.flowable_app.features.iam.service.CasbinService;
import com.example.flowable_app.features.iam.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

/**
 * REST Controller for Permission management.
 *
 * <p>Two URL prefixes used:</p>
 * <ul>
 *   <li>{@code /api/permissions/...} — accessible to ALL authenticated users (e.g., my-permissions)</li>
 *   <li>{@code /api/tenant/admin/permissions/...} — admin-only grant/revoke operations</li>
 * </ul>
 *
 * <p><strong>Consistency Guarantee:</strong> The {@code /my-permissions} endpoint and the
 * {@code /effective-access} endpoint in {@code UserController} now use the same evaluation
 * algorithm: DB-verified roles → per-resource {@code enforce()} call. This ensures the two
 * endpoints can never return conflicting results.</p>
 *
 * <p><strong>Grant/Revoke Validation:</strong> Before any Casbin mutation, both the roleId
 * AND the resource/action tuple are validated against the DB. This prevents:</p>
 * <ul>
 *   <li>Ghost grants: p-rules written for roles that no longer exist in {@code tbl_roles}.</li>
 *   <li>Phantom actions: p-rules referencing actions not registered in {@code tbl_resource_actions}.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "IAM - Permissions",
        description = "Endpoints for querying and mapping Roles to Resources via Casbin")
public class PermissionController {

    private final CasbinService casbinService;
    private final UserContextService userContextService;
    private final DSLContext dsl;
    private final UserManagementService userManagementService;

    // ─── PUBLIC: All authenticated users ───────────────────────────────────

    /**
     * Returns the flat permission map for the currently logged-in user.
     * Called by {@code PermissionContext.tsx} on every page load.
     *
     * <p>Response shape: {@code { "module:users:read": true, "module:access_control:manage": true, ... }}</p>
     *
     * <p><strong>Implementation Note — Why we use DB roles instead of Casbin g-rules:</strong></p>
     * <p>
     * We fetch the user's active roles directly from {@code tbl_user_roles} rather than
     * calling {@code casbinService.getImplicitRolesForUser(userId)} directly. This protects
     * against split-brain scenarios where a role is deleted from the DB but its Casbin g-rule
     * was not cleaned up. By using DB-verified roles and then checking Casbin strictly for those
     * roleIds, we bypass orphaned Casbin state and guarantee the permission map aligns perfectly
     * with the real database records.
     * </p>
     *
     * <p><strong>Consistency with effective-access:</strong> Both endpoints now use this
     * identical evaluation flow — DB roles + {@code enforce(roleId)} per resource/action.
     * They are guaranteed to return the same permission set for the same user state.</p>
     */
    @Operation(summary = "Get my permissions",
            description = "Returns a flat map of all permissions for the current user. " +
                    "Used by the frontend to show/hide UI elements. " +
                    "Evaluates DB-verified roles through the Casbin enforcer for each resource/action pair.")
    @GetMapping("/api/permissions/my-permissions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyPermissions() {
        String userId   = userContextService.getCurrentUserId();
        String tenantId = userContextService.getCurrentTenantId();
        String schema   = userContextService.getCurrentTenantSchema();

        log.info("📋 [MY PERMISSIONS] Computing for userId={} tenant={} schema={}",
                userId, tenantId, schema);

        // Fetch every resource:action combination registered in this tenant
        Result<Record2<String, String>> resourceActions = dsl
                .select(field("resource_key", String.class), field("action_name", String.class))
                .from(table(name(schema, "tbl_resource_actions")))
                .fetch();

        log.debug("📋 [MY PERMISSIONS] Found {} resource/action pairs to evaluate", resourceActions.size());

        /*
         * We fetch the user's active roles directly from the relational database (tbl_user_roles)
         * instead of calling casbinService.getImplicitRolesForUser(userId, ...).
         *
         * Why this was changed: If an admin deletes a role from the DB but the Casbin
         * policy engine transaction fails (a split-brain scenario), Casbin will still hold
         * a stale 'g' rule linking the user to the deleted role.
         *
         * By looping through DB-verified roles and checking Casbin strictly for those roleIds,
         * we bypass any orphaned memory in Casbin and guarantee the frontend permission map
         * perfectly aligns with the real database records.
         */
        List<String> activeRoles = userManagementService.getUserRoles(userId, schema);
        log.debug("📋 [MY PERMISSIONS] userId={} dbActiveRoles={}", userId, activeRoles);

        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (Record2<String, String> record : resourceActions) {
            String key    = record.value1();
            String action = record.value2();

            boolean hasAccess = false;
            for (String roleId : activeRoles) {
                // Evaluate Casbin engine using the roleId instead of userId.
                // enforce(roleId) follows role-inheritance chains transitively —
                // if roleId inherits from a parent role that has this permission, it returns true.
                if (casbinService.canDo(roleId, tenantId, schema, key, action)) {
                    hasAccess = true;
                    log.debug("📋 [MY PERMISSIONS] GRANTED via role={}: resource={} action={}",
                            roleId, key, action);
                    break; // Stop checking other roles once permission is confirmed
                }
            }

            if (hasAccess) {
                permissions.put(key + ":" + action, true);
            }
        }

        log.info("📋 [MY PERMISSIONS] Done — userId={} granted={}/{} permissions",
                userId, permissions.size(), resourceActions.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("tenantId", tenantId);
        result.put("permissions", permissions);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Returns all Casbin policies for a specific resource (all roles that have access to it).
     * Used by the Permissions Matrix "Group by Resource" read.
     *
     * <p>Returns raw stored p-rules. Does not follow inheritance.</p>
     */
    @Operation(summary = "Get permissions for a resource",
            description = "Returns all role:action policies associated with a specific resource key.")
    @GetMapping("/api/permissions/resource/{resourceKey}")
    public ResponseEntity<ApiResponse<List<List<String>>>> getResourcePermissions(
            @PathVariable String resourceKey) {

        String tenantId = userContextService.getCurrentTenantId();
        String schema   = userContextService.getCurrentTenantSchema();

        log.debug("📄 [RESOURCE PERMS] resource={} tenant={} schema={}", resourceKey, tenantId, schema);

        List<List<String>> policies = casbinService.getPoliciesForResource(tenantId, schema, resourceKey);

        log.debug("📄 [RESOURCE PERMS] resource={} policyCount={}", resourceKey, policies.size());
        return ResponseEntity.ok(ApiResponse.ok(policies));
    }

    // ─── ADMIN: Grant / Revoke ──────────────────────────────────────────────

    /**
     * Grants a permission by writing a new Casbin p-rule.
     *
     * <p><strong>Validation steps (in order):</strong></p>
     * <ol>
     *   <li>The target roleId must exist in {@code tbl_roles}. Prevents ghost grants
     *       that pollute casbin_rule with references to deleted or non-existent roles.</li>
     *   <li>The resource/action combination must exist in {@code tbl_resource_actions}.
     *       Prevents phantom permission grants for undefined capabilities.</li>
     * </ol>
     *
     * <p>The grant is idempotent: if the exact p-rule already exists in Casbin, a warning
     * is logged but no error is raised.</p>
     */
    @Operation(summary = "Grant a permission",
            description = "Writes a new Casbin policy rule granting a role access to a " +
                    "resource/action combination. Validates that both the role and resource/action " +
                    "exist in the DB before writing to Casbin.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/api/tenant/admin/permissions/grant")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> grantPermission(
            @Valid @RequestBody IamDto.Permission.GrantRequest request) {

        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();
        String adminId  = userContextService.getCurrentUserId();

        log.info("➕ [GRANT PERMISSION] role={} resource={} action={} tenant={} schema={} by={}",
                request.getRoleId(), request.getResource(), request.getAction(),
                tenantId, schema, adminId);

        // VALIDATION 1: Role must exist in the DB before we write a Casbin p-rule for it.
        // Without this check, granting permissions to a deleted role would silently
        // populate casbin_rule with orphaned p-rules that can never be matched.
        if (!userManagementService.doesRoleExist(request.getRoleId(), schema)) {
            log.warn("⚠️ [GRANT PERMISSION] Rejected — role does not exist: roleId={} schema={}",
                    request.getRoleId(), schema);
            throw new BusinessException(
                    "Role '" + request.getRoleId() + "' does not exist in this tenant.",
                    ErrorCode.RESOURCE_NOT_FOUND,
                    org.springframework.http.HttpStatus.NOT_FOUND
            );
        }

        // VALIDATION 2: Resource/action must exist in tbl_resource_actions.
        // Ensures we never grant permissions for capabilities not registered in the system.
        List<Map<String, Object>> validActions =
                userManagementService.getActionsForResource(request.getResource(), schema);
        boolean isValidAction = validActions.stream()
                .anyMatch(a -> request.getAction().equals(a.get("action_name")));

        if (!isValidAction) {
            log.warn("⚠️ [GRANT PERMISSION] Rejected — invalid resource/action: " +
                            "resource={} action={} schema={}",
                    request.getResource(), request.getAction(), schema);
            throw new BusinessException(
                    "Invalid permission request. The resource '" + request.getResource() +
                            "' or action '" + request.getAction() + "' does not exist.",
                    ErrorCode.VALIDATION_ERROR,
                    org.springframework.http.HttpStatus.BAD_REQUEST
            );
        }

        // Write the Casbin p-rule
        casbinService.grantPermissionToRole(
                request.getRoleId(), tenantId, schema,
                request.getResource(), request.getAction()
        );

        log.info("✅ [GRANT PERMISSION] Granted — role={} resource={} action={}",
                request.getRoleId(), request.getResource(), request.getAction());

        return ResponseEntity.ok(ApiResponse.message("Permission granted successfully"));
    }

    /**
     * Revokes a permission by deleting a Casbin p-rule.
     *
     * <p>NOTE: Uses POST (not DELETE) because the request body contains the policy details.
     * DELETE with a body is technically allowed but causes problems with some HTTP clients.</p>
     *
     * <p>The revoke is idempotent: if the p-rule does not exist in Casbin (e.g., already
     * revoked or never granted), a warning is logged and the operation returns success.
     * This tolerates the case where the DB was already updated but Casbin was stale.</p>
     *
     * <p><strong>Validation:</strong> The roleId is validated against the DB to detect
     * revoke attempts on non-existent roles, which could mask sync issues.</p>
     */
    @Operation(summary = "Revoke a permission",
            description = "Deletes a specific Casbin policy rule for a role. Idempotent — " +
                    "returns success even if the rule did not exist (logs a warning for debugging).")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/api/tenant/admin/permissions/revoke")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> revokePermission(
            @Valid @RequestBody IamDto.Permission.GrantRequest request) {

        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();
        String adminId  = userContextService.getCurrentUserId();

        log.info("➖ [REVOKE PERMISSION] role={} resource={} action={} tenant={} schema={} by={}",
                request.getRoleId(), request.getResource(), request.getAction(),
                tenantId, schema, adminId);

        // VALIDATION: Warn if the role doesn't exist in DB (may indicate a sync issue
        // where an operator is trying to clean up a deleted role's permissions manually).
        if (!userManagementService.doesRoleExist(request.getRoleId(), schema)) {
            log.warn("⚠️ [REVOKE PERMISSION] Target role does not exist in DB: roleId={}. " +
                            "Proceeding with Casbin revoke to clean up potential orphaned p-rules.",
                    request.getRoleId());
            // NOTE: We do NOT throw here — the revoke is still valid as a cleanup operation.
            // If someone is trying to revoke a permission for a deleted role, it's likely
            // a cleanup operation and we should allow it through.
        }

        casbinService.revokePermissionFromRole(
                request.getRoleId(), tenantId, schema,
                request.getResource(), request.getAction()
        );

        log.info("✅ [REVOKE PERMISSION] Revoked — role={} resource={} action={}",
                request.getRoleId(), request.getResource(), request.getAction());

        return ResponseEntity.ok(ApiResponse.message("Permission revoked successfully"));
    }
}