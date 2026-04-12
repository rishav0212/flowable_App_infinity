package com.example.flowable_app.service;

import com.example.flowable_app.core.security.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service exposed to Flowable BPMN expressions as "${data}".
 * Handles safe, low-code database interactions using standard SQL terminology.
 */
@Service("data")
@RequiredArgsConstructor
@Slf4j
public class FlowableDataService {

    // Regex to find named parameters like :myParam
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile("(?<!:):([a-zA-Z0-9_]+)");
    // Flowable / engine internal tables — NEVER accessible from low-code DSL
    // (Consolidated to top for better visibility)
    private static final String[] SYSTEM_TABLE_PREFIXES = {
            "ACT_",
            "FLW_"
    };
    private final DSLContext dsl;
    private final UserContextService userContextService;

    // =========================================================================
    // 📖 READ OPERATIONS
    // =========================================================================

    public Object selectVal(String tableRef, String columnName, String conditionSql, Map<String, Object> params) {
        log.debug("🔍 Executing selectVal: Table=[{}], Column=[{}], Condition=[{}]", tableRef, columnName, conditionSql);

        log.debug("📊 selectVal Parameters: Table=[{}], Column=[{}], Condition=[{}], Params={}",
                tableRef, columnName, conditionSql, params);
        try {
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);


            // UPDATE: Replaced direct dsl.parser().parseField(columnName) with resolveColumnField(columnName).
            // WHY: The jOOQ parser strips exact casing for plain identifiers, causing PostgreSQL to look for
            // lowercase columns. resolveColumnField detects if it's a plain column and strictly quotes it
            // to preserve uppercase, while still letting complex functions (like COUNT) use the parser.
            Object result = dsl.select(resolveColumnField(columnName))
                    .from(resolveTable(tableRef))
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .fetchOne(0);

            log.info("✅ selectVal Result for [{}]: {}", columnName, result);
            return result;
        } catch (Exception e) {
            log.error("❌ selectVal FAILED on Table=[{}], Column=[{}], Condition=[{}]. Error: {}",
                    tableRef, columnName, conditionSql, e.getMessage(), e);
            return null;
        }
    }

    public Map<String, Object> selectOne(String tableRef, String conditionSql, Map<String, Object> params) {
        log.debug("🔍 Executing selectOne: Table=[{}], Condition=[{}]", tableRef, conditionSql);
        try {
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);
            Record record = dsl.selectFrom(resolveTable(tableRef))
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .fetchOne();

            Map<String, Object> result = (record != null) ? record.intoMap() : null;
            log.debug("✅ selectOne Result: Found={}", (result != null));
            return result;
        } catch (Exception e) {
            log.error("❌ selectOne FAILED on Table=[{}], Condition=[{}]. Error: {}",
                    tableRef, conditionSql, e.getMessage(), e);
            return null;
        }
    }

    public List<Object> selectAll(String tableRef, String columnName, String conditionSql, Map<String, Object> params) {
        log.debug("🔍 Executing selectAll: Table=[{}], Column=[{}], Condition=[{}]", tableRef, columnName, conditionSql);
        try {
            // UPDATE: Replaced dsl.parser().parseField(columnName) with resolveColumnField(columnName).
            // WHY: Same reason as selectVal. We must safely quote the requested column to protect uppercase names.
            Field<?> field = resolveColumnField(columnName);
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);

            List<Object> results = dsl.select(field)
                    .from(resolveTable(tableRef))
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .fetch((Field<Object>) field);

            log.debug("✅ selectAll Result: Fetched {} rows", results.size());
            return results;
        } catch (Exception e) {
            log.error("❌ selectAll FAILED on Table=[{}], Column=[{}], Condition=[{}]. Error: {}",
                    tableRef, columnName, conditionSql, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public boolean exists(String tableRef, String conditionSql, Map<String, Object> params) {
        log.debug("🔍 Executing exists: Table=[{}], Condition=[{}]", tableRef, conditionSql);
        try {
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);
            boolean exists = dsl.fetchExists(
                    dsl.selectOne()
                            .from(resolveTable(tableRef))
                            .where(DSL.condition(parsed.sql, parsed.bindings))
            );

            log.debug("✅ exists Result: {}", exists);
            return exists;
        } catch (Exception e) {
            log.error("❌ exists FAILED on Table=[{}], Condition=[{}]. Error: {}",
                    tableRef, conditionSql, e.getMessage(), e);
            return false;
        }
    }

    // =========================================================================
    // ✍️ WRITE OPERATIONS
    // =========================================================================

    public int update(String tableRef, String conditionSql, Map<String, Object> params, Object... keyValuePairs) {
        // [PRESERVED] Kept Version A's detailed logging.
        // This is critical for debugging workflows to see exactly what table/condition was hit.
        log.info("✍️ UPDATE Executing: Table=[{}], Condition=[{}]", tableRef, conditionSql);

        try {
            Map<Field<?>, Object> jooqUpdates = new HashMap<>();
            Map<String, Object> rawMap = toMap(keyValuePairs);

            // [PRESERVED] Debug log for payload
            if (log.isDebugEnabled()) {
                log.debug("UPDATE Payload Keys: {}", rawMap.keySet());
            }

            rawMap.forEach((k, v) -> jooqUpdates.put(DSL.field(DSL.name(k)), v));

            SqlAndBindings parsed = parseNamedParams(conditionSql, params);

            int result = dsl.update(resolveTable(tableRef))
                    .set(jooqUpdates)
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .execute();

            // [PRESERVED] Result count logging
            log.info("✅ UPDATE Complete: Modified {} rows in [{}]", result, tableRef);

            return result;
        } catch (Exception e) {
            log.error("❌ update FAILED on Table=[{}], Condition=[{}]. Error: {}",
                    tableRef, conditionSql, e.getMessage(), e);
            throw new RuntimeException("Database update failed: " + e.getMessage());
        }
    }

    public int insert(String tableRef, Object... keyValuePairs) {
        log.info("✍️ INSERT Executing: Table=[{}]", tableRef);
        try {
            Map<String, Object> data = toMap(keyValuePairs);

            // UPDATE: Converted the raw String-keyed map into a strictly quoted Field-keyed map.
            // WHY: If we pass the raw string keys directly to .set(data), jOOQ might not quote them,
            // causing PostgreSQL to search for lowercase columns and throw an error. DSL.name(k) forces quotes.
            Map<Field<?>, Object> jooqInserts = new HashMap<>();
            data.forEach((k, v) -> jooqInserts.put(DSL.field(DSL.name(k)), v));

            int result = dsl.insertInto(resolveTable(tableRef))
                    .set(jooqInserts)
                    .execute();

            log.info("✅ INSERT Complete: Added record to [{}]", tableRef);
            return result;
        } catch (Exception e) {
            log.error("❌ insert FAILED on Table=[{}]. Error: {}", tableRef, e.getMessage(), e);
            throw new RuntimeException("Database insert failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // 🧠 INTERNAL HELPERS
    // =========================================================================

    private Map<String, Object> toMap(Object... args) {
        Map<String, Object> map = new HashMap<>();
        if (args == null || args.length == 0) return map;

        if (args.length % 2 != 0) {
            log.error("❌ toMap FAILED: Invalid argument count. Expected even number of key-value pairs, got {}",
                    args.length);
            throw new FlowableIllegalArgumentException(
                    "Database operations require strict Key-Value pairs. Found odd number of arguments.");
        }

        for (int i = 0; i < args.length; i += 2) {
            map.put(String.valueOf(args[i]), args[i + 1]);
        }
        return map;
    }

    /**
     * UPDATE: New helper method to resolve columns.
     * WHY: Determines if a column string is a plain identifier (like "ORDER_NO_C") or a function ("COUNT(*)").
     * Plain identifiers get wrapped in DSL.name() to force double quotes, preserving uppercase in PostgreSQL.
     */
    private Field<?> resolveColumnField(String columnName) {
        if (columnName.contains("(") || columnName.contains(" ")) {
            return dsl.parser().parseField(columnName);
        }
        return DSL.field(DSL.name(columnName));
    }

    /**
     * Resolves the table reference by prepending the current tenant's schema.
     */
    private Table<?> resolveTable(String inputName) {
        if (inputName == null || inputName.trim().isEmpty()) {
            throw new FlowableIllegalArgumentException("Table name cannot be empty");
        }

        String tableName = inputName.trim();

        // 🚫 Block Flowable / system tables (Case-Insensitive Check)
        for (String prefix : SYSTEM_TABLE_PREFIXES) {
            if (tableName.toUpperCase().startsWith(prefix)) {
                log.error("❌ Security Alert: Attempt to access system table [{}] blocked.", inputName);
                throw new FlowableIllegalArgumentException(
                        "Access to system table '" + inputName + "' is forbidden"
                );
            }
        }

        // 🟢 Fetch the dynamic schema name from the authenticated user context
        String schemaName = userContextService.getCurrentTenantSchema();

        // If the user already provided a schema (e.g., "myschema.mytable"), we respect it,
        // otherwise, we prepend the tenant's schema.
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            if (parts.length == 2) {
                return DSL.table(DSL.unquotedName(parts[0], parts[1]));
            }
        }

        // 🟢 Always route to the tenant's schema using unquotedName to allow Postgres case-folding
        log.debug("🛣️ Routing query for table [{}] to schema [{}]", tableName, schemaName);
        return DSL.table(DSL.unquotedName(schemaName, tableName));
    }

    /**
     * UPDATE: New helper method to safely quote columns inside raw WHERE condition strings.
     * WHY: Raw strings like "ORDER_NO_C=:order" are passed directly to PostgreSQL, which drops the case.
     * This regex finds uppercase column names before an operator and wraps them in quotes: "\"ORDER_NO_C\"=:order".
     */
    private String autoQuoteConditionColumns(String conditionSql) {
        if (conditionSql == null || conditionSql.trim().isEmpty()) {
            return conditionSql;
        }

        // Matches uppercase words (with underscores/numbers) that are followed by standard SQL operators
        Pattern
                pattern =
                Pattern.compile("(?<![\"'])\\b([A-Z][A-Z0-9_]*)\\b(\\s*(=|!=|<>|<|>|<=|>=|LIKE\\b|IN\\b|IS\\b))");
        Matcher matcher = pattern.matcher(conditionSql);
        StringBuffer sb = new StringBuffer();

        boolean wasModified = false;
        while (matcher.find()) {
            String columnName = matcher.group(1);
            String operatorPart = matcher.group(2);

            // Avoid quoting standard SQL keywords like AND, OR
            if (columnName.equals("AND") || columnName.equals("OR") || columnName.equals("NOT")) {
                matcher.appendReplacement(sb, columnName + operatorPart);
            } else {
                matcher.appendReplacement(sb, "\"" + columnName + "\"" + operatorPart);
                wasModified = true;
            }
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        if (wasModified && log.isDebugEnabled()) {
            log.debug("🔧 Auto-Quoted Condition SQL: Original=[{}], Fixed=[{}]", conditionSql, result);
        }

        return result;
    }

    private SqlAndBindings parseNamedParams(String sql, Map<String, Object> params) {
        // UPDATE: Intercept the raw SQL string and auto-quote any uppercase columns before parsing parameters.
        // WHY: This is the critical step to ensure WHERE clauses don't break due to case-folding.
        String safeSql = autoQuoteConditionColumns(sql);

        if (params == null || params.isEmpty() || safeSql == null) {
            return new SqlAndBindings(safeSql, new Object[0]);
        }

        Matcher matcher = NAMED_PARAM_PATTERN.matcher(safeSql);
        List<Object> bindings = new ArrayList<>();
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String key = matcher.group(1);
            if (params.containsKey(key)) {
                matcher.appendReplacement(sb, "?");
                bindings.add(params.get(key));
            } else {
                matcher.appendReplacement(sb, "?");
                bindings.add(null);
                log.warn("⚠️ Parameter :{} found in SQL but missing from params map. Binding NULL.", key);
            }
        }
        matcher.appendTail(sb);
        return new SqlAndBindings(sb.toString(), bindings.toArray());
    }

    private static class SqlAndBindings {
        final String sql;
        final Object[] bindings;

        SqlAndBindings(String sql, Object[] bindings) {
            this.sql = sql;
            this.bindings = bindings;
        }
    }
}