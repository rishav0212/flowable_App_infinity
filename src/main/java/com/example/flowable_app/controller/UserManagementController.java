package com.example.flowable_app.controller;

import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import com.example.flowable_app.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tenant/admin")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userMgmtService;
    private final CasbinService casbinService;
    private final UserContextService userContextService;

    // === USERS ===

    @GetMapping("/users")
    public ResponseEntity<?> listUsers() {
        return ResponseEntity.ok(userMgmtService.getAllUsers(userContextService.getCurrentTenantSchema()));
    }

    @PostMapping("/users")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> payload) {
        String userId = (String) payload.get("userId");
        String email = (String) payload.get("email");
        String firstName = (String) payload.get("firstName");
        String lastName = (String) payload.get("lastName");
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");

        userMgmtService.createUser(userId, email, firstName, lastName, metadata,
                userContextService.getCurrentTenantSchema(),
                userContextService.getCurrentUserId());

        return ResponseEntity.ok(Map.of("message", "User created successfully"));
    }


    @GetMapping("/roles/{roleId}/permissions")
    public ResponseEntity<?> getRolePermissions(@PathVariable String roleId) {
        return ResponseEntity.ok(casbinService.getPoliciesForRole(roleId,
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema()));
    }

    @PutMapping("/users/{userId}/deactivate")
    public ResponseEntity<?> deactivateUser(@PathVariable String userId) {
        userMgmtService.deactivateUser(userId, userContextService.getCurrentTenantSchema(), userContextService.getCurrentUserId());
        return ResponseEntity.ok(Map.of("message", "User deactivated"));
    }

    // === ROLES & ASSIGNMENTS ===

    @GetMapping("/roles")
    public ResponseEntity<?> listRoles() {
        return ResponseEntity.ok(userMgmtService.getAllRoles(userContextService.getCurrentTenantSchema()));
    }

    @PostMapping("/roles")
    public ResponseEntity<?> createRole(@RequestBody Map<String, Object> payload) {
        String roleId = (String) payload.get("roleId");
        String roleName = (String) payload.get("roleName");
        String description = (String) payload.get("description");

        userMgmtService.createRole(roleId, roleName, description,
                userContextService.getCurrentTenantSchema(),
                userContextService.getCurrentUserId());

        return ResponseEntity.ok(Map.of("message", "Role created"));
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<?> assignRole(@PathVariable String userId, @PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Save to relational DB for UI tracking
        userMgmtService.assignRoleToUser(userId, roleId, schema, userContextService.getCurrentUserId());
        // 2. Tell Casbin about the new mapping
        casbinService.assignRoleToUser(userId, roleId, tenantId, schema);

        return ResponseEntity.ok(Map.of("message", "Role assigned to user"));
    }

    // === PERMISSION MATRIX ENGINE ===

    @GetMapping("/resources")
    public ResponseEntity<?> listResources() {
        return ResponseEntity.ok(userMgmtService.getAllResources(userContextService.getCurrentTenantSchema()));
    }

    @PostMapping("/resources")
    public ResponseEntity<?> createResource(@RequestBody Map<String, Object> payload) {
        String resourceKey = (String) payload.get("resourceKey");
        String resourceType = (String) payload.get("resourceType");
        String displayName = (String) payload.get("displayName");
        String description = (String) payload.get("description");

        userMgmtService.registerResource(resourceKey, resourceType, displayName, description,
                userContextService.getCurrentTenantSchema(),
                userContextService.getCurrentUserId());

        return ResponseEntity.ok(Map.of("message", "Resource registered"));
    }

    @PostMapping("/permissions/grant")
    public ResponseEntity<?> grantPermission(@RequestBody Map<String, Object> payload) {
        casbinService.grantPermissionToRole(
                (String) payload.get("roleId"),
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema(),
                (String) payload.get("resourceKey"),
                (String) payload.get("action")
        );
        return ResponseEntity.ok(Map.of("message", "Permission granted"));
    }

    @PostMapping("/permissions/revoke")
    public ResponseEntity<?> revokePermission(@RequestBody Map<String, Object> payload) {
        casbinService.revokePermissionFromRole(
                (String) payload.get("roleId"),
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema(),
                (String) payload.get("resourceKey"),
                (String) payload.get("action")
        );
        return ResponseEntity.ok(Map.of("message", "Permission revoked"));
    }

    @GetMapping("/users/{userId}/roles")
    public ResponseEntity<?> getUserRoles(@PathVariable String userId) {
        return ResponseEntity.ok(userMgmtService.getUserRoles(userId, userContextService.getCurrentTenantSchema()));
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<?> removeRole(@PathVariable String userId, @PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Remove from relational DB
        userMgmtService.removeRoleFromUser(userId, roleId, schema);
        // 2. Tell Casbin to remove the mapping
        casbinService.removeRoleFromUser(userId, roleId, tenantId, schema);

        return ResponseEntity.ok(Map.of("message", "Role removed from user"));
    }
}