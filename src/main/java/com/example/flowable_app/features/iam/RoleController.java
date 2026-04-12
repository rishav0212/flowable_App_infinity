package com.example.flowable_app.features.iam;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tenant/roles")
@RequiredArgsConstructor
@Tag(name = "IAM - Roles", description = "Endpoints for managing RBAC Roles within a tenant")
public class RoleController {

    private final UserManagementService userManagementService;
    private final UserContextService userContextService;
    private final CasbinService casbinService; // <-- ADD THIS

    @Operation(summary = "Get all roles",
            description = "Retrieves all custom and system roles available in the current tenant.")
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRoles() {
        String tenantId = userContextService.getCurrentTenantSchema();
        List<Map<String, Object>> roles = userManagementService.getAllRoles(tenantId);
        return ResponseEntity.ok(ApiResponse.ok(roles));
    }

    @Operation(summary = "Create a role",
            description = "Creates a new functional role that can be assigned permissions.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createRole(@Valid @RequestBody IamDto.Role.CreateRequest request) {
        String tenantId = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.createRole(
                request.getRoleId(), request.getRoleName(), request.getDescription(), tenantId, currentUserId
        );
        return ResponseEntity.ok(ApiResponse.message("Role created successfully"));
    }

    @Operation(summary = "Update a role", description = "Updates the display name or description of an existing role.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PutMapping("/{roleId}")
    public ResponseEntity<ApiResponse<Void>> updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody IamDto.Role.UpdateRequest request) {
        String tenantId = userContextService.getCurrentTenantSchema();

        userManagementService.updateRole(
                roleId, request.getRoleName(), request.getDescription(), tenantId
        );
        return ResponseEntity.ok(ApiResponse.message("Role updated successfully"));
    }

    @Operation(summary = "Delete a role", description = "Deletes a role and unassigns it from all users.")
    @RequiresPermission(resource = "module:access_control", action = "delete")
    @DeleteMapping("/{roleId}")
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable String roleId) {
        String tenantId = userContextService.getCurrentTenantSchema();
        userManagementService.deleteRole(roleId, tenantId);
        return ResponseEntity.ok(ApiResponse.message("Role deleted successfully"));
    }


    // ─── ROLE INHERITANCE (LEAN CASBIN) ────────────────────────────────────

    @Operation(summary = "Get inherited roles", description = "Returns a list of roles that this role currently inherits permissions from.")
    @RequiresPermission(resource = "module:access_control", action = "view")
    @GetMapping("/{roleId}/inherits")
    public ResponseEntity<ApiResponse<List<String>>> getInheritedRoles(@PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        List<String> inheritedRoles = casbinService.getInheritedRoles(roleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.ok(inheritedRoles));
    }

    @Operation(summary = "Add role inheritance", description = "Makes the base role inherit all permissions of the target role.")
    @RequiresPermission(resource = "module:access_control", action = "manage")
    @PostMapping("/{roleId}/inherits/{inheritsRoleId}")
    public ResponseEntity<ApiResponse<Void>> addRoleInheritance(
            @PathVariable String roleId,
            @PathVariable String inheritsRoleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 🛡️ CYCLICAL DEPENDENCY GUARD
        if (casbinService.causesCycle(roleId, inheritsRoleId, tenantId, schema)) {
            // Throwing a bad request through our universal ApiResponse!
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "VALIDATION_ERROR",
                    "Circular dependency detected. This would create an infinite loop."
            ));
        }

        casbinService.addRoleInheritance(roleId, inheritsRoleId, tenantId, schema);
        return ResponseEntity.ok(ApiResponse.message("Role inheritance added successfully"));
    }

    @Operation(summary = "Remove role inheritance", description = "Removes the inheritance link between two roles.")
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