package com.example.flowable_app.features.iam.service;

import com.example.flowable_app.core.exception.DuplicateResourceException;
import com.example.flowable_app.core.security.config.SystemCasbinResourceConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;

/**
 * Core service for managing Identity and Access components via jOOQ.
 *
 * <p>Interacts directly with tenant-specific database schemas ({@code tbl_users},
 * {@code tbl_roles}, {@code tbl_user_roles}, {@code tbl_resources},
 * {@code tbl_resource_actions}).</p>
 *
 * <p><strong>Transaction Note:</strong> All write methods are annotated with
 * {@code @Transactional}. Callers must ensure that corresponding Casbin mutations
 * ({@code CasbinService}) are performed <em>after</em> the transaction commits, or are
 * wrapped in try-catch compensation logic to avoid split-brain between this DB state
 * and the Casbin in-memory/casbin_rule state.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserManagementService {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    // =====================================================================================
    // USERS
    // =====================================================================================

    /**
     * Creates a new user record within the specified tenant schema.
     *
     * @param userId      The unique ID for the user.
     * @param email       The user's email address.
     * @param firstName   The user's first name.
     * @param lastName    The user's last name.
     * @param metadata    Optional JSON metadata (e.g., phone, department).
     * @param schema      The tenant schema name.
     * @param adminUserId The ID of the user performing the creation (for auditing).
     * @throws DuplicateResourceException if a user with the ID or email already exists.
     */
    @Transactional
    public void createUser(String userId, String email, String firstName, String lastName,
                           Map<String, Object> metadata, String schema, String adminUserId) {
        log.info("👤 [USER CREATE] Attempting: userId={} email={} schema={} by={}",
                userId, email, schema, adminUserId);

        boolean exists = dsl.fetchExists(
                dsl.selectFrom(table(name(schema, "tbl_users")))
                        .where(field("user_id").eq(userId).or(field("email").eq(email)))
        );

        if (exists) {
            log.warn("⚠️ [USER CREATE] Duplicate detected — userId={} or email={} already exists in schema={}",
                    userId, email, schema);
            throw new DuplicateResourceException("User", userId + " or " + email);
        }

        String metadataJson = (metadata != null && !metadata.isEmpty()) ? safeWriteJson(metadata) : "{}";

        dsl.insertInto(table(name(schema, "tbl_users")))
                .set(field("user_id"), userId)
                .set(field("email"), email)
                .set(field("first_name"), firstName)
                .set(field("last_name"), lastName)
                .set(field("metadata"), DSL.cast(DSL.val(metadataJson), SQLDataType.JSONB))
                .set(field("created_by"), adminUserId)
                .execute();

        log.info("✅ [USER CREATE] Created userId={} email={} schema={}", userId, email, schema);
    }

    /**
     * Deactivates a user account, preventing login but preserving history.
     *
     * <p>This is a soft-delete: the user record remains in {@code tbl_users} with
     * {@code is_active = false}. The user's role assignments in {@code tbl_user_roles}
     * are NOT removed — reactivation restores full access automatically.</p>
     */
    @Transactional
    public void deactivateUser(String targetUserId, String schema, String adminUserId) {
        log.info("🔒 [USER DEACTIVATE] userId={} schema={} by={}", targetUserId, schema, adminUserId);

        int updated = dsl.update(table(name(schema, "tbl_users")))
                .set(field("is_active"), false)
                .set(field("updated_ts"), currentTimestamp())
                .set(field("updated_by"), adminUserId)
                .where(field("user_id").eq(targetUserId))
                .execute();

        if (updated == 0) {
            log.warn("⚠️ [USER DEACTIVATE] No rows updated — userId={} may not exist in schema={}",
                    targetUserId, schema);
        } else {
            log.info("✅ [USER DEACTIVATE] userId={} deactivated", targetUserId);
        }
    }

    /**
     * Retrieves all users within a tenant, including their assigned roles and parsed metadata.
     */
    public List<Map<String, Object>> getAllUsers(String schema) {
        log.debug("📋 [USER LIST] Fetching all users for schema={}", schema);

        return dsl.select(
                        field(name("u", "user_id")).as("user_id"),
                        field(name("u", "email")).as("email"),
                        field(name("u", "first_name")).as("first_name"),
                        field(name("u", "last_name")).as("last_name"),
                        field(name("u", "is_active")).as("is_active"),
                        field(name("u", "metadata")).as("metadata"),
                        field(name("u", "created_ts")).as("created_ts"),
                        multiset(
                                select(field(name("ur", "role_id")))
                                        .from(table(name(schema, "tbl_user_roles")).as("ur"))
                                        .where(field(name("ur", "user_id")).eq(field(name("u", "user_id"))))
                        ).as("roles").convertFrom(r -> r.map(rec -> rec.get(0, String.class)))
                )
                .from(table(name(schema, "tbl_users")).as("u"))
                .orderBy(field(name("u", "created_ts")).desc())
                .fetch()
                .map(record -> {
                    Map<String, Object> map = record.intoMap();
                    Object metadata = map.get("metadata");

                    if (metadata instanceof org.jooq.JSONB jsonb) {
                        try {
                            map.put("metadata", objectMapper.readValue(jsonb.data(), Map.class));
                        } catch (Exception e) {
                            log.warn("⚠️ [USER LIST] Failed to parse metadata JSONB for user in schema={}",
                                    schema, e);
                            map.put("metadata", Map.of());
                        }
                    } else if (metadata == null) {
                        map.put("metadata", Map.of());
                    }
                    return map;
                });
    }

    /**
     * Dynamically updates user fields. Only non-null parameters are updated.
     *
     * <p>This is used for both explicit field updates ({@code PUT /{userId}}) and for
     * the reactivate flow ({@code isActive = true} only).</p>
     */
    @Transactional
    public void updateUser(String userId, String email, String firstName, String lastName,
                           Boolean isActive, String schema) {
        Map<org.jooq.Field<?>, Object> updates = new java.util.HashMap<>();

        if (email != null) updates.put(field("email"), email);
        if (firstName != null) updates.put(field("first_name"), firstName);
        if (lastName != null) updates.put(field("last_name"), lastName);
        if (isActive != null) updates.put(field("is_active"), isActive);

        if (updates.isEmpty()) {
            log.warn("⚠️ [USER UPDATE] No fields to update for userId={} schema={}", userId, schema);
            return;
        }

        log.info("✏️ [USER UPDATE] userId={} schema={} updatingFields={}", userId, schema, updates.keySet());

        int updated = dsl.update(table(name(schema, "tbl_users")))
                .set(updates)
                .where(field("user_id").eq(userId))
                .execute();

        if (updated == 0) {
            log.warn("⚠️ [USER UPDATE] No rows updated — userId={} may not exist in schema={}",
                    userId, schema);
        } else {
            log.info("✅ [USER UPDATE] userId={} updated fields={}", userId, updates.keySet());
        }
    }

    /**
     * Hard deletes a user and cascades the deletion to their role assignments.
     *
     * <p><strong>Caller MUST also call {@code CasbinService.removeUserCompletely()} after
     * this transaction commits</strong> to remove orphaned g-rules from the Casbin engine.</p>
     */
    @Transactional
    public void deleteUser(String userId, String schema) {
        log.info("🗑️ [USER DELETE] Deleting userId={} from schema={}", userId, schema);

        // Cascade: remove role assignments first (FK constraint)
        int roleRowsDeleted = dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(userId))
                .execute();

        int userRowsDeleted = dsl.deleteFrom(table(name(schema, "tbl_users")))
                .where(field("user_id").eq(userId))
                .execute();

        log.info("✅ [USER DELETE] userId={} deleted | roleRowsDeleted={} userRowsDeleted={}",
                userId, roleRowsDeleted, userRowsDeleted);
    }

    /**
     * Checks whether a user with the given ID exists in the tenant schema.
     *
     * <p>Used by controllers to validate targets before performing role assignments
     * or other operations, preventing writes against non-existent subjects.</p>
     *
     * @param userId the user ID to check
     * @param schema the tenant schema
     * @return {@code true} if the user record exists
     */
    public boolean doesUserExist(String userId, String schema) {
        boolean exists = dsl.fetchExists(
                dsl.selectFrom(table(name(schema, "tbl_users")))
                        .where(field("user_id").eq(userId))
        );
        log.debug("🔎 [USER EXISTS] userId={} schema={} → {}", userId, schema, exists);
        return exists;
    }

    // =====================================================================================
    // ROLES & ASSIGNMENTS
    // =====================================================================================

    /**
     * Retrieves all available roles in the tenant schema.
     */
    public List<Map<String, Object>> getAllRoles(String schema) {
        log.debug("📋 [ROLE LIST] Fetching all roles for schema={}", schema);
        return dsl.selectFrom(table(name(schema, "tbl_roles"))).fetch().intoMaps();
    }

    /**
     * Returns a flat list of all role IDs in the tenant schema.
     *
     * <p>Used by the reconciliation flow in {@code UserController.getEffectiveAccess}
     * to filter Casbin-resolved inherited roles against DB-verified roles, eliminating
     * orphaned g-rules from the effective role set.</p>
     */
    public List<String> getAllRoleIds(String schema) {
        return dsl.select(field("role_id", String.class))
                .from(table(name(schema, "tbl_roles")))
                .fetchInto(String.class);
    }

    /**
     * Creates a new role definition.
     *
     * <p>This only writes to {@code tbl_roles}. Casbin is not modified here —
     * Casbin p-rules (permissions) are added separately via {@code PermissionController},
     * and g-rules (user assignments) are added via {@code UserController.assignRoleToUser}.</p>
     *
     * @throws DuplicateResourceException if a role with the same ID already exists.
     */
    @Transactional
    public void createRole(String roleId, String roleName, String description,
                           String schema, String adminUserId) {
        log.info("🎭 [ROLE CREATE] Attempting: roleId={} roleName={} schema={} by={}",
                roleId, roleName, schema, adminUserId);

        boolean exists = dsl.fetchExists(
                dsl.selectFrom(table(name(schema, "tbl_roles")))
                        .where(field("role_id").eq(roleId))
        );

        if (exists) {
            log.warn("⚠️ [ROLE CREATE] Duplicate roleId={} in schema={}", roleId, schema);
            throw new DuplicateResourceException("Role", roleId);
        }

        dsl.insertInto(table(name(schema, "tbl_roles")))
                .set(field("role_id"), roleId)
                .set(field("role_name"), roleName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .execute();

        log.info("✅ [ROLE CREATE] roleId={} created in schema={}", roleId, schema);
    }

    /**
     * Updates an existing role's display name or description.
     *
     * <p>The {@code role_id} is immutable — only cosmetic fields are updated.
     * Casbin policies (which reference role_id) are unaffected.</p>
     */
    @Transactional
    public void updateRole(String roleId, String roleName, String description, String schema) {
        Map<org.jooq.Field<?>, Object> updates = new java.util.HashMap<>();
        if (roleName != null) updates.put(field("role_name"), roleName);
        if (description != null) updates.put(field("description"), description);

        if (updates.isEmpty()) {
            log.warn("⚠️ [ROLE UPDATE] No fields to update for roleId={} schema={}", roleId, schema);
            return;
        }

        log.info("✏️ [ROLE UPDATE] roleId={} schema={} fields={}", roleId, schema, updates.keySet());

        int updated = dsl.update(table(name(schema, "tbl_roles")))
                .set(updates)
                .where(field("role_id").eq(roleId))
                .execute();

        if (updated == 0) {
            log.warn("⚠️ [ROLE UPDATE] No rows updated — roleId={} may not exist in schema={}",
                    roleId, schema);
        } else {
            log.info("✅ [ROLE UPDATE] roleId={} updated", roleId);
        }
    }

    /**
     * Deletes a role and removes it from any users currently holding it.
     *
     * <p><strong>Caller MUST also call {@code CasbinService.removeRoleCompletely()} after
     * this transaction commits</strong> to remove all p-rules and g-rules for this role
     * from the Casbin engine, preventing ghost permissions.</p>
     *
     * <p>Deletion order: {@code tbl_user_roles} first (FK constraint),
     * then {@code tbl_roles}.</p>
     */
    @Transactional
    public void deleteRole(String roleId, String schema) {
        log.info("🗑️ [ROLE DELETE] Deleting roleId={} from schema={}", roleId, schema);

        int userRoleRows = dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("role_id").eq(roleId))
                .execute();

        int roleRows = dsl.deleteFrom(table(name(schema, "tbl_roles")))
                .where(field("role_id").eq(roleId))
                .execute();

        log.info("✅ [ROLE DELETE] roleId={} deleted | userRoleRowsDeleted={} roleRowsDeleted={}",
                roleId, userRoleRows, roleRows);
    }

    /**
     * Checks whether a role with the given ID exists in the tenant schema.
     *
     * <p>Used by controllers to validate targets before:</p>
     * <ul>
     *   <li>Granting permissions to a role (prevents ghost-role grants in Casbin).</li>
     *   <li>Assigning a role to a user (prevents assignments referencing deleted roles).</li>
     *   <li>Adding role inheritance (prevents inheritance from non-existent roles).</li>
     * </ul>
     *
     * @param roleId the role ID to check
     * @param schema the tenant schema
     * @return {@code true} if the role exists in {@code tbl_roles}
     */
    public boolean doesRoleExist(String roleId, String schema) {
        boolean exists = dsl.fetchExists(
                dsl.selectFrom(table(name(schema, "tbl_roles")))
                        .where(field("role_id").eq(roleId))
        );
        log.debug("🔎 [ROLE EXISTS] roleId={} schema={} → {}", roleId, schema, exists);
        return exists;
    }

    /**
     * Binds a user to a specific role in {@code tbl_user_roles}.
     *
     * <p>Uses {@code ON CONFLICT DO NOTHING} for idempotent inserts — calling this twice
     * with the same user/role pair is safe.</p>
     *
     * <p><strong>Caller MUST also call {@code CasbinService.assignRoleToUser()} to
     * mirror this assignment in the Casbin engine.</strong></p>
     */
    @Transactional
    public void assignRoleToUser(String targetUserId, String roleId, String schema, String adminUserId) {
        log.info("🔗 [USER ROLE ASSIGN] userId={} role={} schema={} by={}",
                targetUserId, roleId, schema, adminUserId);

        dsl.insertInto(table(name(schema, "tbl_user_roles")))
                .set(field("user_id"), targetUserId)
                .set(field("role_id"), roleId)
                .set(field("assigned_by"), adminUserId)
                .onConflictDoNothing()
                .execute();

        log.info("✅ [USER ROLE ASSIGN] Done — userId={} role={}", targetUserId, roleId);
    }

    /**
     * Lists all role IDs mapped to a specific user from {@code tbl_user_roles}.
     *
     * <p>This is the <em>authoritative</em> source for a user's roles. It is preferred over
     * querying Casbin directly because Casbin g-rules can become stale if a prior sync
     * operation failed. All permission evaluations in this application derive their role
     * list from this method rather than from Casbin's in-memory g-table.</p>
     */
    public List<String> getUserRoles(String userId, String schema) {
        List<String> roles = dsl.select(field("role_id", String.class))
                .from(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(userId))
                .fetchInto(String.class);

        log.debug("📋 [USER ROLES] userId={} schema={} roles={}", userId, schema, roles);
        return roles;
    }

    /**
     * Unbinds a role from a user in {@code tbl_user_roles}.
     *
     * <p><strong>Caller MUST also call {@code CasbinService.removeRoleFromUser()} to
     * mirror this removal in the Casbin engine.</strong></p>
     */
    @Transactional
    public void removeRoleFromUser(String targetUserId, String roleId, String schema) {
        log.info("✂️ [USER ROLE REMOVE] userId={} role={} schema={}", targetUserId, roleId, schema);

        int deleted = dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(targetUserId))
                .and(field("role_id").eq(roleId))
                .execute();

        if (deleted == 0) {
            log.warn("⚠️ [USER ROLE REMOVE] No row found — userId={} role={} may not be assigned",
                    targetUserId, roleId);
        } else {
            log.info("✅ [USER ROLE REMOVE] Done — userId={} role={}", targetUserId, roleId);
        }
    }

    // =====================================================================================
    // RESOURCES & ACTIONS
    // =====================================================================================

    /**
     * Fetches all registered resources and attaches their specific allowable actions.
     */
    public List<Map<String, Object>> getAllResources(String schema) {
        log.debug("📋 [RESOURCE LIST] Fetching resources for schema={}", schema);

        List<Map<String, Object>> resources = dsl.selectFrom(table(name(schema, "tbl_resources")))
                .fetch().intoMaps();
        List<Map<String, Object>> actions = dsl.selectFrom(table(name(schema, "tbl_resource_actions")))
                .fetch().intoMaps();

        for (Map<String, Object> res : resources) {
            String rKey = (String) res.get("resource_key");
            List<Map<String, Object>> resActions = actions.stream()
                    .filter(a -> rKey.equals(a.get("resource_key")))
                    .toList();
            res.put("actions", resActions);
        }
        return resources;
    }

    /**
     * Returns all defined actions for a given resource key.
     *
     * <p>Used by {@code PermissionController} to validate that a requested action is a
     * registered action before writing a Casbin policy — preventing ghost permissions
     * that reference undefined actions.</p>
     */
    public List<Map<String, Object>> getActionsForResource(String resourceKey, String schema) {
        return dsl.selectFrom(table(name(schema, "tbl_resource_actions")))
                .where(field("resource_key").eq(resourceKey))
                .fetch()
                .intoMaps();
    }

    /**
     * Overload for {@link #registerResource(String, String, String, String, String, String, List)}
     * with no default actions.
     */
    @Transactional
    public void registerResource(String resourceKey, String resourceType, String displayName,
                                 String description, String schema, String adminUserId) {
        registerResource(resourceKey, resourceType, displayName, description, schema, adminUserId, null);
    }

    /**
     * Upserts a resource definition into the database, optionally seeding default actions.
     *
     * <p>Uses {@code ON CONFLICT DO UPDATE} on {@code resource_key} for idempotent upserts
     * (safe to call for system resources on startup).</p>
     *
     * <p>Each action is inserted with {@code ON CONFLICT DO NOTHING}, so pre-existing
     * action definitions are never overwritten.</p>
     */
    @Transactional
    public void registerResource(String resourceKey, String resourceType, String displayName,
                                 String description, String schema, String adminUserId,
                                 List<SystemCasbinResourceConfig.ActionDef> actions) {
        log.info("🗂️ [RESOURCE REGISTER] resourceKey={} schema={} by={}",
                resourceKey, schema, adminUserId);

        dsl.insertInto(table(name(schema, "tbl_resources")))
                .set(field("resource_key"), resourceKey)
                .set(field("resource_type"), resourceType)
                .set(field("display_name"), displayName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .onConflict(field("resource_key"))
                .doUpdate()
                .set(field("display_name"), displayName)
                .set(field("description"), description)
                .execute();

        if (actions != null && !actions.isEmpty()) {
            for (SystemCasbinResourceConfig.ActionDef action : actions) {
                dsl.insertInto(table(name(schema, "tbl_resource_actions")))
                        .set(field("resource_key"), resourceKey)
                        .set(field("action_name"), action.getName())
                        .set(field("description"), action.getDescription())
                        .set(field("created_by"), adminUserId)
                        .onConflict(field("resource_key"), field("action_name"))
                        .doNothing()
                        .execute();
            }
            log.info("✅ [RESOURCE REGISTER] resourceKey={} seeded with {} actions",
                    resourceKey, actions.size());
        } else {
            log.info("✅ [RESOURCE REGISTER] resourceKey={} registered (no default actions)", resourceKey);
        }
    }

    /**
     * Adds a dynamic custom action capability to an existing resource.
     *
     * <p>Uses {@code ON CONFLICT DO NOTHING} — idempotent.</p>
     */
    @Transactional
    public void addCustomActionToResource(String resourceKey, String actionName,
                                          String description, String schema, String adminUserId) {
        log.info("➕ [RESOURCE ACTION] Adding action={} to resourceKey={} schema={} by={}",
                actionName, resourceKey, schema, adminUserId);

        dsl.insertInto(table(name(schema, "tbl_resource_actions")))
                .set(field("resource_key"), resourceKey)
                .set(field("action_name"), actionName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .onConflict(field("resource_key"), field("action_name"))
                .doNothing()
                .execute();

        log.info("✅ [RESOURCE ACTION] action={} added to resource={}", actionName, resourceKey);
    }

    /**
     * Deletes a resource definition and all its associated action definitions.
     *
     * <p><strong>Caller MUST also call {@code CasbinService.removePoliciesByResource()} after
     * this transaction commits</strong> to remove all orphaned Casbin p-rules that reference
     * this resource — otherwise roles will appear to have permissions on a resource that
     * no longer exists.</p>
     *
     * <p>Deletion order: {@code tbl_resource_actions} first (FK constraint),
     * then {@code tbl_resources}.</p>
     */
    @Transactional
    public void deleteResource(String resourceKey, String schema) {
        log.info("🗑️ [RESOURCE DELETE] Deleting resourceKey={} from schema={}", resourceKey, schema);

        int actionRows = dsl.deleteFrom(table(name(schema, "tbl_resource_actions")))
                .where(field("resource_key").eq(resourceKey))
                .execute();

        int resourceRows = dsl.deleteFrom(table(name(schema, "tbl_resources")))
                .where(field("resource_key").eq(resourceKey))
                .execute();

        log.info("✅ [RESOURCE DELETE] resourceKey={} deleted | actionRowsDeleted={} resourceRowsDeleted={}",
                resourceKey, actionRows, resourceRows);
    }

    // =====================================================================================
    // HELPERS
    // =====================================================================================

    private String safeWriteJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("❌ [JSON] Failed to serialize metadata to JSON", e);
            return "{}";
        }
    }
}