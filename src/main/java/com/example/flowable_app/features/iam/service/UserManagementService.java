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
 * Interacts directly with tenant-specific database schemas (`tbl_users`, `tbl_roles`, etc.).
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
    public void createUser(String userId, String email, String firstName, String lastName, Map<String, Object> metadata, String schema, String adminUserId) {
        boolean exists = dsl.fetchExists(
                dsl.selectFrom(table(name(schema, "tbl_users")))
                        .where(field("user_id").eq(userId).or(field("email").eq(email)))
        );

        if (exists) {
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

        log.info("✅ User {} created in schema {}", email, schema);
    }

    /**
     * Deactivates a user account, preventing login but preserving history.
     */
    public void deactivateUser(String targetUserId, String schema, String adminUserId) {
        dsl.update(table(name(schema, "tbl_users")))
                .set(field("is_active"), false)
                .set(field("updated_ts"), currentTimestamp())
                .set(field("updated_by"), adminUserId)
                .where(field("user_id").eq(targetUserId))
                .execute();
    }

    /**
     * Retrieves all users within a tenant, including their assigned roles and parsed metadata.
     */
    public List<Map<String, Object>> getAllUsers(String schema) {
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
     */
    @Transactional
    public void updateUser(String userId, String email, String firstName, String lastName, Boolean isActive, String schema) {
        Map<org.jooq.Field<?>, Object> updates = new java.util.HashMap<>();

        if (email != null) updates.put(field("email"), email);
        if (firstName != null) updates.put(field("first_name"), firstName);
        if (lastName != null) updates.put(field("last_name"), lastName);
        if (isActive != null) updates.put(field("is_active"), isActive);

        if (!updates.isEmpty()) {
            dsl.update(table(name(schema, "tbl_users")))
                    .set(updates)
                    .where(field("user_id").eq(userId))
                    .execute();
        }
    }

    /**
     * Hard deletes a user and cascades the deletion to their role assignments.
     */
    @Transactional
    public void deleteUser(String userId, String schema) {
        dsl.deleteFrom(table(name(schema, "tbl_user_roles"))).where(field("user_id").eq(userId)).execute();
        dsl.deleteFrom(table(name(schema, "tbl_users"))).where(field("user_id").eq(userId)).execute();
    }

    // =====================================================================================
    // ROLES & ASSIGNMENTS
    // =====================================================================================

    /**
     * Retrieves all available roles in the tenant schema.
     */
    public List<Map<String, Object>> getAllRoles(String schema) {
        return dsl.selectFrom(table(name(schema, "tbl_roles"))).fetch().intoMaps();
    }

    /**
     * Creates a new role definition.
     */
    public void createRole(String roleId, String roleName, String description, String schema, String adminUserId) {
        boolean
                exists =
                dsl.fetchExists(dsl.selectFrom(table(name(schema, "tbl_roles"))).where(field("role_id").eq(roleId)));
        if (exists) {
            throw new DuplicateResourceException("Role", roleId);
        }

        dsl.insertInto(table(name(schema, "tbl_roles")))
                .set(field("role_id"), roleId)
                .set(field("role_name"), roleName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .execute();
    }

    /**
     * Updates an existing role's display name or description.
     */
    @Transactional
    public void updateRole(String roleId, String roleName, String description, String schema) {
        Map<org.jooq.Field<?>, Object> updates = new java.util.HashMap<>();
        if (roleName != null) updates.put(field("role_name"), roleName);
        if (description != null) updates.put(field("description"), description);

        if (!updates.isEmpty()) {
            dsl.update(table(name(schema, "tbl_roles")))
                    .set(updates)
                    .where(field("role_id").eq(roleId))
                    .execute();
        }
    }

    /**
     * Deletes a role and removes it from any users currently holding it.
     */
    @Transactional
    public void deleteRole(String roleId, String schema) {
        dsl.deleteFrom(table(name(schema, "tbl_user_roles"))).where(field("role_id").eq(roleId)).execute();
        dsl.deleteFrom(table(name(schema, "tbl_roles"))).where(field("role_id").eq(roleId)).execute();
    }

    /**
     * Binds a user to a specific role.
     */
    public void assignRoleToUser(String targetUserId, String roleId, String schema, String adminUserId) {
        dsl.insertInto(table(name(schema, "tbl_user_roles")))
                .set(field("user_id"), targetUserId)
                .set(field("role_id"), roleId)
                .set(field("assigned_by"), adminUserId)
                .onConflictDoNothing()
                .execute();
    }

    /**
     * Lists all role IDs mapped to a specific user.
     */
    public List<String> getUserRoles(String userId, String schema) {
        return dsl.select(field("role_id", String.class))
                .from(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(userId))
                .fetchInto(String.class);
    }

    /**
     * Unbinds a role from a user.
     */
    public void removeRoleFromUser(String targetUserId, String roleId, String schema) {
        dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(targetUserId))
                .and(field("role_id").eq(roleId))
                .execute();
    }

    // =====================================================================================
    // RESOURCES & ACTIONS
    // =====================================================================================

    /**
     * Fetches all registered resources and attaches their specific allowable actions.
     */
    public List<Map<String, Object>> getAllResources(String schema) {
        List<Map<String, Object>> resources = dsl.selectFrom(table(name(schema, "tbl_resources"))).fetch().intoMaps();
        List<Map<String, Object>>
                actions =
                dsl.selectFrom(table(name(schema, "tbl_resource_actions"))).fetch().intoMaps();

        for (Map<String, Object> res : resources) {
            String rKey = (String) res.get("resource_key");
            List<Map<String, Object>> resActions = actions.stream()
                    .filter(a -> rKey.equals(a.get("resource_key")))
                    .toList();
            res.put("actions", resActions);
        }
        return resources;
    }

    public List<Map<String, Object>> getActionsForResource(String resourceKey, String schema) {
        return dsl.selectFrom(table(name(schema, "tbl_resource_actions")))
                .where(field("resource_key").eq(resourceKey))
                .fetch()
                .intoMaps();
    }

    @Transactional
    public void registerResource(String resourceKey, String resourceType, String displayName, String description, String schema, String adminUserId) {
        registerResource(resourceKey, resourceType, displayName, description, schema, adminUserId, null);
    }

    /**
     * Upserts a resource definition into the database, optionally seeding default actions.
     */
    @Transactional
    public void registerResource(String resourceKey, String resourceType, String displayName, String description, String schema, String adminUserId, List<SystemCasbinResourceConfig.ActionDef> actions) {
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
        }
    }

    /**
     * Adds a dynamic custom action capability to an existing resource.
     */
    public void addCustomActionToResource(String resourceKey, String actionName, String description, String schema, String adminUserId) {
        dsl.insertInto(table(name(schema, "tbl_resource_actions")))
                .set(field("resource_key"), resourceKey)
                .set(field("action_name"), actionName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .onConflict(field("resource_key"), field("action_name"))
                .doNothing()
                .execute();
    }

    @Transactional
    public void deleteResource(String resourceKey, String schema) {
        dsl.deleteFrom(table(name(schema, "tbl_resource_actions")))
                .where(field("resource_key").eq(resourceKey))
                .execute();
        dsl.deleteFrom(table(name(schema, "tbl_resources"))).where(field("resource_key").eq(resourceKey)).execute();
    }

    private String safeWriteJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse metadata", e);
            return "{}";
        }
    }
}