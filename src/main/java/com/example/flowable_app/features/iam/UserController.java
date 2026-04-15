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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST Controller for User management within a tenant.
 * Base URL: /api/tenant/admin/users
 *
 * <p><strong>Dual-Write Contract:</strong> Every mutating operation that involves role
 * assignments MUST update BOTH the relational DB ({@code tbl_user_roles} via
 * {@code UserManagementService}) AND the Casbin engine (g-rules via {@code CasbinService}).
 * The correct order is:</p>
 * <ol>
 *   <li>DB write inside {@code @Transactional} — if this fails, nothing persists.</li>
 *   <li>Casbin write AFTER the DB call returns — if this fails, the DB is rolled back
 *       by the surrounding {@code @Transactional} (because Casbin throws before the
 *       method exits). Log the failure for manual investigation.</li>
 * </ol>
 *
 * <p><strong>Effective Access vs My-Permissions — Root Cause & Fix:</strong></p>
 * <p>
 * The previous implementation of {@code getEffectiveAccess} used {@code getPoliciesForRoles}
 * (raw Casbin p-rules from {@code getFilteredPolicy}) which returns ONLY directly stored rules.
 * Meanwhile, {@code getMyPermissions} uses {@code enforcer.enforce()} which transitively follows
 * role-inheritance (g-rules). These two code paths produced different results when a role
 * inherited permissions from a parent role. Fixed by making {@code getEffectiveAccess} use the
 * same evaluation path as {@code getMyPermissions}: DB-verified roles + per-resource
 * {@code enforce()} check.
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant/admin/users")
@RequiredArgsConstructor
@Tag(name = "IAM - Users", description = "Endpoints for managing users within a specific tenant")
public class UserController {

    private final UserManagementService userManagementService;
    private final UserContextService userContextService;
    private final CasbinService casbinService;

    // ─── USER CRUD ─────────────────────────────────────────────────────────

    @Operation(summary = "Get all users",
            description = "Retrieves a list of all active and inactive users in the current tenant schema.")
    @RequiresPermission(resource = "module:users", action = "read")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUsers() {
        String schema = userContextService.getCurrentTenantSchema();
        log.info("📋 [GET USERS] schema={}", schema);

        List<Map<String, Object>> users = userManagementService.getAllUsers(schema);
        log.info("📋 [GET USERS] Returned {} users for schema={}", users.size(), schema);
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    @Operation(summary = "Create a user",
            description = "Provisions a new user account within the current tenant.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Void>> createUser(
            @Valid @RequestBody IamDto.User.CreateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        log.info("➕ [CREATE USER] Requested: userId={} email={} schema={} by={}",
                request.getUserId(), request.getEmail(), schema, currentUserId);

        userManagementService.createUser(
                request.getUserId(), request.getEmail(), request.getFirstName(),
                request.getLastName(), request.getMetadata(), schema, currentUserId
        );

        log.info("✅ [CREATE USER] userId={} created successfully", request.getUserId());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.message("User created successfully"));
    }

    @Operation(summary = "Update a user",
            description = "Updates a user's basic profile fields. Only provided fields are updated.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> updateUser(
            @PathVariable String userId,
            @Valid @RequestBody IamDto.User.UpdateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        log.info("✏️ [UPDATE USER] userId={} schema={}", userId, schema);

        userManagementService.updateUser(
                userId,
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getIsActive(),
                schema
        );

        log.info("✅ [UPDATE USER] userId={} updated", userId);
        return ResponseEntity.ok(ApiResponse.message("User updated successfully"));
    }

    @Operation(summary = "Deactivate a user",
            description = "Soft-deactivates a user. They cannot log in but their history is preserved.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}/deactivate")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        String adminId = userContextService.getCurrentUserId();

        log.info("🔒 [DEACTIVATE USER] userId={} schema={} by={}", userId, schema, adminId);
        userManagementService.deactivateUser(userId, schema, adminId);
        log.info("✅ [DEACTIVATE USER] userId={} deactivated", userId);

