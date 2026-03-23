package com.example.flowable_app.service;

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
        try {
            String metadataJson = (metadata != null && !metadata.isEmpty()) ? objectMapper.writeValueAsString(metadata) : "{}";

            dsl.insertInto(table(name(schema, "tbl_users")))
                    .set(field("user_id"), userId)
                    .set(field("email"), email)
                    .set(field("first_name"), firstName)
                    .set(field("last_name"), lastName)
                    // Safely cast the JSON string to PostgreSQL JSONB
                    .set(field("metadata"), cast(val(metadataJson), SQLDataType.JSONB))
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
        return dsl.selectFrom(table(name(schema, "tbl_users")))
                .orderBy(field("created_ts").desc())
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

    public void registerResource(String resourceKey, String resourceType, String displayName, String description, String schema, String adminUserId) {
        dsl.insertInto(table(name(schema, "tbl_resources")))
                .set(field("resource_key"), resourceKey)
                .set(field("resource_type"), resourceType)
                .set(field("display_name"), displayName)
                .set(field("description"), description)
                .set(field("created_by"), adminUserId)
                .onConflict(field("resource_key"))
                .doNothing() // Ignore if the resource is already registered
                .execute();
    }

    public List<Map<String, Object>> getAllRoles(String schema) {
        return dsl.selectFrom(table(name(schema, "tbl_roles")))
                .fetch()
                .intoMaps();
    }

    public List<Map<String, Object>> getAllResources(String schema) {
        return dsl.selectFrom(table(name(schema, "tbl_resources")))
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
}