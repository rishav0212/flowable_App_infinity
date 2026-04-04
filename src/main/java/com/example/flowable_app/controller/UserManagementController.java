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
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 🛡️ CYCLICAL DEPENDENCY GUARD
        if (casbinService.causesCycle(roleId, inheritsRoleId, tenantId, schema)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Circular dependency detected. This would create an infinite loop."));
        }

        casbinService.addRoleInheritance(roleId, inheritsRoleId, tenantId, schema);
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

    // === NEW DELETION APIS ===

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String userId) {
        userMgmtService.deleteUser(userId, userContextService.getCurrentTenantSchema());
        // (Optional: You could also wipe Casbin user groupings here)
        return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
    }

    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<?> deleteRole(@PathVariable String roleId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        userMgmtService.deleteRole(roleId, schema);
        casbinService.removeRoleCompletely(roleId, tenantId, schema);

        return ResponseEntity.ok(Map.of("message", "Role deleted successfully"));
    }

    @DeleteMapping("/resources/{resourceKey}")
    public ResponseEntity<?> deleteResource(@PathVariable String resourceKey) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        userMgmtService.deleteResource(resourceKey, schema);
        casbinService.removePoliciesByResource(resourceKey, tenantId, schema);

        return ResponseEntity.ok(Map.of("message", "Resource deleted successfully"));
    }

// === NEW UPDATE APIS ===

    @PutMapping("/users/{userId}")
    public ResponseEntity<?> updateUser(@PathVariable String userId, @RequestBody Map<String, Object> payload) {
        String schema = userContextService.getCurrentTenantSchema();
        // Assuming your userMgmtService has an updateUser method.
        // If not, create a simple jOOQ update query similar to your create query.
        userMgmtService.updateUser(userId, payload, schema);
        return ResponseEntity.ok(Map.of("message", "User updated successfully"));
    }

    @PutMapping("/roles/{roleId}")
    public ResponseEntity<?> updateRole(@PathVariable String roleId, @RequestBody Map<String, Object> payload) {
        String schema = userContextService.getCurrentTenantSchema();
        userMgmtService.updateRole(roleId, payload, schema);
        return ResponseEntity.ok(Map.of("message", "Role updated successfully"));
    }
// === NEW EFFECTIVE ACCESS API (Replaces frontend loops) ===

    @GetMapping("/users/{userId}/effective-access")
    public ResponseEntity<?> getEffectiveAccess(@PathVariable String userId) {
        String schema = userContextService.getCurrentTenantSchema();
        String tenantId = userContextService.getCurrentTenantId();

        // 1. Get exact direct roles from the relational DB (Absolute Source of Truth)
        List<String> directRoles = userMgmtService.getUserRoles(userId, schema);

        // 2. Resolve all inherited roles using Casbin's graph
        java.util.Set<String> effectiveRolesSet = new java.util.HashSet<>(directRoles);

        for (String role : directRoles) {
            // Casbin treats users and roles interchangeably in the graph.
            // Here we ask Casbin: "What roles does this specific role inherit?"
            List<String> inherited = casbinService.getImplicitRolesForUser(role, tenantId, schema);
            effectiveRolesSet.addAll(inherited);
        }

        List<String> effectiveRoles = new java.util.ArrayList<>(effectiveRolesSet);

        // 3. Fetch all policies (permissions) for these combined effective roles
        List<List<String>> policies = casbinService.getPoliciesForRoles(effectiveRoles, tenantId, schema);

        return ResponseEntity.ok(Map.of(
                "roles", directRoles,
                "effectiveRoles", effectiveRoles,
                "policies", policies
        ));
    }
}