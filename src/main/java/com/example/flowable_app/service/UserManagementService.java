package com.example.flowable_app.service;

import com.example.flowable_app.config.SystemCasbinResourceConfig;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class UserManagementService {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    // --- USERS ---

    @Transactional
    public void createUser(String userId, String email, String firstName, String lastName, Map<String, Object> metadata, String schema, String adminUserId) {

        // 🛡️ CHECK FOR DUPLICATES FIRST
        boolean exists = dsl.fetchExists(
                dsl.selectFrom(table(name(schema, "tbl_users")))
                        .where(field("user_id").eq(userId)
                                .or(field("email").eq(email)))
        );

        if (exists) {
            throw new IllegalArgumentException("A user with this ID or Email already exists in the system.");
        }

        try {
            String
                    metadataJson =
                    (metadata != null && !metadata.isEmpty()) ? objectMapper.writeValueAsString(metadata) : "{}";

            dsl.insertInto(table(name(schema, "tbl_users")))
                    .set(field("user_id"), userId)
                    .set(field("email"), email)
                    .set(field("first_name"), firstName)
                    .set(field("last_name"), lastName)
                    .set(field("metadata"), DSL.cast(DSL.val(metadataJson), SQLDataType.JSONB))
                    .set(field("created_by"), adminUserId)
                    .execute();

            log.info("✅ User {} created in schema {}", email, schema);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create user: " + e.getMessage());
        }
    }

    public void deactivateUser(String targetUserId, String schema, String adminUserId) {
        dsl.update(table(name(schema, "tbl_users")))
                .set(field("is_active"), false)
                .set(field("updated_ts"), currentTimestamp())
                .set(field("updated_by"), adminUserId)
                .where(field("user_id").eq(targetUserId))
                .execute();
    }

    public List<Map<String, Object>> getAllUsers(String schema) {
        /*
         * OPTIMIZATION: We are using jOOQ's 'multiset' feature to aggregate the user's roles
         * directly within the database query. This resolves the N+1 query problem by fetching
         * all users and their associated roles in a single database round-trip, rather than
         * executing a separate query for every single user in the application layer.
         * We alias the tables as 'u' and 'ur' to perform a clean internal JOIN via the multiset subquery.
         */
        return dsl.select(
                        field("u.*"),
                        multiset(
                                select(field("ur.role_id"))
                                        .from(table(name(schema, "tbl_user_roles")).as("ur"))
                                        .where(field("ur.user_id").eq(field("u.user_id")))
                        ).as("roles").convertFrom(r -> r.map(rec -> rec.get(0, String.class)))
                )
                .from(table(name(schema, "tbl_users")).as("u"))
                .orderBy(field("u.created_ts").desc())
                .fetch()
                .map(record -> {
                    Map<String, Object> map = record.intoMap();
                    Object metadata = map.get("metadata");

                    // 🛡️ THE FIX: Unwrap jOOQ's JSONB and parse it back to a standard Map
                    if (metadata instanceof org.jooq.JSONB jsonb) {
                        try {
                            map.put("metadata", objectMapper.readValue(jsonb.data(), Map.class));
                        } catch (Exception e) {
                            map.put("metadata", Map.of()); // Fallback to empty map on parse error
                        }
                    }
                    return map;
                });
    }

    // --- ROLES & RESOURCES ---

    public void createRole(String roleId, String roleName, String description, String schema, String adminUserId) {
        dsl.insertInto(table(name(schema, "tbl_roles")))
                .set(field("role_id"), roleId)
                .set(field("role_name"), roleName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .execute();
    }

    /**
     * Overloaded method to support legacy calls without actions
     */
    @Transactional
    public void registerResource(String resourceKey, String resourceType, String displayName, String description, String schema, String adminUserId) {
        registerResource(resourceKey, resourceType, displayName, description, schema, adminUserId, null);
    }

    /**
     * Registers a resource and populates its supported actions in the relational table.
     * This establishes the blueprint of what actions are possible for this specific resource.
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
                // 🟢 CHANGED: Now acts as an Update if the resource already exists!
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
                        .doNothing() // Actions remain insert-only (no updating action names)
                        .execute();
            }
        }
    }

    /**
     * Allows tenants or admins to define completely custom actions for an existing resource dynamically.
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

    public List<Map<String, Object>> getAllRoles(String schema) {
        return dsl.selectFrom(table(name(schema, "tbl_roles")))
                .fetch()
                .intoMaps();
    }

    public List<Map<String, Object>> getAllResources(String schema) {
        // 1. Fetch all resources
        List<Map<String, Object>> resources = dsl.selectFrom(table(name(schema, "tbl_resources")))
                .fetch()
                .intoMaps();

        // 2. Fetch all actions
        List<Map<String, Object>> actions = dsl.selectFrom(table(name(schema, "tbl_resource_actions")))
                .fetch()
                .intoMaps();

        // 3. Attach actions to their respective resources
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

    // --- USER-ROLE ASSIGNMENT ---

    public void assignRoleToUser(String targetUserId, String roleId, String schema, String adminUserId) {
        dsl.insertInto(table(name(schema, "tbl_user_roles")))
                .set(field("user_id"), targetUserId)
                .set(field("role_id"), roleId)
                .set(field("assigned_by"), adminUserId)
                .onConflictDoNothing()
                .execute();
    }

    // Fetch a simple list of role IDs assigned to a user
    public List<String> getUserRoles(String userId, String schema) {
        return dsl.select(field("role_id", String.class))
                .from(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(userId))
                .fetchInto(String.class);
    }

    // Remove the assignment from the relational database
    public void removeRoleFromUser(String targetUserId, String roleId, String schema) {
        dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(targetUserId))
                .and(field("role_id").eq(roleId))
                .execute();
    }

    // --- DELETIONS ---

    @Transactional
    public void deleteUser(String userId, String schema) {
        // Remove role mappings first
        dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("user_id").eq(userId))
                .execute();
        // Delete user
        dsl.deleteFrom(table(name(schema, "tbl_users")))
                .where(field("user_id").eq(userId))
                .execute();
    }

    @Transactional
    public void deleteRole(String roleId, String schema) {
        // Remove role mappings
        dsl.deleteFrom(table(name(schema, "tbl_user_roles")))
                .where(field("role_id").eq(roleId))
                .execute();
        // Delete role
        dsl.deleteFrom(table(name(schema, "tbl_roles")))
                .where(field("role_id").eq(roleId))
                .execute();
    }

    @Transactional
    public void deleteResource(String resourceKey, String schema) {
        // Remove actions
        dsl.deleteFrom(table(name(schema, "tbl_resource_actions")))
                .where(field("resource_key").eq(resourceKey))
                .execute();
        // Delete resource
        dsl.deleteFrom(table(name(schema, "tbl_resources")))
                .where(field("resource_key").eq(resourceKey))
                .execute();
    }



    // =====================================================================================
    // UPDATE METHODS
    // =====================================================================================

    @Transactional
    public void updateUser(String userId, Map<String, Object> payload, String schema) {
        Map<org.jooq.Field<?>, Object> updates = new java.util.HashMap<>();

        if (payload.containsKey("email")) {
            updates.put(field("email"), payload.get("email"));
        }
        if (payload.containsKey("firstName")) {
            updates.put(field("first_name"), payload.get("firstName"));
        }
        if (payload.containsKey("lastName")) {
            updates.put(field("last_name"), payload.get("lastName"));
        }
        if (payload.containsKey("isActive")) {
            updates.put(field("is_active"), payload.get("isActive"));
        }

        if (!updates.isEmpty()) {
            dsl.update(table(name(schema, "tbl_users")))
                    .set(updates)
                    .where(field("user_id").eq(userId))
                    .execute();
        }
    }

    @Transactional
    public void updateRole(String roleId, Map<String, Object> payload, String schema) {
        Map<org.jooq.Field<?>, Object> updates = new java.util.HashMap<>();

        if (payload.containsKey("roleName")) {
            updates.put(field("role_name"), payload.get("roleName"));
        }
        if (payload.containsKey("description")) {
            updates.put(field("description"), payload.get("description"));
        }

        if (!updates.isEmpty()) {
            dsl.update(table(name(schema, "tbl_roles")))
                    .set(updates)
                    .where(field("role_id").eq(roleId))
                    .execute();
        }
    }
}