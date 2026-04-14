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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller for User management within a tenant.
 * Base URL: /api/tenant/admin/users
 *
 * IMPORTANT: Both userManagementService (DB) AND casbinService (policy engine)
 * must be updated for role assignments to take effect.
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
        List<Map<String, Object>> users = userManagementService.getAllUsers(schema);
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    @Operation(summary = "Create a user",
            description = "Provisions a new user account within the current tenant.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createUser(
            @Valid @RequestBody IamDto.User.CreateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.createUser(
                request.getUserId(), request.getEmail(), request.getFirstName(),
                request.getLastName(), request.getMetadata(), schema, currentUserId
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.message("User created successfully"));
    }

    @Operation(summary = "Update a user",
            description = "Updates a user's basic profile fields. Only provided fields are updated.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> updateUser(
            @PathVariable String userId,
            @Valid @RequestBody IamDto.User.UpdateRequest request) {

        String schema = userContextService.getCurrentTenantSchema();

        userManagementService.updateUser(
                userId,
                request.getEmail(),
                request.getFirstName(),
                request.getLastName(),
                request.getIsActive(),
                schema
        );

        return ResponseEntity.ok(ApiResponse.message("User updated successfully"));
    }

    @Operation(summary = "Deactivate a user",
            description = "Soft-deactivates a user. They cannot log in but their history is preserved.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        String adminId = userContextService.getCurrentUserId();

        userManagementService.deactivateUser(userId, schema, adminId);
        return ResponseEntity.ok(ApiResponse.message("User deactivated successfully"));
    }

    @Operation(summary = "Reactivate a user",
            description = "Re-enables a previously deactivated user account.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivateUser(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();

        // Reuse updateUser with only isActive = true
        userManagementService.updateUser(userId, null, null, null, true, schema);
        return ResponseEntity.ok(ApiResponse.message("User reactivated successfully"));
    }

    @Operation(summary = "Delete a user",
            description = "Hard deletes a user and removes all of their role assignments.")
    @RequiresPermission(resource = "module:users", action = "delete")
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        userManagementService.deleteUser(userId, schema);
        return ResponseEntity.ok(ApiResponse.message("User deleted successfully"));
    }

    // ─── ROLE ASSIGNMENTS ──────────────────────────────────────────────────

    @Operation(summary = "Get user roles",
            description = "Fetches a list of Role IDs currently assigned to a specific user.")
    @RequiresPermission(resource = "module:users", action = "view")
    @GetMapping("/{userId}/roles")
    public ResponseEntity<ApiResponse<List<String>>> getUserRoles(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        List<String> roles = userManagementService.getUserRoles(userId, schema);
        return ResponseEntity.ok(ApiResponse.ok(roles));
    }

    @Operation(summary = "Assign role to user",
            description = "Binds a role to a user. Updates BOTH the database and the Casbin policy engine.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PostMapping("/{userId}/roles/{roleId}")
    public ResponseEntity<ApiResponse<Void>> assignRoleToUser(
            @PathVariable String userId,
            @PathVariable String roleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId(); // ← real tenantId for Casbin domain
        String currentUserId = userContextService.getCurrentUserId();

        // 1. Update the relational DB table (tbl_user_roles)
        userManagementService.assignRoleToUser(userId, roleId, schema, currentUserId);

        // 2. Update Casbin policy engine (MUST happen or permissions don't work!)
        casbinService.assignRoleToUser(userId, roleId, tenantId, schema);

        return ResponseEntity.ok(ApiResponse.message("Role assigned successfully"));
    }

    @Operation(summary = "Remove role from user",
            description = "Removes a role from a user. Updates BOTH the database and the Casbin policy engine.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @DeleteMapping("/{userId}/roles/{roleId}")
    public ResponseEntity<ApiResponse<Void>> removeRoleFromUser(
            @PathVariable String userId,
            @PathVariable String roleId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId(); // ← real tenantId for Casbin domain

        // 1. Update the relational DB table
        userManagementService.removeRoleFromUser(userId, roleId, schema);

        // 2. Update Casbin policy engine
        casbinService.removeRoleFromUser(userId, roleId, tenantId, schema);

        return ResponseEntity.ok(ApiResponse.message("Role removed successfully"));
    }

    // ─── EFFECTIVE ACCESS ──────────────────────────────────────────────────

    @Operation(summary = "Get effective access",
            description = "Resolves direct and inherited roles to return the full effective permission matrix for a user.")
    @RequiresPermission(resource = "module:users", action = "view")
    @GetMapping("/{userId}/effective-access")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEffectiveAccess(
            @PathVariable String userId) {

        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Get direct roles from DB
        List<String> directRoles = userManagementService.getUserRoles(userId, schema);

        // 2. Walk the inheritance graph to get all effective roles
        Set<String> effectiveRolesSet = new HashSet<>(directRoles);
        for (String role : directRoles) {
            List<String> inherited = casbinService.getImplicitRolesForUser(role, tenantId, schema);
            if (inherited != null) {
                effectiveRolesSet.addAll(inherited);
            }
        }

        List<String> effectiveRoles = new ArrayList<>(effectiveRolesSet);

        // 3. Fetch all policies for these combined roles
        List<List<String>> policies = casbinService.getPoliciesForRoles(effectiveRoles, tenantId, schema);

        Map<String, Object> result = new HashMap<>();
        result.put("roles", directRoles);
        result.put("effectiveRoles", effectiveRoles);
        result.put("policies", policies);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}