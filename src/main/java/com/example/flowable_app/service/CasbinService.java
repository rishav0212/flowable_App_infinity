package com.example.flowable_app.service;

import com.example.flowable_app.config.CasbinConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.casbin.jcasbin.main.Enforcer;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CasbinService {

    private final CasbinConfig casbinConfig;

    // 1. Core Security Check (Used by Aspects & explicit permission checks)
    public boolean canDo(String userId, String tenantId, String schemaName, String resource, String action) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        boolean allowed = enforcer.enforce(userId, tenantId, resource, action);
        log.debug("🛡️ PERM CHECK: user={} res={} act={} -> {}", userId, resource, action, allowed);
        return allowed;
    }

    // 2. Build the Flat Map for ToolJet & React UI Visibility
    public Map<String, Boolean> getPermissionMap(String userId, String tenantId, String schemaName, List<String> allResourceKeys) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String key : allResourceKeys) {
            map.put(key, enforcer.enforce(userId, tenantId, key, "view"));
        }
        return map;
    }

    // 3. Admin Tools: Assign or Remove Roles from Users
    public void assignRoleToUser(String userId, String roleId, String tenantId, String schemaName) {
        casbinConfig.getEnforcer(schemaName).addRoleForUserInDomain(userId, roleId, tenantId);
    }

    public void removeRoleFromUser(String userId, String roleId, String tenantId, String schemaName) {
        casbinConfig.getEnforcer(schemaName).deleteRoleForUserInDomain(userId, roleId, tenantId);
    }

    // 4. Admin Tools: Grant or Revoke specific Permissions to Roles
    public void grantPermissionToRole(String roleId, String tenantId, String schemaName, String resource, String action) {
        casbinConfig.getEnforcer(schemaName).addPolicy(roleId, tenantId, resource, action);
    }

    public void revokePermissionFromRole(String roleId, String tenantId, String schemaName, String resource, String action) {
        casbinConfig.getEnforcer(schemaName).removePolicy(roleId, tenantId, resource, action);
    }

    // 5. Admin UI: Fetch existing policies for the Checkbox Matrix
    public List<List<String>> getPoliciesForRole(String roleId, String tenantId, String schemaName) {
        // getFilteredPolicy(0, ...) searches starting at index 0 (v0/Subject) for roleId, and index 1 for tenantId
        return casbinConfig.getEnforcer(schemaName).getFilteredPolicy(0, roleId, tenantId);
    }
}