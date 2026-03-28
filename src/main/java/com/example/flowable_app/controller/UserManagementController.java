package com.example.flowable_app.controller;

import com.example.flowable_app.config.SystemCasbinResourceConfig;
import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.UserContextService;
import com.example.flowable_app.service.UserManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
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

    @PutMapping("/users/{userId}/deactivate")
    public ResponseEntity<?> deactivateUser(@PathVariable String userId) {
        userMgmtService.deactivateUser(userId,
                userContextService.getCurrentTenantSchema(),
                userContextService.getCurrentUserId());
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

        userMgmtService.assignRoleToUser(userId, roleId, schema, userContextService.getCurrentUserId());
        casbinService.assignRoleToUser(userId, roleId, tenantId, schema);

        return ResponseEntity.ok(Map.of("message", "Role assigned to user"));
    }

    @GetMapping("/users/{userId}/roles")
    public ResponseEntity<?> getUserRoles(@PathVariable String userId) {
        return ResponseEntity.ok(userMgmtService.getUserRoles(userId, userContextService.getCurrentTenantSchema()));
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    public ResponseEntity<?> removeRole(@PathVariable String userId, @PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        userMgmtService.removeRoleFromUser(userId, roleId, schema);
        casbinService.removeRoleFromUser(userId, roleId, tenantId, schema);

        return ResponseEntity.ok(Map.of("message", "Role removed from user"));
    }

    // === ROLE INHERITANCE (LEAN CASBIN) ===

    @GetMapping("/roles/{roleId}/inherits")
    public ResponseEntity<?> getInheritedRoles(@PathVariable String roleId) {
        return ResponseEntity.ok(casbinService.getInheritedRoles(roleId,
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema()));
    }

    @PostMapping("/roles/{roleId}/inherits/{inheritsRoleId}")
    public ResponseEntity<?> addRoleInheritance(@PathVariable String roleId, @PathVariable String inheritsRoleId) {
        casbinService.addRoleInheritance(roleId, inheritsRoleId,
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema());
        return ResponseEntity.ok(Map.of("message", "Role inheritance added successfully"));
    }

    @DeleteMapping("/roles/{roleId}/inherits/{inheritsRoleId}")
    public ResponseEntity<?> removeRoleInheritance(@PathVariable String roleId, @PathVariable String inheritsRoleId) {
        casbinService.removeRoleInheritance(roleId, inheritsRoleId,
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema());
        return ResponseEntity.ok(Map.of("message", "Role inheritance removed successfully"));
    }

    // === PERMISSION MATRIX ENGINE ===

    @GetMapping("/roles/{roleId}/permissions")
    public ResponseEntity<?> getRolePermissions(@PathVariable String roleId) {
        return ResponseEntity.ok(casbinService.getPoliciesForRole(roleId,
                userContextService.getCurrentTenantId(),
                userContextService.getCurrentTenantSchema()));
    }

    @GetMapping("/resources")
    public ResponseEntity<?> listResources() {
        return ResponseEntity.ok(userMgmtService.getAllResources(userContextService.getCurrentTenantSchema()));
    }

    @PostMapping("/resources")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> createResource(@RequestBody Map<String, Object> payload) {
        String resourceKey = (String) payload.get("resourceKey");
        String resourceType = (String) payload.get("resourceType");
        String displayName = (String) payload.get("displayName");
        String description = (String) payload.get("description");

        List<Map<String, String>> actionsPayload = (List<Map<String, String>>) payload.get("actions");
        List<SystemCasbinResourceConfig.ActionDef> actions = new ArrayList<>();

        if (actionsPayload != null) {
            for (Map<String, String> actMap : actionsPayload) {
                SystemCasbinResourceConfig.ActionDef actionDef = new SystemCasbinResourceConfig.ActionDef();
                actionDef.setName(actMap.get("name"));
                actionDef.setDescription(actMap.get("description"));
                actions.add(actionDef);
            }
        }

        userMgmtService.registerResource(resourceKey, resourceType, displayName, description,
                userContextService.getCurrentTenantSchema(),
                userContextService.getCurrentUserId(),
                actions);

        return ResponseEntity.ok(Map.of("message", "Resource registered successfully"));
    }

    @PostMapping("/resources/{resourceKey}/actions")
    public ResponseEntity<?> addCustomActionToResource(
            @PathVariable String resourceKey, @RequestBody Map<String, String> payload) {
        String actionName = payload.get("actionName");
        String description = payload.get("description");

        userMgmtService.addCustomActionToResource(resourceKey, actionName, description,
                userContextService.getCurrentTenantSchema(),
                userContextService.getCurrentUserId());

        return ResponseEntity.ok(Map.of("message", "Action added successfully"));
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
}