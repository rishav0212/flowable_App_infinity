package com.example.flowable_app.service;

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
    private static final String[] SYSTEM_TABLE_PREFIXES = {
            "ACT_",
            "FLW_"
    };

    // =========================================================================
    // 📖 READ OPERATIONS
    // =========================================================================
    private final DSLContext dsl;

    public Object selectVal(String tableRef, String columnName, String conditionSql, Map<String, Object> params) {
        try {
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);
            // FIX: Use dsl.parser().parseField to support functions like COUNT(*) or MAX()
            return dsl.select(dsl.parser().parseField(columnName))
                    .from(resolveTable(tableRef))
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .fetchOne(0);
        } catch (Exception e) {
            log.error("selectVal error on table [{}]: {}", tableRef, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> selectOne(String tableRef, String conditionSql, Map<String, Object> params) {
        try {
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);
            Record record = dsl.selectFrom(resolveTable(tableRef))
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .fetchOne();
            return (record != null) ? record.intoMap() : null;
        } catch (Exception e) {
            log.error("selectOne error on table [{}]: {}", tableRef, e.getMessage());
            return null;
        }
    }

    public List<Object> selectAll(String tableRef, String columnName, String conditionSql, Map<String, Object> params) {
        try {
            // FIX: Use dsl.parser().parseField to support complex column expressions
            Field<?> field = dsl.parser().parseField(columnName);
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);

            return dsl.select(field)
                    .from(resolveTable(tableRef))
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .fetch((Field<Object>) field);
        } catch (Exception e) {
            log.error("selectAll error on table [{}]: {}", tableRef, e.getMessage());
            return new ArrayList<>();
        }
    }

    // =========================================================================
    // ✍️ WRITE OPERATIONS
    // =========================================================================

    public boolean exists(String tableRef, String conditionSql, Map<String, Object> params) {
        try {
            SqlAndBindings parsed = parseNamedParams(conditionSql, params);
            return dsl.fetchExists(
                    dsl.selectOne()
                            .from(resolveTable(tableRef))
                            .where(DSL.condition(parsed.sql, parsed.bindings))
            );
        } catch (Exception e) {
            log.error("exists error on table [{}]: {}", tableRef, e.getMessage());
            return false;
        }
    }

    public int update(String tableRef, String conditionSql, Map<String, Object> params, Object... keyValuePairs) {
        try {
            Map<Field<?>, Object> jooqUpdates = new HashMap<>();
            Map<String, Object> rawMap = toMap(keyValuePairs);

            rawMap.forEach((k, v) -> jooqUpdates.put(DSL.field(DSL.name(k)), v));

            SqlAndBindings parsed = parseNamedParams(conditionSql, params);

            return dsl.update(resolveTable(tableRef))
                    .set(jooqUpdates)
                    .where(DSL.condition(parsed.sql, parsed.bindings))
                    .execute();
        } catch (Exception e) {
            log.error("update error on table [{}]: {}", tableRef, e.getMessage());
            throw new RuntimeException("Database update failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // 🧠 INTERNAL HELPERS
    // =========================================================================

    public int insert(String tableRef, Object... keyValuePairs) {
        try {
            Map<String, Object> data = toMap(keyValuePairs);

            return dsl.insertInto(resolveTable(tableRef))
                    .set(data)
                    .execute();
        } catch (Exception e) {
            log.error("insert error on table [{}]: {}", tableRef, e.getMessage());
            throw new RuntimeException("Database insert failed: " + e.getMessage());
        }
    }

    private Map<String, Object> toMap(Object... args) {
        Map<String, Object> map = new HashMap<>();
        if (args == null || args.length == 0) return map;

        if (args.length % 2 != 0) {
            throw new FlowableIllegalArgumentException(
                    "Database operations require strict Key-Value pairs. Found odd number of arguments.");
        }

        for (int i = 0; i < args.length; i += 2) {
            map.put(String.valueOf(args[i]), args[i + 1]);
        }
        return map;
    }

    private Table<?> resolveTable(String inputName) {
        if (inputName == null || inputName.trim().isEmpty()) {
            throw new FlowableIllegalArgumentException("Table name cannot be empty");
        }

        // "myTable" is not the same as "MYTABLE" in Postgres if quoted.
        String normalized = inputName.trim();

        // Update the check to be case-insensitive safe
        for (String prefix : SYSTEM_TABLE_PREFIXES) {
            if (normalized.toUpperCase().startsWith(prefix)) {
                throw new FlowableIllegalArgumentException(
                        "Access to system table '" + inputName + "' is forbidden"
                );
            }
        }


        // Handle schema-qualified tables (schema.table)
        if (inputName.contains(".")) {
            String[] parts = inputName.split("\\.");
            if (parts.length == 2) {
                return DSL.table(DSL.name(parts[0], parts[1]));
            }
        }

        return DSL.table(DSL.name(inputName));
    }

    private SqlAndBindings parseNamedParams(String sql, Map<String, Object> params) {
        if (params == null || params.isEmpty() || sql == null) {
            return new SqlAndBindings(sql, new Object[0]);
        }

        Matcher matcher = NAMED_PARAM_PATTERN.matcher(sql);
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
                log.warn("Parameter :{} found in SQL but missing from params map. Binding NULL.", key);
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