        return ResponseEntity.ok(ApiResponse.message("User deactivated successfully"));
    }

    @Operation(summary = "Reactivate a user",
            description = "Re-enables a previously deactivated user account.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}/reactivate")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> reactivateUser(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();

        log.info("🔓 [REACTIVATE USER] userId={} schema={}", userId, schema);

        // Reuse updateUser with only isActive = true
        userManagementService.updateUser(userId, null, null, null, true, schema);

        log.info("✅ [REACTIVATE USER] userId={} reactivated", userId);
        return ResponseEntity.ok(ApiResponse.message("User reactivated successfully"));
    }

    @Operation(summary = "Delete a user",
            description = "Hard deletes a user and removes all of their role assignments from both " +
                    "the database (tbl_user_roles) and the Casbin policy engine (g-rules).")
    @RequiresPermission(resource = "module:users", action = "delete")
    @DeleteMapping("/{userId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();

        log.info("🗑️ [DELETE USER] Starting dual-write delete for userId={} schema={}", userId, schema);

        // STEP 1: Remove from Relational DB (tbl_users + tbl_user_roles cascade)
        // This is inside @Transactional — any failure here rolls back the DB.
        userManagementService.deleteUser(userId, schema);

        // STEP 2: Remove orphaned role bindings from Casbin (g-rules)
        // NOTE: Casbin writes are NOT part of the Spring transaction. If this fails after
        // the DB commit, orphaned g-rules will remain in casbin_rule. Use the
        // POST /reconcile endpoint to detect and clean up such drift.
        try {
            casbinService.removeUserCompletely(userId, schema);
        } catch (Exception casbinEx) {
            // Log as ERROR but do NOT re-throw — the DB delete succeeded and the user
            // can no longer log in. The orphaned Casbin g-rules will be harmless because
            // the application always validates roles against the DB first (getUserRoles).
            // Operators should run /reconcile to clean up.
            log.error("❌ [DELETE USER] DB delete succeeded but Casbin cleanup FAILED for userId={}. " +
                            "Orphaned g-rules may remain in casbin_rule. Run /reconcile to fix. Error: {}",
                    userId, casbinEx.getMessage(), casbinEx);
        }

        log.info("✅ [DELETE USER] userId={} fully deleted", userId);
        return ResponseEntity.ok(ApiResponse.message("User deleted successfully"));
    }

    // ─── ROLE ASSIGNMENTS ──────────────────────────────────────────────────

    @Operation(summary = "Get user roles",
            description = "Fetches a list of Role IDs currently assigned to a specific user " +
                    "from the authoritative tbl_user_roles table (not Casbin in-memory state).")
    @RequiresPermission(resource = "module:users", action = "read")
    @GetMapping("/{userId}/roles")
    public ResponseEntity<ApiResponse<List<String>>> getUserRoles(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        log.debug("📋 [GET USER ROLES] userId={} schema={}", userId, schema);

        List<String> roles = userManagementService.getUserRoles(userId, schema);
        return ResponseEntity.ok(ApiResponse.ok(roles));
    }

    @Operation(summary = "Assign role to user",
            description = "Binds a role to a user. Updates BOTH the database (tbl_user_roles) " +
                    "and the Casbin policy engine (g-rules). Validates that both the user and " +
                    "role exist in the DB before writing.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PostMapping("/{userId}/roles/{roleId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> assignRoleToUser(
            @PathVariable String userId,
            @PathVariable String roleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();
        String currentUserId = userContextService.getCurrentUserId();

        log.info("🔗 [ASSIGN ROLE] userId={} role={} tenant={} schema={} by={}",
                userId, roleId, tenantId, schema, currentUserId);

        // VALIDATION: Ensure the target user exists in the DB before assigning.
        // Prevents g-rules being added to Casbin for a non-existent user.
        if (!userManagementService.doesUserExist(userId, schema)) {
            log.warn("⚠️ [ASSIGN ROLE] Rejected — user does not exist: userId={} schema={}",
                    userId, schema);
            throw new BusinessException(
                    "User '" + userId + "' does not exist in this tenant.",
                    ErrorCode.RESOURCE_NOT_FOUND,
                    org.springframework.http.HttpStatus.NOT_FOUND
            );
        }

        // VALIDATION: Ensure the target role exists in the DB before assigning.
        // Prevents user being linked to a "ghost" role that has no definition.
        if (!userManagementService.doesRoleExist(roleId, schema)) {
            log.warn("⚠️ [ASSIGN ROLE] Rejected — role does not exist: roleId={} schema={}",
                    roleId, schema);
            throw new BusinessException(
                    "Role '" + roleId + "' does not exist in this tenant.",
                    ErrorCode.RESOURCE_NOT_FOUND,
                    org.springframework.http.HttpStatus.NOT_FOUND
            );
        }

        // STEP 1: Update the relational DB table (tbl_user_roles) — inside @Transactional
        userManagementService.assignRoleToUser(userId, roleId, schema, currentUserId);

        // STEP 2: Update Casbin policy engine (MUST happen or permissions won't work!)
        // If this throws, the @Transactional will roll back the DB insert above.
        casbinService.assignRoleToUser(userId, roleId, tenantId, schema);

        log.info("✅ [ASSIGN ROLE] userId={} role={} assigned in both DB and Casbin", userId, roleId);
        return ResponseEntity.ok(ApiResponse.message("Role assigned successfully"));
    }

    @Operation(summary = "Remove role from user",
            description = "Removes a role from a user. Updates BOTH the database (tbl_user_roles) " +
                    "and the Casbin policy engine (g-rules).")
    @RequiresPermission(resource = "module:users", action = "manage")
    @DeleteMapping("/{userId}/roles/{roleId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> removeRoleFromUser(
            @PathVariable String userId,
            @PathVariable String roleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.info("✂️ [REMOVE ROLE] userId={} role={} tenant={} schema={}", userId, roleId, tenantId, schema);

        // STEP 1: Update the relational DB table
        userManagementService.removeRoleFromUser(userId, roleId, schema);

        // STEP 2: Update Casbin policy engine
        // If this throws, @Transactional rolls back the DB delete above.
        casbinService.removeRoleFromUser(userId, roleId, tenantId, schema);

        log.info("✅ [REMOVE ROLE] userId={} role={} removed from both DB and Casbin", userId, roleId);
        return ResponseEntity.ok(ApiResponse.message("Role removed successfully"));
    }

    // ─── EFFECTIVE ACCESS ──────────────────────────────────────────────────

    /**
     * Resolves the full effective permission matrix for a user, following role-inheritance chains.
     *
     * <p><strong>Previous Bug & Fix:</strong></p>
     * <p>
     * The old implementation used {@code getPoliciesForRoles(effectiveRoles)} which calls
     * {@code getFilteredPolicy} — returning only DIRECTLY stored Casbin p-rules. This missed
     * permissions inherited through role-to-role g-rules, causing a mismatch with
     * {@code /my-permissions} (which uses {@code enforce()} and correctly follows inheritance).
     * </p>
     *
     * <p>The new implementation uses the same algorithm as {@code /my-permissions}:</p>
     * <ol>
     *   <li>Get authoritative direct roles from DB ({@code tbl_user_roles}).</li>
     *   <li>Expand to include inherited roles by walking Casbin g-rules.</li>
     *   <li>Filter inherited roles against the DB role list to exclude orphaned g-rules.</li>
     *   <li>For each resource/action pair, call {@code enforce()} on each effective role.</li>
     * </ol>
     *
     * <p>The response includes both the raw per-role policy breakdown ({@code "directPolicies"})
     * for admin transparency and the resolved effective permission map ({@code "permissions"})
     * that matches exactly what {@code /my-permissions} returns for the same user.</p>
     */
    @Operation(summary = "Get effective access",
            description = "Resolves direct and inherited roles to return the full effective " +
                    "permission matrix for a user. The 'permissions' map in the response " +
                    "is guaranteed to be consistent with /api/permissions/my-permissions " +
                    "because both use the same enforcement algorithm (DB-verified roles + Casbin enforce()).")
    @RequiresPermission(resource = "module:users", action = "read")
    @GetMapping("/{userId}/effective-access")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEffectiveAccess(
            @PathVariable String userId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.info("🔍 [EFFECTIVE ACCESS] Computing for userId={} tenant={} schema={}",
                userId, tenantId, schema);

        // ── Step 1: Get direct roles from DB (authoritative source of truth) ──────────
        List<String> directRoles = userManagementService.getUserRoles(userId, schema);
        log.debug("🔍 [EFFECTIVE ACCESS] directRoles={}", directRoles);

        // ── Step 2: Walk the inheritance graph to get all effective roles ─────────────
        //
        // We call getImplicitRolesForUser on each ROLE (not the userId) to get the
        // roles that *that role* inherits from. This resolves the full hierarchy.
        //
        // Note: We keep directRoles first in a LinkedHashSet to maintain insertion order.
        Set<String> effectiveRolesSet = new LinkedHashSet<>(directRoles);
        for (String role : directRoles) {
            List<String> inherited = casbinService.getImplicitRolesForUser(role, tenantId, schema);
            if (inherited != null) {
                effectiveRolesSet.addAll(inherited);
            }
        }

        // ── Step 3: Filter inherited roles against DB-verified roles ─────────────────
        //
        // WHY: If a Casbin g-rule points to a role that was deleted from the DB but
        // the Casbin cleanup failed (split-brain), we must not include that orphaned
        // role in the effective set. This prevents ghost-role permissions from leaking.
        //
        // Strategy: keep any role that's in tbl_roles OR in the user's direct DB roles
        // (direct roles are already DB-verified above).
        Set<String> allDbRoleIds = new HashSet<>(userManagementService.getAllRoleIds(schema));
        Set<String> filteredEffectiveRoles = new LinkedHashSet<>();
        for (String role : effectiveRolesSet) {
            if (allDbRoleIds.contains(role)) {
                filteredEffectiveRoles.add(role);
            } else {
                log.warn("⚠️ [EFFECTIVE ACCESS] Orphaned Casbin g-rule detected — role='{}' " +
                                "exists in Casbin but NOT in tbl_roles for schema={}. " +
                                "Excluded from effective access. Run /reconcile to fix.",
                        role, schema);
            }
        }

        List<String> effectiveRoles = new ArrayList<>(filteredEffectiveRoles);
        log.debug("🔍 [EFFECTIVE ACCESS] effectiveRoles (DB-filtered)={}", effectiveRoles);

        // ── Step 4: Build flat permission map (same algorithm as /my-permissions) ─────
        //
        // We iterate ALL resource/action combinations from tbl_resource_actions and call
        // enforce() for each effective role. This is the exact same evaluation path used by
        // getMyPermissions, guaranteeing consistent results between the two endpoints.
        //
        // We call enforce() with the roleId (not userId) because at this stage we've already
        // verified which roles the user has from the DB. This prevents stale Casbin g-rules
        // from influencing the result.
        List<org.jooq.Record2<String, String>> resourceActions = dsl
                .select(org.jooq.impl.DSL.field("resource_key", String.class),
                        org.jooq.impl.DSL.field("action_name", String.class))
                .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name(schema, "tbl_resource_actions")))
                .fetch();

        Map<String, Boolean> permissions = new LinkedHashMap<>();
        for (org.jooq.Record2<String, String> record : resourceActions) {
            String key    = record.value1();
            String action = record.value2();
            String permKey = key + ":" + action;

            boolean hasAccess = false;
            for (String roleId : effectiveRoles) {
                if (casbinService.checkEffectivePermission(roleId, tenantId, schema, key, action)) {
                    hasAccess = true;
                    log.debug("🔍 [EFFECTIVE ACCESS] GRANTED: userId={} role={} resource={} action={}",
                            userId, roleId, key, action);
                    break; // Stop checking other roles once permission is confirmed
                }
            }

            if (hasAccess) {
                permissions.put(permKey, true);
            }
        }

        // ── Step 5: Also fetch raw direct policies per role for admin transparency ─────
        //
        // This shows which roles DIRECTLY have which policies in casbin_rule, useful for
        // debugging the matrix. This is intentionally separate from the 'permissions' map.
        List<List<String>> directPolicies = casbinService.getPoliciesForRoles(
                effectiveRoles, tenantId, schema);

        log.info("🔍 [EFFECTIVE ACCESS] Done — userId={} directRoles={} effectiveRoles={} " +
                        "totalPermissions={} grantedPermissions={}",
                userId, directRoles.size(), effectiveRoles.size(),
                resourceActions.size(), permissions.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("tenantId", tenantId);
        result.put("roles", directRoles);             // Roles directly assigned in tbl_user_roles
        result.put("effectiveRoles", effectiveRoles); // Direct + inherited (DB-verified)
        result.put("permissions", permissions);       // Flat map — matches /my-permissions exactly
        result.put("directPolicies", directPolicies); // Raw casbin_rule entries (for debugging)

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── RECONCILIATION ────────────────────────────────────────────────────

    /**
     * Reconciles a specific user's Casbin g-rules against the authoritative DB role list.
     *
     * <p>This endpoint detects and repairs split-brain scenarios where the DB
     * ({@code tbl_user_roles}) and Casbin (casbin_rule g-rules) have diverged for a user,
     * typically caused by a prior partial failure during role assignment or deletion.</p>
     *
     * <p><strong>What it does:</strong></p>
     * <ul>
     *   <li>Reads current roles from {@code tbl_user_roles} (the source of truth).</li>
     *   <li>Reads current g-rules for the user from Casbin in-memory state.</li>
     *   <li>Removes orphaned Casbin g-rules (in Casbin but not in DB).</li>
     *   <li>Adds missing Casbin g-rules (in DB but not in Casbin).</li>
     * </ul>
     */
    @Operation(summary = "Reconcile user roles",
            description = "Detects and repairs drift between tbl_user_roles (DB) and Casbin g-rules " +
                    "for a specific user. Returns a summary of changes made.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/{userId}/reconcile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconcileUser(
            @PathVariable String userId) {

        String schema    = userContextService.getCurrentTenantSchema();
        String tenantId  = userContextService.getCurrentTenantId();

        log.info("🔧 [RECONCILE] Starting reconciliation for userId={} tenant={} schema={}",
                userId, tenantId, schema);

        // The DB is always the authoritative source
        List<String> dbRoles = userManagementService.getUserRoles(userId, schema);

        Map<String, List<String>> changes = casbinService.reconcileUserRoles(
                userId, tenantId, dbRoles, schema);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("tenantId", tenantId);
        result.put("dbRoles", dbRoles);
        result.put("orphanedRolesRemoved", changes.get("removed"));
        result.put("missingRolesAdded", changes.get("added"));
        result.put("inSync", changes.get("removed").isEmpty() && changes.get("added").isEmpty());

        log.info("✅ [RECONCILE] Done for userId={} — orphanedRemoved={} missingAdded={}",
                userId, changes.get("removed").size(), changes.get("added").size());

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Forces a full reload of the Casbin enforcer for this tenant from the casbin_rule table.
     *
     * <p>Use this after direct DB migrations or when the in-memory enforcer state is suspected
     * to be stale. The enforcer cache is evicted and the next request re-initializes it by
     * calling {@code loadPolicy()} against the live casbin_rule table.</p>
     */
    @Operation(summary = "Reload Casbin policies",
            description = "Evicts and reloads the Casbin enforcer cache for the current tenant " +
                    "from the casbin_rule table. Use after direct DB changes or suspected stale state.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/casbin/reload")
    public ResponseEntity<ApiResponse<Void>> reloadCasbinPolicies() {
        String schema = userContextService.getCurrentTenantSchema();

        log.info("🔄 [CASBIN RELOAD] Requested for schema={}", schema);
        casbinService.reloadPolicy(schema);
        log.info("✅ [CASBIN RELOAD] Cache evicted for schema={}", schema);

        return ResponseEntity.ok(ApiResponse.message(
                "Casbin enforcer cache cleared. Policies will be reloaded from DB on next access."));
    }

    // NOTE: DSLContext is injected by @RequiredArgsConstructor alongside the other final fields.
    // It is used in getEffectiveAccess to query tbl_resource_actions — the same pattern
    // used by PermissionController.
    private final org.jooq.DSLContext dsl;
}