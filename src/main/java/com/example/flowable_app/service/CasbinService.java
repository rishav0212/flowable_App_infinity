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

    // 1. Core Security Check
    public boolean canDo(String userId, String tenantId, String schemaName, String resource, String action) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        boolean allowed = enforcer.enforce(userId, tenantId, resource, action);
        log.debug("🛡️ PERM CHECK: user={} res={} act={} -> {}", userId, resource, action, allowed);
        return allowed;
    }

    // 2. Build the Flat Map for ToolJet UI Visibility
    public Map<String, Boolean> getPermissionMap(String userId, String tenantId, String schemaName, List<String> allResourceKeys) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String key : allResourceKeys) {
            map.put(key, enforcer.enforce(userId, tenantId, key, "view"));
        }
        return map;
    }

    // 3. Admin Tools (Assign Roles & Policies)
    public void assignRoleToUser(String userId, String roleId, String tenantId, String schemaName) {
        casbinConfig.getEnforcer(schemaName).addRoleForUserInDomain(userId, roleId, tenantId);
    }

    public void removeRoleFromUser(String userId, String roleId, String tenantId, String schemaName) {
        casbinConfig.getEnforcer(schemaName).deleteRoleForUserInDomain(userId, roleId, tenantId);
    }

    public void grantPermissionToRole(String roleId, String tenantId, String schemaName, String resource, String action) {
        casbinConfig.getEnforcer(schemaName).addPolicy(roleId, tenantId, resource, action);
    }

    public void revokePermissionFromRole(String roleId, String tenantId, String schemaName, String resource, String action) {
        casbinConfig.getEnforcer(schemaName).removePolicy(roleId, tenantId, resource, action);
    }


    // 4. Fetch existing policies for the Matrix UI
    public List<List<String>> getPoliciesForRole(String roleId, String tenantId, String schemaName) {
        // getFilteredPolicy(0, ...) means: "Search starting at index 0 (v0/Subject) for roleId, and index 1 (v1/Domain) for tenantId"
        return casbinConfig.getEnforcer(schemaName).getFilteredPolicy(0, roleId, tenantId);
    }
}