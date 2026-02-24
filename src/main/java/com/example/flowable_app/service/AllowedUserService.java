package com.example.flowable_app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import static org.jooq.impl.DSL.field;

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

        try {
            // We use DSL.unquotedName(schemaName, tableName) so jOOQ correctly routes the query
            // to "schema_name.tbl_user_email_mapping" instead of the default public schema.
            // Using lowercase table names with unquotedName prevents PostgreSQL strict case-sensitivity crashes.
            Record1<Object> result = dsl.select(field(DSL.name("USER_ID")))
                    .from(DSL.table(DSL.unquotedName(schemaName, "tbl_user_email_mapping")))
                    .where(field(DSL.name("EMAIL_ID")).eq(email))
                    .fetchOne();

            if (result == null) {
                log.warn("❌ Database Lookup: No mapping found for email: [{}] in schema: [{}]", email, schemaName);
                return null;
            }

            String mappedId = result.value1().toString();
            log.info("✅ Database Lookup: Found mapping. Email [{}] -> USER_ID [{}]", email, mappedId);
            return mappedId;

        } catch (Exception e) {
            log.error("🔥 Database Lookup: Error executing query for email: [{}] in schema: [{}]. Error: {}",
                    email,
                    schemaName,
                    e.getMessage(),
                    e);
            return null;
        }
    }
}