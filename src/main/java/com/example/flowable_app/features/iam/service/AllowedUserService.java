package com.example.flowable_app.features.iam.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

/**
 * Security boundary service used primarily during the OAuth2 authentication flow.
 * Safely determines if an authenticated email belongs to an active system user
 * before granting a JWT token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllowedUserService {

    private final DSLContext dsl;

    public String getUserIdByEmail(String email, String schemaName) {
        log.info("🔍 Database Lookup: Searching for USER_ID mapping for email: [{}] in schema: [{}]", email, schemaName);

        // =====================================================================================
        // STEP 1: Check the new main tbl_users table first.
        // =====================================================================================
        try {
            log.debug("Step 1: Attempting lookup in main tbl_users table...");
            Record1<Object> userRecord = dsl.select(field(DSL.name("user_id")))
                    .from(table(DSL.name(schemaName, "tbl_users")))
                    .where(field(DSL.name("email")).eq(email))
                    .and(field(DSL.name("is_active")).eq(true))
                    .fetchOne();

            if (userRecord != null) {
                String mappedId = userRecord.value1().toString();
                log.info("✅ Database Lookup: Found ACTIVE user in tbl_users. Email [{}] -> USER_ID [{}]",
                        email,
                        mappedId);
                return mappedId;
            } else {
                log.warn("⚠️ Step 1 Lookup: Email [{}] not found or is deactivated in tbl_users for schema [{}]",
                        email,
                        schemaName);
            }
        } catch (Exception e) {
            log.warn("🔄 Step 1 Lookup skipped/failed for schema [{}]. Table might not exist yet. Error: {}",
                    schemaName,
                    e.getMessage());
        }

        // =====================================================================================
        // STEP 2: Fallback to the legacy mapping table.
        // =====================================================================================
        try {
            log.debug("Step 2: Attempting fallback lookup in tbl_user_email_mapping...");
            Record1<Object> result = dsl.select(field(DSL.name("USER_ID")))
                    .from(table(DSL.unquotedName(schemaName, "tbl_user_email_mapping")))
                    .where(field(DSL.name("EMAIL_ID")).eq(email))
                    .fetchOne();

            if (result == null) {
                log.warn("❌ Database Lookup: No mapping found in fallback table for email: [{}] in schema: [{}]",
                        email,
                        schemaName);
                return null;
            }

            String mappedId = result.value1().toString();
            log.info("✅ Database Lookup: Found mapping in fallback table. Email [{}] -> USER_ID [{}]", email, mappedId);
            return mappedId;

        } catch (Exception e) {
            log.error("🔥 Database Lookup: Error executing fallback query for email: [{}] in schema: [{}]. Error: {}",
                    email, schemaName, e.getMessage(), e);
            return null;
        }
    }
}