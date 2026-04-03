package com.example.flowable_app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * Service to verify if an OAuth2 authenticated email exists in the tenant's allowed users mapping.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllowedUserService {

    private final DSLContext dsl;

    /**
     * Looks up the internal USER_ID for a given email within a specific tenant schema.
     * We pass schemaName explicitly because during the OAuth2 login phase, the JWT token
     * is not yet minted, so we cannot rely on the standard UserContextService here.
     *
     * @param email      The email address returned by Google OAuth2.
     * @param schemaName The physical PostgreSQL schema mapped to the current tenant.
     * @return The internal USER_ID if found, otherwise null.
     */
    public String getUserIdByEmail(String email, String schemaName) {
        log.info("🔍 Database Lookup: Searching for USER_ID mapping for email: [{}] in schema: [{}]", email, schemaName);

        // =====================================================================================
        // STEP 1: Check the new main tbl_users table first.
        // We enforce the 'is_active' check here so deactivated users cannot log in.
        // This block is wrapped in a try-catch to safely handle scenarios where a tenant's
        // schema hasn't been migrated yet and the tbl_users table might not exist.
        // =====================================================================================
        try {
            log.debug("Step 1: Attempting lookup in main tbl_users table...");
            Record1<Object> userRecord = dsl.select(field(DSL.name("user_id")))
                    .from(table(DSL.name(schemaName, "tbl_users")))
                    .where(field(DSL.name("email")).eq(email))
                    // Ensure we only authorize active users
                    .and(field(DSL.name("is_active")).eq(true))
                    .fetchOne();

            if (userRecord != null) {
                String mappedId = userRecord.value1().toString();
                log.info("✅ Database Lookup: Found ACTIVE user in tbl_users. Email [{}] -> USER_ID [{}]", email, mappedId);
                return mappedId;
            } else {
                log.warn("⚠️ Step 1 Lookup: Email [{}] not found or is deactivated in tbl_users for schema [{}]", email, schemaName);
            }
        } catch (Exception e) {
            // We catch and log this instead of throwing so we can proceed to the fallback table.
            // This prevents login failures during active database migrations.
            log.warn("🔄 Step 1 Lookup skipped/failed for schema [{}]. Table might not exist yet. Error: {}", schemaName, e.getMessage());
        }

        // =====================================================================================
        // STEP 2: Fallback to the legacy mapping table.
        // If the user wasn't found above (or the table didn't exist), we check the older structure.
        // =====================================================================================
        try {
            log.debug("Step 2: Attempting fallback lookup in tbl_user_email_mapping...");

            // We use DSL.unquotedName(schemaName, tableName) so jOOQ correctly routes the query
            // to "schema_name.tbl_user_email_mapping" instead of the default public schema.
            // Using lowercase table names with unquotedName prevents PostgreSQL strict case-sensitivity crashes.
            Record1<Object> result = dsl.select(field(DSL.name("USER_ID")))
                    .from(table(DSL.unquotedName(schemaName, "tbl_user_email_mapping")))
                    .where(field(DSL.name("EMAIL_ID")).eq(email))
                    .fetchOne();

            if (result == null) {
                log.warn("❌ Database Lookup: No mapping found in fallback table for email: [{}] in schema: [{}]", email, schemaName);
                return null;
            }

            String mappedId = result.value1().toString();
            log.info("✅ Database Lookup: Found mapping in fallback table. Email [{}] -> USER_ID [{}]", email, mappedId);
            return mappedId;

        } catch (Exception e) {
            log.error("🔥 Database Lookup: Error executing fallback query for email: [{}] in schema: [{}]. Error: {}",
                    email,
                    schemaName,
                    e.getMessage(),
                    e);
            return null;
        }
    }
}