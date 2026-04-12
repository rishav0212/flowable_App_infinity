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
@RequestMapping("/api/tenant/users")
@RequiredArgsConstructor
@Tag(name = "IAM - Users", description = "Endpoints for managing users within a specific tenant")
public class UserController {

    private final UserManagementService userManagementService;
    private final UserContextService userContextService;
    private final CasbinService casbinService; // <-- ADD THIS

    @Operation(summary = "Get all users",
            description = "Retrieves a list of all active and inactive users in the current tenant schema.")
    @RequiresPermission(resource = "module:users", action = "read")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUsers() {
        String tenantId = userContextService.getCurrentTenantSchema();
        List<Map<String, Object>> users = userManagementService.getAllUsers(tenantId);
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    @Operation(summary = "Create a user", description = "Provisions a new user account within the current tenant.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> createUser(@Valid @RequestBody IamDto.User.CreateRequest request) {
        String tenantId = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.createUser(
                request.getUserId(), request.getEmail(), request.getFirstName(),
                request.getLastName(), request.getMetadata(), tenantId, currentUserId
        );
        return ResponseEntity.ok(ApiResponse.message("User created successfully"));
    }

    @Operation(summary = "Update a user", description = "Updates a user's basic profile or activation status.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PutMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> updateUser(
            @PathVariable String userId,
            @Valid @RequestBody IamDto.User.UpdateRequest request) {
        String tenantId = userContextService.getCurrentTenantSchema();

        userManagementService.updateUser(
                userId, request.getEmail(), request.getFirstName(),
                request.getLastName(), request.getIsActive(), tenantId
        );
        return ResponseEntity.ok(ApiResponse.message("User updated successfully"));
    }

    @Operation(summary = "Delete a user",
            description = "Hard deletes a user and removes all of their role assignments.")
    @RequiresPermission(resource = "module:users", action = "delete")
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable String userId) {
        String tenantId = userContextService.getCurrentTenantSchema();
        userManagementService.deleteUser(userId, tenantId);
        return ResponseEntity.ok(ApiResponse.message("User deleted successfully"));
    }

    // ─── ROLE ASSIGNMENTS ──────────────────────────────────────────────────

    @Operation(summary = "Get user roles",
            description = "Fetches a list of Role IDs currently assigned to a specific user.")
    @RequiresPermission(resource = "module:users", action = "view")
    @GetMapping("/{userId}/roles")
    public ResponseEntity<ApiResponse<List<String>>> getUserRoles(@PathVariable String userId) {
        String tenantId = userContextService.getCurrentTenantSchema();
        List<String> roles = userManagementService.getUserRoles(userId, tenantId);
        return ResponseEntity.ok(ApiResponse.ok(roles));
    }

    @Operation(summary = "Assign role to user",
            description = "Binds a Casbin role to a specific user within the tenant.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @PostMapping("/{userId}/roles/{roleId}")
    public ResponseEntity<ApiResponse<Void>> assignRoleToUser(
            @PathVariable String userId, @PathVariable String roleId) {
        String tenantId = userContextService.getCurrentTenantSchema();
        String currentUserId = userContextService.getCurrentUserId();

        userManagementService.assignRoleToUser(userId, roleId, tenantId, currentUserId);
        return ResponseEntity.ok(ApiResponse.message("Role assigned successfully"));
    }

    @Operation(summary = "Remove role from user", description = "Unbinds a Casbin role from a specific user.")
    @RequiresPermission(resource = "module:users", action = "manage")
    @DeleteMapping("/{userId}/roles/{roleId}")
    public ResponseEntity<ApiResponse<Void>> removeRoleFromUser(
            @PathVariable String userId, @PathVariable String roleId) {
        String tenantId = userContextService.getCurrentTenantSchema();
        userManagementService.removeRoleFromUser(userId, roleId, tenantId);
        return ResponseEntity.ok(ApiResponse.message("Role removed successfully"));
    }

    // ─── EFFECTIVE ACCESS ──────────────────────────────────────────────────

    @Operation(summary = "Get effective access",
            description = "Resolves all direct and inherited roles to return the full matrix of effective permissions for a user.")
    @RequiresPermission(resource = "module:users", action = "view")
    @GetMapping("/{userId}/effective-access")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEffectiveAccess(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Get exact direct roles from the relational DB
        List<String> directRoles = userManagementService.getUserRoles(userId, schema);

        // 2. Resolve all inherited roles using Casbin's graph
        java.util.Set<String> effectiveRolesSet = new java.util.HashSet<>(directRoles);

        for (String role : directRoles) {
            List<String> inherited = casbinService.getImplicitRolesForUser(role, tenantId, schema);
            if (inherited != null) {
                effectiveRolesSet.addAll(inherited);
            }
        }

        List<String> effectiveRoles = new java.util.ArrayList<>(effectiveRolesSet);

        // 3. Fetch all policies (permissions) for these combined effective roles
        List<List<String>> policies = casbinService.getPoliciesForRoles(effectiveRoles, tenantId, schema);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "roles", directRoles,
                "effectiveRoles", effectiveRoles,
                "policies", policies
        )));
    }
}