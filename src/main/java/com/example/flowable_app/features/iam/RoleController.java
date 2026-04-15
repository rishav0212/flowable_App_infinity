package com.example.flowable_app.features.iam;

import com.example.flowable_app.core.exception.BusinessException;
import com.example.flowable_app.core.exception.ErrorCode;
import com.example.flowable_app.core.response.ApiResponse;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.core.security.annotation.RequiresAnyPermission;
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

import java.util.List;
import java.util.Map;

/**
 * REST Controller for Role management within a tenant.
 * Base URL: /api/tenant/admin/roles
 *
 * <p><strong>Dual-Write Contract:</strong></p>
 * <p>Roles live only in {@code tbl_roles} (DB). Casbin is NOT updated on role CREATE or UPDATE —
 * only on DELETE (to clean up orphaned p-rules and g-rules). Role permissions are managed
 * separately via {@code PermissionController} which writes Casbin p-rules. Role assignments
 * to users are managed via {@code UserController} which writes Casbin g-rules.</p>
 *
 * <p><strong>Delete Sync:</strong> {@code deleteRole} performs a strict dual-write:</p>
 * <ol>
 *   <li>Removes from {@code tbl_user_roles} and {@code tbl_roles} (DB, inside {@code @Transactional}).</li>
 *   <li>Removes ALL p-rules and g-rules for this role from Casbin (via {@code removeRoleCompletely}).</li>
 * </ol>
 * <p>If Casbin cleanup fails after DB deletion, orphaned rules remain. Use the
 * {@code POST /reconcile} endpoint on {@code UserController} or call {@code reloadPolicy}
 * to recover.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant/admin/roles")
@RequiredArgsConstructor
@Tag(name = "IAM - Roles", description = "Endpoints for managing RBAC Roles within a tenant")
public class RoleController {

    private final UserManagementService userManagementService;
    private final UserContextService userContextService;
    private final CasbinService casbinService;

    // ─── ROLE CRUD ─────────────────────────────────────────────────────────

    @Operation(summary = "Get all roles",
            description = "Retrieves all custom and system roles available in the current tenant.")
    @RequiresAnyPermission({
            "module:access_control:read",
            "module:users:read"
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRoles() {
        String schema = userContextService.getCurrentTenantSchema();
        log.debug("📋 [GET ROLES] schema={}", schema);

        List<Map<String, Object>> roles = userManagementService.getAllRoles(schema);
        log.debug("📋 [GET ROLES] Returned {} roles for schema={}", roles.size(), schema);

        return ResponseEntity.ok(ApiResponse.ok(roles));
    }

    @Operation(summary = "Create a role",
            description = "Creates a new functional role that can be assigned permissions. " +
                    "The role is persisted to tbl_roles only. Casbin is NOT modified at create " +
                    "time — permissions are added separately via the grant endpoint.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Void>> createRole(
            @Valid @RequestBody IamDto.Role.CreateRequest request) {

        String schema         = userContextService.getCurrentTenantSchema();
        String currentUserId  = userContextService.getCurrentUserId();

        log.info("🎭 [CREATE ROLE] roleId={} roleName={} schema={} by={}",
                request.getRoleId(), request.getRoleName(), schema, currentUserId);

        userManagementService.createRole(
                request.getRoleId(), request.getRoleName(),
                request.getDescription(), schema, currentUserId
        );

        log.info("✅ [CREATE ROLE] roleId={} created in schema={}", request.getRoleId(), schema);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.message("Role created successfully"));
    }

    @Operation(summary = "Update a role",
            description = "Updates the display name or description of an existing role. " +
                    "The role_id (which is used as the Casbin subject) is immutable — " +
                    "all existing p-rules and g-rules remain valid after an update.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PutMapping("/{roleId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody IamDto.Role.UpdateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        log.info("✏️ [UPDATE ROLE] roleId={} schema={}", roleId, schema);

        userManagementService.updateRole(
                roleId, request.getRoleName(), request.getDescription(), schema
        );

        log.info("✅ [UPDATE ROLE] roleId={} updated (Casbin not affected — role_id is immutable)",
                roleId);

        return ResponseEntity.ok(ApiResponse.message("Role updated successfully"));
    }

    @Operation(summary = "Delete a role",
            description = "Deletes a role and performs a full dual-write cleanup: " +
                    "(1) removes from tbl_user_roles and tbl_roles in DB, " +
                    "(2) removes ALL Casbin p-rules (permissions) and g-rules (user assignments " +
                    "and inheritance links) for this role. " +
                    "Without the Casbin cleanup, orphaned policies would grant permissions on a " +
                    "role that no longer exists.")
    @RequiresPermission(resource = "module:access_control", action = "delete")
    @DeleteMapping("/{roleId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable String roleId) {
        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.info("🗑️ [DELETE ROLE] Starting dual-write delete for roleId={} tenant={} schema={}",
                roleId, tenantId, schema);

        // STEP 1: Remove from DB (tbl_roles + tbl_user_roles assignments)
        // This is inside @Transactional — failure here rolls back the DB.
        userManagementService.deleteRole(roleId, schema);

        // STEP 2: Remove ALL Casbin policies for this role (p-rules and g-rules)
        // Without this, orphaned policies remain in casbin_rule and users assigned to this
        // role (before deletion) would still appear to have its permissions via stale g-rules.
        //
        // NOTE: Casbin writes are NOT part of the Spring transaction. If this fails after
        // the DB commit, orphaned Casbin rules remain. Use reloadPolicy or reconcile to fix.
        try {
            casbinService.removeRoleCompletely(roleId, tenantId, schema);
        } catch (Exception casbinEx) {
            // The DB deletion succeeded. Casbin cleanup failed. Log as ERROR and allow the
            // response to succeed — the role no longer exists in the DB so it cannot be assigned.
            // Orphaned Casbin p-rules are harmless because getMyPermissions validates roles from DB first.
            // However, orphaned g-rules (user-role bindings) could cause false positives in
            // raw Casbin queries. Run reloadPolicy to clean up.
            log.error("❌ [DELETE ROLE] DB delete succeeded but Casbin cleanup FAILED for roleId={}. " +
                            "Orphaned p-rules and g-rules may remain in casbin_rule. " +
                            "Call POST /users/casbin/reload to rebuild from DB. Error: {}",
                    roleId, casbinEx.getMessage(), casbinEx);
        }

        log.info("✅ [DELETE ROLE] roleId={} fully deleted", roleId);
        return ResponseEntity.ok(ApiResponse.message("Role deleted successfully"));
    }

    // ─── ROLE PERMISSIONS ──────────────────────────────────────────────────

    @Operation(summary = "Get permissions for a role",
            description = "Returns all Casbin p-rules directly assigned to a specific role. " +
                    "Note: this shows DIRECTLY stored rules only — inherited permissions from " +
                    "parent roles are not included. For full effective permissions, use " +
                    "GET /users/{userId}/effective-access.")
    @RequiresPermission(resource = "module:access_control", action = "read")
    @GetMapping("/{roleId}/permissions")
    public ResponseEntity<ApiResponse<List<List<String>>>> getRolePermissions(
            @PathVariable String roleId) {

        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.debug("📄 [ROLE PERMISSIONS] roleId={} tenant={} schema={}", roleId, tenantId, schema);

        List<List<String>> policies = casbinService.getPoliciesForRole(roleId, tenantId, schema);

        log.debug("📄 [ROLE PERMISSIONS] roleId={} directPolicies={}", roleId, policies.size());
        return ResponseEntity.ok(ApiResponse.ok(policies));
    }

    // ─── ROLE INHERITANCE ──────────────────────────────────────────────────

    @Operation(summary = "Get inherited roles",
            description = "Returns a list of roles that this role directly inherits permissions from. " +
                    "Only direct parents are returned (single hop). For the full transitive " +
                    "inheritance graph, examine effective-access for a user assigned this role.")
    @RequiresPermission(resource = "module:access_control", action = "read")
    @GetMapping("/{roleId}/inherits")
    public ResponseEntity<ApiResponse<List<String>>> getInheritedRoles(
            @PathVariable String roleId) {

        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.debug("🔗 [ROLE INHERITS] roleId={} tenant={} schema={}", roleId, tenantId, schema);

        List<String> inheritedRoles = casbinService.getInheritedRoles(roleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.ok(inheritedRoles));
    }

    @Operation(summary = "Add role inheritance",
            description = "Makes the base role inherit all permissions of the target role. " +
                    "Validates that the target role exists in the DB and checks for circular dependencies.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/{roleId}/inherits/{inheritsRoleId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> addRoleInheritance(
            @PathVariable String roleId,
            @PathVariable String inheritsRoleId) {

        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.info("🔗 [ADD INHERITANCE] role={} → inherits={} tenant={} schema={}",
                roleId, inheritsRoleId, tenantId, schema);

        // VALIDATION 1: The base role must exist in the DB
        if (!userManagementService.doesRoleExist(roleId, schema)) {
            log.warn("⚠️ [ADD INHERITANCE] Base role does not exist: roleId={} schema={}", roleId, schema);
            throw new BusinessException(
                    "Role '" + roleId + "' does not exist in this tenant.",
                    ErrorCode.RESOURCE_NOT_FOUND,
                    org.springframework.http.HttpStatus.NOT_FOUND
            );
        }

        // VALIDATION 2: The target role (the one being inherited FROM) must exist in the DB.
        // Without this check, adding inheritance to a non-existent role would create a
        // dangling g-rule in casbin_rule that can never resolve to any p-rules.
        if (!userManagementService.doesRoleExist(inheritsRoleId, schema)) {
            log.warn("⚠️ [ADD INHERITANCE] Target role does not exist: inheritsRoleId={} schema={}",
                    inheritsRoleId, schema);
            throw new BusinessException(
                    "Role '" + inheritsRoleId + "' does not exist in this tenant. " +
                            "Cannot inherit from a non-existent role.",
                    ErrorCode.RESOURCE_NOT_FOUND,
                    org.springframework.http.HttpStatus.NOT_FOUND
            );
        }

        // VALIDATION 3: Guard against circular dependency in the role inheritance graph.
        // A cycle would cause getImplicitRolesForUser to loop infinitely.
        if (casbinService.causesCycle(roleId, inheritsRoleId, tenantId, schema)) {
            log.warn("⚠️ [ADD INHERITANCE] Circular dependency detected: role={} → inheritsRoleId={}",
                    roleId, inheritsRoleId);
            throw new BusinessException(
                    "Circular dependency detected. This inheritance would create an infinite loop.",
                    ErrorCode.CIRCULAR_DEPENDENCY,
                    org.springframework.http.HttpStatus.BAD_REQUEST
            );
        }

        casbinService.addRoleInheritance(roleId, inheritsRoleId, tenantId, schema);

        log.info("✅ [ADD INHERITANCE] role={} now inherits from role={}", roleId, inheritsRoleId);
        return ResponseEntity.ok(ApiResponse.message("Role inheritance added successfully"));
    }

    @Operation(summary = "Remove role inheritance",
            description = "Removes the inheritance link between two roles. " +
                    "After this call, the base role will no longer inherit any permissions " +
                    "from the target role.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @DeleteMapping("/{roleId}/inherits/{inheritsRoleId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> removeRoleInheritance(
            @PathVariable String roleId,
            @PathVariable String inheritsRoleId) {

        String schema   = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        log.info("✂️ [REMOVE INHERITANCE] role={} ✂ inherits={} tenant={} schema={}",
                roleId, inheritsRoleId, tenantId, schema);

        casbinService.removeRoleInheritance(roleId, inheritsRoleId, tenantId, schema);

        log.info("✅ [REMOVE INHERITANCE] role={} no longer inherits from role={}", roleId, inheritsRoleId);
        return ResponseEntity.ok(ApiResponse.message("Role inheritance removed successfully"));
    }
}