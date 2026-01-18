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

//
// ... existing imports ...

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

            // Reuse the single mirror logic for each item
            mirrorDataToTable(table, keys, data);
        }
        log.info("✅ BATCH COMPLETE: All tables synchronized.");
    }

    /**
     * 🟢 SAVE: Insert/Update with Schema Support (e.g. "infinity.orders")
     * NOTE: Does NOT add "tbl_" prefix automatically. We trust your input.
     */
    @Transactional
    public void mirrorDataToTable(String targetTableName, Map<String, Object> identifiers, Map<String, Object> data) {
        log.info("🪞 SQL MIRROR: Syncing [{}] | Keys: {}", targetTableName, identifiers);

        try {
            // 1. PARSE SCHEMA & TABLE (Manual Control)
            String schemaName = null;
            String tableName = targetTableName;

            // Handle "schema.table" format
            if (targetTableName.contains(".")) {
                String[] parts = targetTableName.split("\\.", 2);
                schemaName = parts[0];
                tableName = parts[1];
            }

            // 2. FETCH COLUMNS (Smart Lookup)
            var step = dsl.select(
                            DSL.field("column_name", String.class),
                            DSL.field("data_type", String.class)
                    )
                    .from("information_schema.columns")
                    .where(DSL.field("table_name").eq(tableName));

            // If schema provided, filter by it. Else use current DB.
            if (schemaName != null) {
                step = step.and(DSL.field("table_schema").eq(schemaName));
            } else {
                step = step.and(DSL.field("table_schema").eq(DSL.function("DATABASE", String.class)));
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

                    if ("json".equalsIgnoreCase(dbType) && (value instanceof Map || value instanceof List)) {
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


//

    /**
     * 🟢 UNIVERSAL FETCH: Handles both "/submission" intercepts AND "/sql-data" proxies.
     * Supports:
     * 1. Schema.Table format ("infinity.orders")
     * 2. Advanced Filters ("data.amount__gt=100", "city=Delhi")
     * 3. Pagination & Sorting
     */
    public List<Map<String, Object>> fetchTableData(String targetTableName, Map<String, String> queryParams) {
        // 1. Parse Schema & Table
        String schemaName = null;
        String tableName = targetTableName;

        if (targetTableName.contains(".")) {
            String[] parts = targetTableName.split("\\.", 2);
            schemaName = parts[0];
            tableName = parts[1];
        }

        // Sanitize table name (if coming from raw input)
        if (!tableName.startsWith("tbl_") && !targetTableName.contains(".")) {
            // Optional: Force prefix if you stick to that convention,
            // but for generic generic fetching, usually better to leave it raw or handle logic here.
            // tableName = "tbl_" + tableName;
        }

        log.info("🌐 SQL FETCH: Querying [{}.{}]", (schemaName != null ? schemaName : "DEFAULT"), tableName);

        // 2. Build Query
        SelectQuery<Record> query = dsl.selectQuery();
        Table<?> table = (schemaName != null)
                ? DSL.table(DSL.name(schemaName, tableName))
                : DSL.table(DSL.name(tableName));

        query.addFrom(table);

        // 3. Apply Filters & Sorting (Reusing your Logic!)
        applyUniversalFilters(query, queryParams);
        applyUniversalSorting(query, queryParams);

        // 4. Pagination
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

    // ♻️ REFACTORED FILTER LOGIC (Used by fetchTableData)
    private void applyUniversalFilters(SelectQuery<Record> query, Map<String, String> params) {
        params.forEach((key, value) -> {
            // Skip control params
            if (List.of("limit", "skip", "sort", "select", "table", "formId").contains(key)) return;

            // 1. Strip "data." prefix (Fixes: data.order_num -> order_num)
            String rawCol = key.replace("data.", "").toLowerCase();

            // 2. Handle Suffixes (e.g. __gt, __regex)
            String colName = rawCol.replaceAll("__[a-z]+$", "");
            Field<Object> field = DSL.field(DSL.name(colName));

            // 3. Apply Conditions
            if (key.endsWith("__regex")) query.addConditions(DSL.condition("{0} REGEXP ?", field, value));
            else if (key.endsWith("__gt")) query.addConditions(field.gt(value));
            else if (key.endsWith("__gte")) query.addConditions(field.ge(value));
            else if (key.endsWith("__lt")) query.addConditions(field.lt(value));
            else if (key.endsWith("__lte")) query.addConditions(field.le(value));
            else if (key.endsWith("__ne")) query.addConditions(field.ne(value));
            else if (key.endsWith("__in")) query.addConditions(field.in(Arrays.asList(value.split(","))));
            else query.addConditions(field.eq(value)); // <--- THIS HANDLES data.order_num = "6543"
        });
    }

    // ♻️ REFACTORED SORT LOGIC
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

            String pk = record.get("id", String.class);
            submission.put("id", pk);
            submission.put("_id", pk);
            submission.put("created", record.get("created_at"));

            data.put("id", pk);

            for (org.jooq.Field<?> field : record.fields()) {
                String name = field.getName();
                if (List.of("id", "submission_id", "created_at").contains(name)) continue;

                if (allowedFields == null || allowedFields.contains(name)) {
                    Object value = record.get(field);

                    // MySQL returns org.jooq.JSON instead of JSONB
                    if (value instanceof org.jooq.JSON) {
                        try {
                            value = objectMapper.readValue(((org.jooq.JSON) value).data(), Object.class);
                        } catch (Exception e) {
                            log.error("❌ JSON DESERIALIZE FAILED for [{}]: {}", name, e.getMessage());
                            value = ((org.jooq.JSON) value).data();
                        }
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


//package com.example.flowable_app.service;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.jooq.Record;
//import org.jooq.*;
//import org.jooq.impl.DSL;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class DataMirrorService {
//
//    private final DSLContext dsl;
//    private final ObjectMapper objectMapper;
//
//    /**
//     * 🟢 SAVE: Takes Form.io JSON data and inserts/updates it in PostgreSQL
//     */
//    @Transactional
//    public void mirrorDataToTable(String targetTableName, String recordId, Map<String, Object> data) {
//        log.info("🪞 SQL MIRROR: Starting sync for table [{}] | ID: [{}]", targetTableName, recordId);
//
//        try {
//            // 1. Sanitize Table Name
//            String tableName = targetTableName.startsWith("tbl_")
//                    ? targetTableName
//                    : "tbl_" + targetTableName.toLowerCase().replaceAll("[^a-z0-9_]", "");
//
//            // 2. Fetch Schema to map types correctly
//            log.debug("🔍 SCHEMA: Fetching column types for table [{}]", tableName);
//            Map<String, String> columnTypes = dsl.select(
//                            DSL.field("column_name", String.class),
//                            DSL.field("udt_name", String.class)
//                    )
//                    .from("information_schema.columns")
//                    .where(DSL.field("table_name").eq(tableName))
//                    .fetchMap(
//                            field -> field.get("column_name", String.class).toLowerCase(),
//                            field -> field.get("udt_name", String.class).toLowerCase()
//                    );
//
//            if (columnTypes.isEmpty()) {
//                log.warn("⚠️ MIRROR ABORTED: Table [{}] does not exist in PostgreSQL schema.", tableName);
//                return;
//            }
//
//            Map<Field<?>, Object> insertData = new HashMap<>();
//            Map<Field<?>, Object> updateData = new HashMap<>();
//
//            // 3. Handle ID mapping
//            if (columnTypes.containsKey("id")) {
//                insertData.put(DSL.field(DSL.name("id")), recordId);
//            } else {
//                log.error("❌ MIRROR FAILED: Target table [{}] is missing 'id' primary key column.", tableName);
//                return;
//            }
//
//            // 4. Process and Log Payload Mapping
//            log.debug("📦 DATA: Mapping {} input fields to SQL columns...", data.size());
//            for (Map.Entry<String, Object> entry : data.entrySet()) {
//                String rawKey = entry.getKey();
//                if ("id".equalsIgnoreCase(rawKey)) continue;
//
//                String colName = rawKey.toLowerCase().replaceAll("[^a-z0-9_]", "");
//
//                if (columnTypes.containsKey(colName)) {
//                    Object value = entry.getValue();
//                    String dbType = columnTypes.get(colName);
//                    Field<Object> field = DSL.field(DSL.name(colName));
//
//                    // Handle JSONB
//                    if ("jsonb".equalsIgnoreCase(dbType)) {
//                        try {
//                            if (value instanceof Map || value instanceof List) {
//                                value = JSONB.valueOf(objectMapper.writeValueAsString(value));
//                            } else if (value instanceof String) {
//                                value = JSONB.valueOf((String) value);
//                            }
//                        } catch (Exception e) {
//                            log.warn("⚠️ JSONB CONVERSION: Failed for column [{}], saving as NULL.", colName);
//                            value = null;
//                        }
//                    } else if (value instanceof Map || value instanceof List) {
//                        value = value.toString();
//                    }
//
//                    insertData.put(field, value);
//                    updateData.put(field, value);
//                }
//            }
//
//            // 5. Build and Log the UPSERT Logic
//            log.info("🔨 DATABASE ACTION: Executing Upsert on [{}] for ID [{}]", tableName, recordId);
//
//            var query = dsl.insertInto(DSL.table(DSL.name(tableName)))
//                    .set(insertData)
//                    .onConflict(DSL.field(DSL.name("id")));
//
//            if (updateData.isEmpty()) {
//                log.info("➡️ UPSERT MODE: [Insert Only] No matching data columns found.");
//                query.doNothing().execute();
//            } else {
//                log.info("➡️ UPSERT MODE: [Insert or Update] Mapping {} data columns to update set.",
//                        updateData.size());
//                query.doUpdate().set(updateData).execute();
//            }
//
//            log.info("✅ MIRROR SUCCESS: Table [{}] synchronized for PK [{}]", tableName, recordId);
//
//        } catch (Exception e) {
//            log.error("❌ MIRROR CRASH: Failed to sync table [{}]. Reason: {}", targetTableName, e.getMessage());
//        }
//    }
//
//    /**
//     * 🔵 FETCH: Fetches data from SQL using Form.io query params
//     */
//    public List<Map<String, Object>> fetchSubmissionsFromSql(String formPath, Map<String, String> queryParams) {
//        String tableName = "tbl_" + formPath.toLowerCase().replaceAll("[^a-z0-9_]", "");
//        log.info("🌐 SQL FETCH: Intercepting request for form [{}] -> table [{}]", formPath, tableName);
//
//        SelectQuery<Record> query = dsl.selectQuery();
//        query.addFrom(DSL.table(DSL.name(tableName)));
//
//        applyFilters(query, queryParams, tableName);
//        applySorting(query, queryParams);
//
//        int limit = Integer.parseInt(queryParams.getOrDefault("limit", "100"));
//        int skip = Integer.parseInt(queryParams.getOrDefault("skip", "0"));
//        query.addLimit(skip, limit);
//
//        try {
//            log.debug("📡 SQL EXECUTION: Limit={}, Skip={}", limit, skip);
//            Result<Record> records = query.fetch();
//            log.info("✅ FETCH SUCCESS: Found {} records in [{}]", records.size(), tableName);
//            return mapRecordsToJson(records, queryParams.get("select"));
//        } catch (Exception e) {
//            log.error("❌ FETCH FAILED: Table [{}] error: {}", tableName, e.getMessage());
//            return new ArrayList<>();
//        }
//    }
//
//    private void applyFilters(SelectQuery<Record> query, Map<String, String> params, String tableName) {
//        params.forEach((key, value) -> {
//            if (List.of("limit", "skip", "sort", "select").contains(key)) return;
//
//            String rawCol = key.replace("data.", "").toLowerCase();
//            String colName = rawCol.replaceAll("__[a-z]+$", "");
//            Field<Object> field = DSL.field(DSL.name(colName));
//
//            log.debug("🔍 FILTER: Applied condition [{}] = [{}]", colName, value);
//
//            if (key.endsWith("__regex")) query.addConditions(DSL.condition("{0} ~* ?", field, value));
//            else if (key.endsWith("__gt")) query.addConditions(field.gt(value));
//            else if (key.endsWith("__gte")) query.addConditions(field.ge(value));
//            else if (key.endsWith("__lt")) query.addConditions(field.lt(value));
//            else if (key.endsWith("__lte")) query.addConditions(field.le(value));
//            else if (key.endsWith("__ne")) query.addConditions(field.ne(value));
//            else if (key.endsWith("__in")) query.addConditions(field.in(Arrays.asList(value.split(","))));
//            else query.addConditions(field.eq(value));
//        });
//    }
//
//    private void applySorting(SelectQuery<Record> query, Map<String, String> params) {
//        String sortParam = params.get("sort");
//        if (sortParam != null && !sortParam.isEmpty()) {
//            SortOrder order = sortParam.startsWith("-") ? SortOrder.DESC : SortOrder.ASC;
//            String colName = (sortParam.startsWith("-") ? sortParam.substring(1) : sortParam)
//                    .replace("data.", "").toLowerCase().replaceAll("[^a-z0-9_]", "");
//            if (colName.equals("created")) colName = "created_at";
//
//            log.debug("🔃 SORT: Ordering by [{}] {}", colName, order);
//            query.addOrderBy(DSL.field(DSL.name(colName)).sort(order));
//        } else {
//            query.addOrderBy(DSL.field(DSL.name("created_at")).desc());
//        }
//    }
//
//    private List<Map<String, Object>> mapRecordsToJson(Result<Record> records, String selectParam) {
//        List<String> allowedFields = (selectParam != null)
//                ? Arrays.stream(selectParam.split(","))
//                .map(s -> s.replace("data.", "").trim().toLowerCase())
//                .collect(Collectors.toList())
//                : null;
//
//        List<Map<String, Object>> submissions = new ArrayList<>();
//        for (Record record : records) {
//            Map<String, Object> submission = new HashMap<>();
//            Map<String, Object> data = new HashMap<>();
//
//            String pk = record.get("id", String.class);
//            submission.put("id", pk);
//            submission.put("_id", pk);
//            submission.put("created", record.get("created_at"));
//
//            data.put("id", pk); // Critical fix for Select component bindings
//
//            for (org.jooq.Field<?> field : record.fields()) {
//                String name = field.getName();
//                if (List.of("id", "submission_id", "created_at").contains(name)) continue;
//
//                if (allowedFields == null || allowedFields.contains(name)) {
//                    Object value = record.get(field);
//
//                    if (value instanceof org.jooq.JSONB) {
//                        try {
//                            value = objectMapper.readValue(((org.jooq.JSONB) value).data(), Object.class);
//                        } catch (Exception e) {
//                            log.error("❌ JSONB DESERIALIZE FAILED for [{}]: {}", name, e.getMessage());
//                            value = ((org.jooq.JSONB) value).data();
//                        }
//                    }
//                    data.put(name, value);
//                }
//            }
//            submission.put("data", data);
//            submissions.add(submission);
//        }
//        return submissions;
//    }
//}