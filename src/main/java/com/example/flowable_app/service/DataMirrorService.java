package com.example.flowable_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Record;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataMirrorService {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    /**
     * 🟢 BATCH SAVE: Updates multiple tables in ONE transaction.
     */
    @Transactional
    public void mirrorBatch(List<Map<String, Object>> batchOperations) {
        log.info("📦 SQL BATCH: Starting transaction for {} operations...", batchOperations.size());
        for (Map<String, Object> operation : batchOperations) {
            String table = (String) operation.get("table");
            Map<String, Object> keys = (Map<String, Object>) operation.get("keys");
            Map<String, Object> data = (Map<String, Object>) operation.get("data");

            mirrorDataToTable(table, keys, data);
        }
        log.info("✅ BATCH COMPLETE: All tables synchronized.");
    }

    /**
     * 🟢 SAVE: Insert/Update with Schema Support
     */
    @Transactional
    public void mirrorDataToTable(String targetTableName, Map<String, Object> identifiers, Map<String, Object> data) {
        log.info("🪞 SQL MIRROR: Syncing [{}] | Keys: {}", targetTableName, identifiers);

        try {
            // 1. PARSE SCHEMA & TABLE
            String schemaName = null;
            String tableName = targetTableName;

            if (targetTableName.contains(".")) {
                String[] parts = targetTableName.split("\\.", 2);
                schemaName = parts[0];
                tableName = parts[1];
            }

            // 2. FETCH COLUMNS (Fixed for PostgreSQL)
            var step = dsl.select(
                            DSL.field("column_name", String.class),
                            DSL.field("data_type", String.class)
                    )
                    .from("information_schema.columns")
                    .where(DSL.field("table_name").eq(tableName));

            if (schemaName != null) {
                step = step.and(DSL.field("table_schema").eq(schemaName));
            } else {
                // POSTGRES UPDATE: Use 'current_schema' instead of 'DATABASE()'
                // In Postgres, tables live in schemas (default: public), not directly in the DB name namespace.
                step = step.and(DSL.field("table_schema").eq(DSL.function("current_schema", String.class)));
            }

            Map<String, String> columnTypes = step.fetchMap(
                    field -> field.get("column_name", String.class).toLowerCase(),
                    field -> field.get("data_type", String.class).toLowerCase()
            );

            if (columnTypes.isEmpty()) {
                log.warn("⚠️ MIRROR ABORTED: Table [{}{}] does not exist.",
                        (schemaName != null ? schemaName + "." : ""), tableName);
                return;
            }

            // 3. PREPARE DATA
            Map<Field<?>, Object> writeData = new HashMap<>();
            Map<String, Object> finalData = new HashMap<>(data);
            identifiers.forEach(finalData::putIfAbsent);

            for (Map.Entry<String, Object> entry : finalData.entrySet()) {
                String colName = entry.getKey().toLowerCase().replaceAll("[^a-z0-9_]", "");

                if (columnTypes.containsKey(colName)) {
                    Object value = entry.getValue();
                    String dbType = columnTypes.get(colName);
                    Field<Object> field = DSL.field(DSL.name(colName));

                    // POSTGRES UPDATE: Check for 'jsonb' as well as 'json'
                    boolean isJson = "json".equalsIgnoreCase(dbType) || "jsonb".equalsIgnoreCase(dbType);

                    if (isJson && (value instanceof Map || value instanceof List)) {
                        try { value = objectMapper.writeValueAsString(value); } catch (Exception e) {}
                    } else if (value instanceof Map || value instanceof List) {
                        value = value.toString();
                    }
                    writeData.put(field, value);
                }
            }

            // 4. DEFINE SQL TABLE TARGET
            Table<?> sqlTable = (schemaName != null)
                    ? DSL.table(DSL.name(schemaName, tableName))
                    : DSL.table(DSL.name(tableName));

            // 5. CHECK EXISTENCE
            Condition matchCondition = DSL.noCondition();
            boolean validKeyFound = false;

            for (Map.Entry<String, Object> idEntry : identifiers.entrySet()) {
                String col = idEntry.getKey().toLowerCase();
                if (columnTypes.containsKey(col)) {
                    matchCondition = matchCondition.and(DSL.field(DSL.name(col)).eq(idEntry.getValue()));
                    validKeyFound = true;
                }
            }

            if (!validKeyFound) {
                log.error("❌ MIRROR FAILED: No valid keys found for [{}].", targetTableName);
                return;
            }

            boolean exists = dsl.fetchExists(dsl.selectOne().from(sqlTable).where(matchCondition));

            // 6. EXECUTE UPSERT
            if (exists) {
                log.info("🔄 UPDATING record in [{}]...", targetTableName);
                dsl.update(sqlTable).set(writeData).where(matchCondition).execute();
            } else {
                log.info("✨ INSERTING record into [{}]...", targetTableName);
                if (columnTypes.containsKey("id") && !writeData.containsKey(DSL.field(DSL.name("id")))) {
                    writeData.put(DSL.field(DSL.name("id")), UUID.randomUUID().toString());
                }
                dsl.insertInto(sqlTable).set(writeData).execute();
            }

        } catch (Exception e) {
            log.error("❌ MIRROR CRASH: {}", e.getMessage(), e);
            throw new RuntimeException("SQL Mirroring Failed", e);
        }
    }

    /**
     * 🟢 UNIVERSAL FETCH
     */
    public List<Map<String, Object>> fetchTableData(String targetTableName, Map<String, String> queryParams) {
        String schemaName = null;
        String tableName = targetTableName;

        if (targetTableName.contains(".")) {
            String[] parts = targetTableName.split("\\.", 2);
            schemaName = parts[0];
            tableName = parts[1];
        }

        log.info("🌐 SQL FETCH: Querying [{}.{}]", (schemaName != null ? schemaName : "DEFAULT"), tableName);

        SelectQuery<Record> query = dsl.selectQuery();
        Table<?> table = (schemaName != null)
                ? DSL.table(DSL.name(schemaName, tableName))
                : DSL.table(DSL.name(tableName));

        query.addFrom(table);

        applyUniversalFilters(query, queryParams);
        applyUniversalSorting(query, queryParams);

        int limit = Integer.parseInt(queryParams.getOrDefault("limit", "100"));
        int skip = Integer.parseInt(queryParams.getOrDefault("skip", "0"));
        query.addLimit(skip, limit);

        try {
            Result<Record> records = query.fetch();
            return mapRecordsToJson(records, queryParams.get("select"));
        } catch (Exception e) {
            log.error("❌ FETCH FAILED: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ♻️ REFACTORED FILTER LOGIC (Fixed for PostgreSQL)
    private void applyUniversalFilters(SelectQuery<Record> query, Map<String, String> params) {
        params.forEach((key, value) -> {
            if (List.of("limit", "skip", "sort", "select", "table", "formId").contains(key)) return;

            String rawCol = key.replace("data.", "").toLowerCase();
            String colName = rawCol.replaceAll("__[a-z]+$", "");
            Field<Object> field = DSL.field(DSL.name(colName));

            // POSTGRES UPDATE: Use '~*' (Case insensitive Regex) instead of MySQL 'REGEXP'
            if (key.endsWith("__regex")) query.addConditions(DSL.condition("{0} ~* ?", field, value));
            else if (key.endsWith("__gt")) query.addConditions(field.gt(value));
            else if (key.endsWith("__gte")) query.addConditions(field.ge(value));
            else if (key.endsWith("__lt")) query.addConditions(field.lt(value));
            else if (key.endsWith("__lte")) query.addConditions(field.le(value));
            else if (key.endsWith("__ne")) query.addConditions(field.ne(value));
            else if (key.endsWith("__in")) query.addConditions(field.in(Arrays.asList(value.split(","))));
            else query.addConditions(field.eq(value));
        });
    }

    private void applyUniversalSorting(SelectQuery<Record> query, Map<String, String> params) {
        String sortParam = params.get("sort");
        if (sortParam != null && !sortParam.isEmpty()) {
            SortOrder order = sortParam.startsWith("-") ? SortOrder.DESC : SortOrder.ASC;
            String colName = (sortParam.startsWith("-") ? sortParam.substring(1) : sortParam)
                    .replace("data.", "").toLowerCase().replaceAll("[^a-z0-9_]", "");

            query.addOrderBy(DSL.field(DSL.name(colName)).sort(order));
        }
    }

    private List<Map<String, Object>> mapRecordsToJson(Result<Record> records, String selectParam) {
        List<String> allowedFields = (selectParam != null)
                ? Arrays.stream(selectParam.split(","))
                .map(s -> s.replace("data.", "").trim().toLowerCase())
                .collect(Collectors.toList())
                : null;

        List<Map<String, Object>> submissions = new ArrayList<>();

        for (Record record : records) {
            Map<String, Object> submission = new HashMap<>();
            Map<String, Object> data = new HashMap<>();

            String pk = null;
            if (record.field("id") != null) pk = record.get("id", String.class);
            else if (record.field("ID") != null) pk = record.get("ID", String.class);

            if (pk != null) {
                submission.put("id", pk);
                submission.put("_id", pk);
                data.put("id", pk);
            }

            if (record.field("created_at") != null) submission.put("created", record.get("created_at"));
            else if (record.field("created") != null) submission.put("created", record.get("created"));

            for (org.jooq.Field<?> field : record.fields()) {
                String name = field.getName();
                if (List.of("id", "submission_id", "created_at").contains(name.toLowerCase())) continue;

                if (allowedFields == null || allowedFields.contains(name.toLowerCase())) {
                    Object value = record.get(field);

                    // Handle Postgres JSONB and JSON
                    if (value instanceof org.jooq.JSON) {
                        try { value = objectMapper.readValue(((org.jooq.JSON) value).data(), Object.class); } catch (Exception e) { value = ((org.jooq.JSON) value).data(); }
                    } else if (value instanceof org.jooq.JSONB) {
                        try { value = objectMapper.readValue(((org.jooq.JSONB) value).data(), Object.class); } catch (Exception e) { value = ((org.jooq.JSONB) value).data(); }
                    }

                    data.put(name, value);
                }
            }
            submission.put("data", data);
            submissions.add(submission);
        }
        return submissions;
    }
}