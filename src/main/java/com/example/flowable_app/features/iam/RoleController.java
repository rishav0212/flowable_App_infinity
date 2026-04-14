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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for Role management within a tenant.
 * Base URL: /api/tenant/admin/roles
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
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRoles() {
        String schema = userContextService.getCurrentTenantSchema();
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.getAllRoles(schema)));
    }

    @Operation(summary = "Create a role",
            description = "Creates a new functional role that can be assigned permissions.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createRole(
            @Valid @RequestBody IamDto.Role.CreateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.createRole(
                request.getRoleId(), request.getRoleName(),
                request.getDescription(), schema, currentUserId
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.message("Role created successfully"));
    }

    @Operation(summary = "Update a role",
            description = "Updates the display name or description of an existing role.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PutMapping("/{roleId}")
    public ResponseEntity<ApiResponse<Void>> updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody IamDto.Role.UpdateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();

        userManagementService.updateRole(
                roleId, request.getRoleName(), request.getDescription(), schema
        );

        return ResponseEntity.ok(ApiResponse.message("Role updated successfully"));
    }

    @Operation(summary = "Delete a role",
            description = "Deletes a role, removes all user assignments, and removes all Casbin policies for this role.")
    @RequiresPermission(resource = "module:access_control", action = "delete")
    @DeleteMapping("/{roleId}")
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Remove from DB (tbl_roles + tbl_user_roles assignments)
        userManagementService.deleteRole(roleId, schema);

        // 2. Remove ALL Casbin policies for this role (permissions + group assignments)
        // Without this, orphaned policies remain in casbin_rule table
        casbinService.removeRoleCompletely(roleId, tenantId, schema);

        return ResponseEntity.ok(ApiResponse.message("Role deleted successfully"));
    }

    // ─── ROLE PERMISSIONS ──────────────────────────────────────────────────

    @Operation(summary = "Get permissions for a role",
            description = "Returns all Casbin policies assigned to a specific role.")
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping("/{roleId}/permissions")
    public ResponseEntity<ApiResponse<List<List<String>>>> getRolePermissions(
            @PathVariable String roleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        List<List<String>> policies = casbinService.getPoliciesForRole(roleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.ok(policies));
    }

    // ─── ROLE INHERITANCE ──────────────────────────────────────────────────

    @Operation(summary = "Get inherited roles",
            description = "Returns a list of roles that this role currently inherits permissions from.")
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping("/{roleId}/inherits")
    public ResponseEntity<ApiResponse<List<String>>> getInheritedRoles(
            @PathVariable String roleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        List<String> inheritedRoles = casbinService.getInheritedRoles(roleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.ok(inheritedRoles));
    }

    @Operation(summary = "Add role inheritance",
            description = "Makes the base role inherit all permissions of the target role.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/{roleId}/inherits/{inheritsRoleId}")
    public ResponseEntity<ApiResponse<Void>> addRoleInheritance(
            @PathVariable String roleId,
            @PathVariable String inheritsRoleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // Guard against circular dependency
        if (casbinService.causesCycle(roleId, inheritsRoleId, tenantId, schema)) {
            // FIX: ApiResponse.error(message, errorCode) — message is FIRST, errorCode is SECOND
            throw new BusinessException(
                    "Circular dependency detected. This inheritance would create an infinite loop.",
                    ErrorCode.CIRCULAR_DEPENDENCY,
                    org.springframework.http.HttpStatus.BAD_REQUEST
            );
        }

        casbinService.addRoleInheritance(roleId, inheritsRoleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.message("Role inheritance added successfully"));
    }

    @Operation(summary = "Remove role inheritance",
            description = "Removes the inheritance link between two roles.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @DeleteMapping("/{roleId}/inherits/{inheritsRoleId}")
    public ResponseEntity<ApiResponse<Void>> removeRoleInheritance(
            @PathVariable String roleId,
            @PathVariable String inheritsRoleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        casbinService.removeRoleInheritance(roleId, inheritsRoleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.message("Role inheritance removed successfully"));
    }
}