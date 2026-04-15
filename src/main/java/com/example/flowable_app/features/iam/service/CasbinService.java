package com.example.flowable_app.features.iam.service;

import com.example.flowable_app.core.security.config.CasbinConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.casbin.jcasbin.main.Enforcer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CasbinService — the single gateway between the application and the Casbin RBAC engine.
 *
 * <p><strong>Architecture Note — Dual-Write Consistency</strong></p>
 * <p>
 * This system maintains IAM state in two places that MUST stay in sync:
 * <ol>
 *   <li><strong>Relational DB</strong> (tbl_roles, tbl_user_roles) — the authoritative source
 *       of truth for "what roles exist" and "which users have which roles".</li>
 *   <li><strong>Casbin Engine / casbin_rule table</strong> — the source of truth for
 *       "what permissions a role has" (p-rules) and "which user/role inherits what role"
 *       (g-rules). This is evaluated in-memory for performance.</li>
 * </ol>
 *
 * <p><strong>Transaction Boundary Warning:</strong></p>
 * <p>
 * The Casbin JDBCAdapter opens its own JDBC connection and is NOT part of the surrounding
 * Spring {@code @Transactional} boundary. This means:
 * <ul>
 *   <li>If a DB (jOOQ) operation commits but a subsequent Casbin call fails, the two stores
 *       will diverge (split-brain). This is mitigated by calling Casbin <em>after</em> the DB
 *       operation succeeds, and by always reloading Casbin policy from DB on enforcer
 *       initialization (via {@code loadPolicy()}).</li>
 *   <li>If the Casbin operation succeeds but the subsequent DB transaction rolls back, a
 *       compensating Casbin call should be made. The individual controllers handle this.</li>
 *   <li>For full reconciliation, call {@link #reconcileUserRoles} or
 *       {@link #reloadPolicy} to re-derive Casbin state from the DB.</li>
 * </ul>
 *
 * <p><strong>Mismatch Root Causes (and fixes applied)</strong></p>
 * <ul>
 *   <li>{@code getEffectiveAccess} was using {@code getFilteredPolicy} (raw stored rules only),
 *       while {@code getMyPermissions} uses {@code enforce()} which follows role-inheritance
 *       chains. Fixed by exposing {@link #getImplicitPermissionsForUser} and
 *       {@link #checkEffectivePermission}.</li>
 *   <li>Casbin g-rules could become orphaned when a role is deleted from the DB but Casbin
 *       removal fails. Fixed by adding {@link #reconcileUserRoles} and {@link #reloadPolicy}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CasbinService {

    private final CasbinConfig casbinConfig;

    // =====================================================================================
    // 1. CORE SECURITY CHECK  (Used by Aspects & explicit permission checks)
    // =====================================================================================

    /**
     * Evaluates whether a subject (userId or roleId) is allowed to perform {@code action}
     * on {@code resource} within the given tenant domain.
     *
     * <p>The Casbin enforcer resolves role-inheritance chains transitively, so calling this
     * with a raw userId is sufficient — it will follow g-rules to pick up all inherited roles.</p>
     *
     * @param subject   userId or roleId being checked
     * @param tenantId  tenant domain used to scope the policy evaluation
     * @param schemaName  database schema owning the casbin_rule table for this tenant
     * @param resource  resource key (e.g., {@code "module:users"})
     * @param action    action name (e.g., {@code "read"})
     * @return {@code true} if allowed, {@code false} otherwise
     */
    public boolean canDo(String subject, String tenantId, String schemaName,
                         String resource, String action) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        boolean allowed = enforcer.enforce(subject, tenantId, resource, action);

        log.debug("🛡️ [CASBIN CHECK] subject={} tenant={} resource={} action={} → {}",
                subject, tenantId, resource, action, allowed ? "ALLOW" : "DENY");
        return allowed;
    }

    /**
     * Convenience alias for {@link #canDo} — checks whether {@code roleId} has a specific
     * permission. Used by {@code getMyPermissions} to iterate DB-verified roles and
     * evaluate each resource/action pair through the Casbin engine.
     *
     * <p>Unlike {@link #getPoliciesForRole}, this method follows role-inheritance chains
     * (g-rules) so it returns the <em>effective</em> permission, not just a stored rule.</p>
     */
    public boolean checkEffectivePermission(String roleId, String tenantId, String schemaName,
                                            String resource, String action) {
        return canDo(roleId, tenantId, schemaName, resource, action);
    }

    // =====================================================================================
    // 2. FLAT PERMISSION MAP  (Used by ToolJet & React UI Visibility)
    // =====================================================================================

    /**
     * Builds a flat {@code resource:action → boolean} map for all provided resource keys
     * by evaluating the Casbin {@code view} action for each. Intended for UI-level show/hide
     * decisions based on the {@code view} permission only.
     *
     * <p>For a full effective permission map (all actions, all inherited roles), prefer
     * calling {@link #checkEffectivePermission} per action from the controller layer.</p>
     *
     * @param userId           the user being evaluated
     * @param tenantId         tenant domain
     * @param schemaName       tenant schema
     * @param allResourceKeys  list of resource keys to evaluate
     * @return map of {@code "resource_key" → allowed}
     */
    public Map<String, Boolean> getPermissionMap(String userId, String tenantId,
                                                 String schemaName,
                                                 List<String> allResourceKeys) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        Map<String, Boolean> map = new java.util.LinkedHashMap<>();

        log.debug("📋 [PERMISSION MAP] Building map for user={} tenant={} resourceCount={}",
                userId, tenantId, allResourceKeys.size());

        for (String key : allResourceKeys) {
            boolean allowed = enforcer.enforce(userId, tenantId, key, "view");
            map.put(key, allowed);
        }

        long granted = map.values().stream().filter(v -> v).count();
        log.debug("📋 [PERMISSION MAP] Done — user={} granted={}/{}", userId, granted, map.size());
        return map;
    }

    // =====================================================================================
    // 3. EFFECTIVE PERMISSIONS  (Follows role-inheritance chains — fixes effective-access mismatch)
    // =====================================================================================

    /**
     * Returns all permissions effectively held by a user, following role-inheritance chains.
     *
     * <p><strong>This is the correct method to call from {@code getEffectiveAccess}.</strong>
     * Unlike {@link #getPoliciesForRoles} (which only reads raw stored p-rules), this method
     * delegates to Casbin's {@code getImplicitPermissionsForUser} which resolves ALL inherited
     * roles and returns their combined policies — matching exactly what {@code enforce()} evaluates.
     * This eliminates the mismatch between {@code /effective-access} and {@code /my-permissions}.</p>
     *
     * <p>Example: if {@code userId} has role {@code testing_role}, and {@code testing_role}
     * inherits {@code default_role} which has policy {@code module:access_control:read}, then
     * that policy is included in the returned list — even though there is no direct p-rule
     * for {@code testing_role} on that resource.</p>
     *
     * @param userId     the user whose effective permissions are being resolved
     * @param tenantId   tenant domain
     * @param schemaName tenant schema
     * @return list of policy tuples {@code [sub, dom, obj, act]} visible to the enforcer
     */
    public List<List<String>> getImplicitPermissionsForUser(String userId, String tenantId,
                                                            String schemaName) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);
        List<List<String>> perms = enforcer.getImplicitPermissionsForUser(userId, tenantId);

        log.debug("🔍 [IMPLICIT PERMS] user={} tenant={} resolvedPolicyCount={}",
                userId, tenantId, perms == null ? 0 : perms.size());
        return perms == null ? new ArrayList<>() : perms;
    }

    /**
     * Returns all roles that a user or role transitively inherits, following g-rule chains.
     *
     * <p>This differs from {@link #getInheritedRoles}: that method returns only direct
     * parents of a role, while this method walks the full inheritance graph recursively.</p>
     *
     * @param userId     the subject whose implicit roles are being resolved
     * @param tenantId   tenant domain
     * @param schemaName tenant schema
     * @return flattened list of all reachable roles (excluding the subject itself)
     */
    public List<String> getImplicitRolesForUser(String userId, String tenantId, String schemaName) {
        List<String> roles = casbinConfig.getEnforcer(schemaName)
                .getImplicitRolesForUser(userId, tenantId);

        log.debug("🔗 [IMPLICIT ROLES] subject={} tenant={} implicitRoles={}",
                userId, tenantId, roles);
        return roles == null ? new ArrayList<>() : roles;
    }

    // =====================================================================================
    // 4. ROLE ASSIGNMENT  (User ↔ Role bindings)
    // =====================================================================================

    /**
     * Adds a g-rule that binds {@code userId} to {@code roleId} in the given domain.
     *
     * <p>This is idempotent — Casbin will not insert a duplicate g-rule if the binding
     * already exists.</p>
     *
     * <p><strong>Caller must have already committed the corresponding {@code tbl_user_roles}
     * row in the DB before calling this method.</strong> See {@code UserController} for the
     * correct dual-write order.</p>
     */
    public void assignRoleToUser(String userId, String roleId, String tenantId, String schemaName) {
        log.info("➕ [CASBIN ASSIGN] Binding user={} → role={} in tenant={} schema={}",
                userId, roleId, tenantId, schemaName);
        casbinConfig.getEnforcer(schemaName).addRoleForUserInDomain(userId, roleId, tenantId);
        log.info("✅ [CASBIN ASSIGN] Done — user={} role={} tenant={}", userId, roleId, tenantId);
    }

    /**
     * Removes the g-rule binding {@code userId} to {@code roleId} in the given domain.
     *
     * <p>This is idempotent — if the binding does not exist, no exception is thrown.</p>
     *
     * <p><strong>Caller must have already removed the corresponding {@code tbl_user_roles}
     * row in the DB before calling this method.</strong></p>
     */
    public void removeRoleFromUser(String userId, String roleId, String tenantId, String schemaName) {
        log.info("➖ [CASBIN REMOVE ROLE] Removing user={} ← role={} in tenant={} schema={}",
                userId, roleId, tenantId, schemaName);
        casbinConfig.getEnforcer(schemaName).deleteRoleForUserInDomain(userId, roleId, tenantId);
        log.info("✅ [CASBIN REMOVE ROLE] Done — user={} role={} tenant={}", userId, roleId, tenantId);
    }

    // =====================================================================================
    // 5. PERMISSION GRANT / REVOKE  (Policy rules for Roles)
    // =====================================================================================

    /**
     * Writes a new p-rule granting {@code roleId} permission to perform {@code action}
     * on {@code resource} within the given tenant domain.
     *
     * <p>This is idempotent — if the exact rule already exists, Casbin will not insert a
     * duplicate. A debug log is emitted when the rule already exists.</p>
     *
     * <p>The resource and action must already exist in {@code tbl_resource_actions} before
     * this is called; validation is the caller's responsibility (see {@code PermissionController}).</p>
     */
    public void grantPermissionToRole(String roleId, String tenantId, String schemaName,
                                      String resource, String action) {
        // Pre-check: detect and log idempotent calls (no-ops) for debugging
        boolean alreadyExists = hasPolicyExact(roleId, tenantId, schemaName, resource, action);
        if (alreadyExists) {
            log.warn("⚠️ [CASBIN GRANT] Policy already exists — idempotent call: " +
                            "role={} tenant={} resource={} action={}",
                    roleId, tenantId, resource, action);
        } else {
            log.info("➕ [CASBIN GRANT] Granting: role={} tenant={} resource={} action={}",
                    roleId, tenantId, resource, action);
        }

        casbinConfig.getEnforcer(schemaName).addPolicy(roleId, tenantId, resource, action);

        log.info("✅ [CASBIN GRANT] Done — role={} resource={} action={}", roleId, resource, action);
    }

    /**
     * Deletes a p-rule that previously granted {@code roleId} the given permission.
     *
     * <p>This is idempotent — if the rule does not exist, no exception is thrown, but a
     * warning is logged to help diagnose sync issues.</p>
     */
    public void revokePermissionFromRole(String roleId, String tenantId, String schemaName,
                                         String resource, String action) {
        boolean exists = hasPolicyExact(roleId, tenantId, schemaName, resource, action);
        if (!exists) {
            // WARNING: Policy not found in Casbin. This may indicate a prior sync failure
            // where the DB was updated but Casbin was not, or vice versa. The operation
            // will still succeed (no-op), but operators should investigate.
            log.warn("⚠️ [CASBIN REVOKE] Policy NOT FOUND in Casbin — possible sync drift: " +
                            "role={} tenant={} resource={} action={}",
                    roleId, tenantId, resource, action);
        } else {
            log.info("➖ [CASBIN REVOKE] Revoking: role={} tenant={} resource={} action={}",
                    roleId, tenantId, resource, action);
        }

        casbinConfig.getEnforcer(schemaName).removePolicy(roleId, tenantId, resource, action);

        log.info("✅ [CASBIN REVOKE] Done — role={} resource={} action={}", roleId, resource, action);
    }

    // =====================================================================================
    // 6. POLICY QUERIES  (Raw stored rules — for admin UI matrix views)
    // =====================================================================================

    /**
     * Returns all p-rules directly assigned to a specific role in the given tenant.
     *
     * <p><strong>Important:</strong> This returns only <em>directly stored</em> p-rules for the
     * role. It does NOT follow role-inheritance chains. For effective permissions (including
     * inherited), use {@link #getImplicitPermissionsForUser} instead.</p>
     *
     * <p>Used by the admin Permissions Matrix "Group by Role" view.</p>
     */
    public List<List<String>> getPoliciesForRole(String roleId, String tenantId, String schemaName) {
        List<List<String>> policies = casbinConfig.getEnforcer(schemaName)
                .getFilteredPolicy(0, roleId, tenantId);

        log.debug("📄 [ROLE POLICIES] role={} tenant={} directPolicyCount={}",
                roleId, tenantId, policies.size());
        return policies;
    }

    /**
     * Returns all p-rules across ALL roles that grant any permission on the given resource.
     *
     * <p>Policy filter: {@code field[1]=tenantId AND field[2]=resourceKey}
     * (policy tuple format: {@code [role, tenant, resource, action]}).</p>
     *
     * <p>Used by the admin Permissions Matrix "Group by Resource" view.</p>
     */
    public List<List<String>> getPoliciesForResource(String tenantId, String schemaName,
                                                     String resourceKey) {
        // field[1] = tenantId, field[2] = resourceKey in the [role, tenant, resource, action] tuple
        List<List<String>> policies = casbinConfig.getEnforcer(schemaName)
                .getFilteredPolicy(1, tenantId, resourceKey);

        log.debug("📄 [RESOURCE POLICIES] resource={} tenant={} policyCount={}",
                resourceKey, tenantId, policies.size());
        return policies;
    }

    /**
     * Returns combined p-rules for a list of roles (direct rules only, no inheritance expansion).
     *
     * <p><strong>NOTE:</strong> This is intentionally a raw rule reader and should only be used
     * for admin display purposes (e.g., showing what's explicitly stored per role in the matrix).
     * For computing effective access, prefer {@link #getImplicitPermissionsForUser}.</p>
     */
    public List<List<String>> getPoliciesForRoles(List<String> roles, String tenantId,
                                                  String schemaName) {
        List<List<String>> allPolicies = new ArrayList<>();
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);

        for (String role : roles) {
            List<List<String>> rolePolicies = enforcer.getFilteredPolicy(0, role, tenantId);
            allPolicies.addAll(rolePolicies);
            log.debug("📄 [ROLES POLICIES] role={} directPolicies={}", role, rolePolicies.size());
        }

        log.debug("📄 [ROLES POLICIES] Total combined policies={} for {} roles",
                allPolicies.size(), roles.size());
        return allPolicies;
    }

    /**
     * Checks whether a specific p-rule (exact match) already exists in Casbin.
     *
     * <p>Used to detect idempotent grant calls and diagnose sync drift in revoke calls.</p>
     *
     * @return {@code true} if the exact policy tuple exists in the enforcer's in-memory model
     */
    public boolean hasPolicyExact(String roleId, String tenantId, String schemaName,
                                  String resource, String action) {
        List<List<String>> existing = casbinConfig.getEnforcer(schemaName)
                .getFilteredPolicy(0, roleId, tenantId, resource, action);
        return existing != null && !existing.isEmpty();
    }

    // =====================================================================================
    // 7. ROLE INHERITANCE  (Lean Casbin approach — stores g-rules for role ↔ role)
    // =====================================================================================

    /**
     * Adds a g-rule making {@code roleId} inherit all permissions of {@code inheritsRoleId}.
     *
     * <p>Cyclic inheritance detection MUST be performed by the caller (see
     * {@link #causesCycle}) before calling this method.</p>
     */
    public void addRoleInheritance(String roleId, String inheritsRoleId,
                                   String tenantId, String schemaName) {
        log.info("🔗 [CASBIN INHERIT] Adding: role={} inherits={} tenant={}",
                roleId, inheritsRoleId, tenantId);
        casbinConfig.getEnforcer(schemaName)
                .addRoleForUserInDomain(roleId, inheritsRoleId, tenantId);
        log.info("✅ [CASBIN INHERIT] Done — role={} now inherits={}", roleId, inheritsRoleId);
    }

    /**
     * Removes the g-rule inheritance link between two roles.
     */
    public void removeRoleInheritance(String roleId, String inheritsRoleId,
                                      String tenantId, String schemaName) {
        log.info("✂️ [CASBIN INHERIT] Removing: role={} stops inheriting={} tenant={}",
                roleId, inheritsRoleId, tenantId);
        casbinConfig.getEnforcer(schemaName)
                .deleteRoleForUserInDomain(roleId, inheritsRoleId, tenantId);
        log.info("✅ [CASBIN INHERIT] Done — role={} no longer inherits={}", roleId, inheritsRoleId);
    }

    /**
     * Returns the list of roles that {@code roleId} DIRECTLY inherits (single hop, no recursion).
     *
     * <p>For the full transitive inheritance graph, use {@link #getImplicitRolesForUser}.</p>
     */
    public List<String> getInheritedRoles(String roleId, String tenantId, String schemaName) {
        List<String> inherited = casbinConfig.getEnforcer(schemaName)
                .getRolesForUserInDomain(roleId, tenantId);
        log.debug("🔗 [ROLE INHERITS] role={} tenant={} directParents={}", roleId, tenantId, inherited);
        return inherited == null ? new ArrayList<>() : inherited;
    }

    // =====================================================================================
    // 8. CYCLE GUARD
    // =====================================================================================

    /**
     * Detects whether adding the inheritance {@code roleId → inheritsRoleId} would create
     * a circular dependency in the role graph.
     *
     * <p>A cycle occurs when {@code roleId} is already reachable from {@code inheritsRoleId}'s
     * implicit role graph — meaning {@code inheritsRoleId} already (transitively) inherits
     * from {@code roleId}.</p>
     *
     * @return {@code true} if the proposed inheritance would create a cycle
     */
    public boolean causesCycle(String roleId, String inheritsRoleId,
                               String tenantId, String schemaName) {
        // Self-inheritance is trivially a cycle
        if (roleId.equals(inheritsRoleId)) {
            log.warn("⚠️ [CYCLE GUARD] Self-inheritance detected: role={}", roleId);
            return true;
        }

        // Check if inheritsRoleId already transitively reaches roleId (which would make the
        // proposed link create a cycle: roleId → inheritsRoleId → ... → roleId)
        List<String> implicitRolesOfTarget = casbinConfig.getEnforcer(schemaName)
                .getImplicitRolesForUser(inheritsRoleId, tenantId);
        boolean cycleDetected = implicitRolesOfTarget != null && implicitRolesOfTarget.contains(roleId);

        if (cycleDetected) {
            log.warn("⚠️ [CYCLE GUARD] Circular inheritance detected: {} → {} would cycle back to {}",
                    roleId, inheritsRoleId, roleId);
        }
        return cycleDetected;
    }

    // =====================================================================================
    // 9. DELETION CLEANUP  (Must be called alongside DB deletes to keep stores in sync)
    // =====================================================================================

    /**
     * Removes ALL p-rules referencing a specific resource key within the given tenant.
     *
     * <p>This must be called alongside {@code UserManagementService.deleteResource()} to prevent
     * orphaned Casbin policies that grant roles permissions on a resource that no longer exists.</p>
     *
     * <p>Policy tuple filter: {@code field[1]=tenantId AND field[2]=resourceKey}
     * (tuple format: {@code [role, tenant, resource, action]}).</p>
     */
    public void removePoliciesByResource(String resourceKey, String tenantId, String schemaName) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);

        // Capture the count BEFORE removal for logging
        List<List<String>> toRemove = enforcer.getFilteredPolicy(1, tenantId, resourceKey);
        log.info("🗑️ [CASBIN CLEANUP] Removing {} p-rules for resource={} tenant={}",
                toRemove.size(), resourceKey, tenantId);

        // field[1] = tenantId, field[2] = resourceKey
        enforcer.removeFilteredPolicy(1, tenantId, resourceKey);

        log.info("✅ [CASBIN CLEANUP] Removed {} policies for resource={}", toRemove.size(), resourceKey);
    }

    /**
     * Removes ALL Casbin state for a role: its p-rules (permissions) and all g-rules
     * (both user→role assignments and role→role inheritance links).
     *
     * <p>This must be called alongside {@code UserManagementService.deleteRole()} to prevent
     * orphaned policies remaining in the casbin_rule table for a deleted role.</p>
     *
     * <p>Three operations are performed:</p>
     * <ol>
     *   <li>Remove all p-rules where {@code sub == roleId AND dom == tenantId}
     *       (the role's own permission grants).</li>
     *   <li>Remove all g-rules where {@code field[0] == roleId}
     *       (inheritance links where this role IS the child, i.e., roles it inherits from).</li>
     *   <li>Remove all g-rules where {@code field[1] == roleId}
     *       (user-role assignment links where this role IS the parent, i.e., users assigned this role).</li>
     * </ol>
     */
    public void removeRoleCompletely(String roleId, String tenantId, String schemaName) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);

        // Snapshot state before deletion for logging
        List<List<String>> pRules = enforcer.getFilteredPolicy(0, roleId, tenantId);
        List<String> inheritedRoles = enforcer.getRolesForUserInDomain(roleId, tenantId);
        List<String> usersWithRole  = enforcer.getUsersForRoleInDomain(roleId, tenantId);

        log.info("🗑️ [CASBIN DELETE ROLE] Removing role={} tenant={} | " +
                        "pRuleCount={} inheritedRoles={} usersWithRole={}",
                roleId, tenantId, pRules.size(), inheritedRoles, usersWithRole);

        // Step 1: Remove all p-rules granted TO this role
        enforcer.removeFilteredPolicy(0, roleId, tenantId);

        // Step 2: Remove g-rules where this role IS the child (inherits from another role)
        enforcer.removeFilteredGroupingPolicy(0, roleId);

        // Step 3: Remove g-rules where this role IS the parent (users assigned to it, or
        //         child roles that inherit from it). Field index 1 in the g-rule is the role.
        enforcer.removeFilteredGroupingPolicy(1, roleId);

        log.info("✅ [CASBIN DELETE ROLE] Done — role={} fully removed from Casbin", roleId);
    }

    /**
     * Removes all Casbin g-rules where the given user is the subject (field[0]).
     *
     * <p>This effectively strips all role assignments from the user in Casbin's in-memory
     * model and the casbin_rule table. It does NOT touch p-rules (which are role-scoped,
     * not user-scoped).</p>
     *
     * <p>Must be called alongside {@code UserManagementService.deleteUser()} to prevent
     * orphaned g-rules for a deleted user.</p>
     */
    public void removeUserCompletely(String userId, String schemaName) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);

        // Snapshot current assignments for logging
        List<String> userRoles = enforcer.getRolesForUser(userId);
        log.info("🗑️ [CASBIN DELETE USER] Removing all g-rules for user={} schema={} | currentRoles={}",
                userId, schemaName, userRoles);

        // fieldIndex 0 matches the subject (userId) in the 'g' policy: g, sub, role, dom
        enforcer.removeFilteredGroupingPolicy(0, userId);

        log.info("✅ [CASBIN DELETE USER] Done — user={} stripped of {} role bindings",
                userId, userRoles == null ? 0 : userRoles.size());
    }

    // =====================================================================================
    // 10. RECONCILIATION & CACHE MANAGEMENT
    // =====================================================================================

    /**
     * Forces a full reload of the Casbin enforcer for the given schema by evicting it from
     * the in-memory cache. The next access will re-create the enforcer by calling
     * {@code loadPolicy()} against the live {@code casbin_rule} table.
     *
     * <p>Use this when:</p>
     * <ul>
     *   <li>A direct SQL migration has modified {@code casbin_rule} outside the application.</li>
     *   <li>A prior split-brain failure was detected and manually corrected in the DB.</li>
     *   <li>The in-memory enforcer state is suspected to be stale.</li>
     * </ul>
     *
     * @param schemaName tenant schema whose enforcer cache entry should be evicted
     */
    public void reloadPolicy(String schemaName) {
        log.info("🔄 [CASBIN RELOAD] Invalidating enforcer cache for schema={}", schemaName);
        casbinConfig.invalidateCache(schemaName);
        log.info("✅ [CASBIN RELOAD] Cache cleared for schema={}. " +
                "Next access will reload from casbin_rule table.", schemaName);
    }

    /**
     * Reconciles the Casbin g-rules for a specific user against the authoritative DB role list.
     *
     * <p><strong>When to call this:</strong> After detecting or recovering from a split-brain
     * scenario where tbl_user_roles (DB) and casbin_rule (Casbin) have diverged for a user.
     * Also useful as a routine health-check operation.</p>
     *
     * <p><strong>Algorithm:</strong></p>
     * <ol>
     *   <li>Read the current g-rules for the user from the in-memory Casbin enforcer.</li>
     *   <li>Compare them against {@code dbRoles} (the ground truth from {@code tbl_user_roles}).</li>
     *   <li>Remove any Casbin g-rules that are NOT in the DB list (orphaned rules).</li>
     *   <li>Add any Casbin g-rules that ARE in the DB list but missing from Casbin (lost rules).</li>
     * </ol>
     *
     * @param userId     the user to reconcile
     * @param tenantId   tenant domain
     * @param dbRoles    authoritative list of role IDs from {@code tbl_user_roles}
     * @param schemaName tenant schema
     * @return a summary map with keys {@code "removed"} and {@code "added"} containing the
     *         list of role IDs that were corrected
     */
    public Map<String, List<String>> reconcileUserRoles(String userId, String tenantId,
                                                        List<String> dbRoles,
                                                        String schemaName) {
        Enforcer enforcer = casbinConfig.getEnforcer(schemaName);

        // Get current Casbin g-rule roles for this user in this domain
        List<String> casbinRoles = enforcer.getRolesForUserInDomain(userId, tenantId);
        if (casbinRoles == null) casbinRoles = new ArrayList<>();

        Set<String> dbRoleSet     = new LinkedHashSet<>(dbRoles);
        Set<String> casbinRoleSet = new LinkedHashSet<>(casbinRoles);

        // Orphaned = in Casbin but NOT in DB
        Set<String> orphaned = new LinkedHashSet<>(casbinRoleSet);
        orphaned.removeAll(dbRoleSet);

        // Missing = in DB but NOT in Casbin
        Set<String> missing = new LinkedHashSet<>(dbRoleSet);
        missing.removeAll(casbinRoleSet);

        log.info("🔧 [RECONCILE USER] user={} tenant={} schema={} | " +
                        "dbRoles={} casbinRoles={} orphaned={} missing={}",
                userId, tenantId, schemaName, dbRoles, casbinRoles, orphaned, missing);

        // Remove orphaned g-rules
        for (String orphanedRole : orphaned) {
            log.warn("🔧 [RECONCILE USER] Removing orphaned Casbin g-rule: user={} role={}",
                    userId, orphanedRole);
            enforcer.deleteRoleForUserInDomain(userId, orphanedRole, tenantId);
        }

        // Add missing g-rules
        for (String missingRole : missing) {
            log.warn("🔧 [RECONCILE USER] Adding missing Casbin g-rule: user={} role={}",
                    userId, missingRole);
            enforcer.addRoleForUserInDomain(userId, missingRole, tenantId);
        }

        if (orphaned.isEmpty() && missing.isEmpty()) {
            log.info("✅ [RECONCILE USER] user={} is in sync — no changes needed", userId);
        } else {
            log.info("✅ [RECONCILE USER] user={} reconciled — removed={} added={}",
                    userId, orphaned.size(), missing.size());
        }

        return Map.of(
                "removed", new ArrayList<>(orphaned),
                "added",   new ArrayList<>(missing)
        );
    }
}