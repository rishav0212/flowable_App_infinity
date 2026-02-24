package com.example.flowable_app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
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
    // 🟢 INJECTED UserContextService to automatically route queries to the current tenant's schema
    private final UserContextService userContextService;

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
     * 🟢 SAVE: Insert/Update with Schema Support and Exact Case Matching
     */
    @Transactional
    public void mirrorDataToTable(String targetTableName, Map<String, Object> identifiers, Map<String, Object> data) {
        log.info("🪞 SQL MIRROR: Syncing [{}] | Keys: {}", targetTableName, identifiers);

        try {
            // 1. 🟢 PARSE SCHEMA & TABLE DYNAMICALLY
            // We use UserContextService to automatically target the correct tenant schema
            // if no explicit schema is provided in the targetTableName.
            String schemaName = userContextService.getCurrentTenantSchema();
            String tableName = targetTableName;

            if (targetTableName.contains(".")) {
                String[] parts = targetTableName.split("\\.", 2);
                schemaName = parts[0];
                tableName = parts[1];
            }

            log.debug("🛣️ Resolving DB Target -> Schema: [{}], Table: [{}]", schemaName, tableName);

            // 2. 🟢 FETCH COLUMNS EXACT CASE (PostgreSQL Fix)
            // We query information_schema to find the ACTUAL exact casing of the database columns.
            // This prevents failures if the DB column is "ORDER_NO_C" but the JSON payload sends "order_no_c".
            var step = dsl.select(
                            DSL.field("column_name", String.class),
                            DSL.field("data_type", String.class)
                    )
                    .from("information_schema.columns")
                    .where(DSL.field("table_name").eq(tableName))
                    .and(DSL.field("table_schema").eq(schemaName));

            Result<Record2<String, String>> dbColumns = step.fetch();

            if (dbColumns.isEmpty()) {
                log.warn("⚠️ MIRROR ABORTED: Table [{}.{}] does not exist in database.", schemaName, tableName);
                return;
            }

            // Mappings to easily bridge incoming lowercase keys to actual database exact-case keys
            Map<String, String> dbExactCaseMap = new HashMap<>();
            Map<String, String> columnTypes = new HashMap<>();

            for(Record r : dbColumns) {
                String exactName = r.get("column_name", String.class);
                String dataType = r.get("data_type", String.class).toLowerCase();
                // Map the lowercase version to the exact version
                dbExactCaseMap.put(exactName.toLowerCase(), exactName);
                columnTypes.put(exactName, dataType);
            }

            // 3. PREPARE DATA
            Map<Field<?>, Object> writeData = new HashMap<>();
            Map<String, Object> finalData = new HashMap<>(data);
            identifiers.forEach(finalData::putIfAbsent);

            for (Map.Entry<String, Object> entry : finalData.entrySet()) {
                // Normalize incoming key to lowercase to match against our bridge map
                String incomingCol = entry.getKey().toLowerCase().replaceAll("[^a-z0-9_]", "");

                if (dbExactCaseMap.containsKey(incomingCol)) {
                    String exactDbColName = dbExactCaseMap.get(incomingCol);
                    Object value = entry.getValue();
                    String dbType = columnTypes.get(exactDbColName);

                    // 🟢 FORCE QUOTING: DSL.name() guarantees PostgreSQL respects the exact casing
                    Field<Object> field = DSL.field(DSL.name(exactDbColName));

                    // POSTGRES UPDATE: Check for 'jsonb' as well as 'json'
                    boolean isJson = "json".equalsIgnoreCase(dbType) || "jsonb".equalsIgnoreCase(dbType);

                    if (isJson && (value instanceof Map || value instanceof List)) {
                        try { value = objectMapper.writeValueAsString(value); } catch (Exception e) {}
                    } else if (value instanceof Map || value instanceof List) {
                        value = value.toString();
                    }
                    writeData.put(field, value);
                } else {
                    log.debug("ℹ️ Ignored JSON field [{}] - No matching column found in DB table [{}]", entry.getKey(), tableName);
                }
            }

            // 4. DEFINE SQL TABLE TARGET
            // DSL.name() securely quotes the schema and table
            Table<?> sqlTable = DSL.table(DSL.name(schemaName, tableName));

            // 5. CHECK EXISTENCE
            Condition matchCondition = DSL.noCondition();
            boolean validKeyFound = false;

            for (Map.Entry<String, Object> idEntry : identifiers.entrySet()) {
                String incomingCol = idEntry.getKey().toLowerCase().replaceAll("[^a-z0-9_]", "");
                if (dbExactCaseMap.containsKey(incomingCol)) {
                    String exactDbColName = dbExactCaseMap.get(incomingCol);
                    // Match condition uses rigidly quoted column names
                    matchCondition = matchCondition.and(DSL.field(DSL.name(exactDbColName)).eq(idEntry.getValue()));
                    validKeyFound = true;
                }
            }

            if (!validKeyFound) {
                log.error("❌ MIRROR FAILED: No valid identifying keys found for table [{}.{}].", schemaName, tableName);
                return;
            }

            boolean exists = dsl.fetchExists(dsl.selectOne().from(sqlTable).where(matchCondition));

            // 6. EXECUTE UPSERT
            if (exists) {
                log.info("🔄 UPDATING record in [{}.{}]...", schemaName, tableName);
                dsl.update(sqlTable).set(writeData).where(matchCondition).execute();
            } else {
                log.info("✨ INSERTING record into [{}.{}]...", schemaName, tableName);

                // Ensure UUID generation relies on the exact casing if the table has an 'id' column
                if (dbExactCaseMap.containsKey("id")) {
                    String exactIdCol = dbExactCaseMap.get("id");
                    if (!writeData.containsKey(DSL.field(DSL.name(exactIdCol)))) {
                        writeData.put(DSL.field(DSL.name(exactIdCol)), UUID.randomUUID().toString());
                    }
                }
                dsl.insertInto(sqlTable).set(writeData).execute();
            }

        } catch (Exception e) {
            log.error("❌ MIRROR CRASH on target [{}]: {}", targetTableName, e.getMessage(), e);
            throw new RuntimeException("SQL Mirroring Failed", e);
        }
    }

    /**
     * 🟢 UNIVERSAL FETCH
     */
    public List<Map<String, Object>> fetchTableData(String targetTableName, Map<String, String> queryParams) {
        // 1. 🟢 PARSE SCHEMA & TABLE DYNAMICALLY
        String schemaName = userContextService.getCurrentTenantSchema();
        String tableName = targetTableName;

        if (targetTableName.contains(".")) {
            String[] parts = targetTableName.split("\\.", 2);
            schemaName = parts[0];
            tableName = parts[1];
        }

        log.info("🌐 SQL FETCH: Querying [{}.{}]", schemaName, tableName);

        SelectQuery<Record> query = dsl.selectQuery();
        Table<?> table = DSL.table(DSL.name(schemaName, tableName));

        query.addFrom(table);

        applyUniversalFilters(query, queryParams);
        applyUniversalSorting(query, queryParams);

        int limit = Integer.parseInt(queryParams.getOrDefault("limit", "100"));
        int skip = Integer.parseInt(queryParams.getOrDefault("skip", "0"));
        query.addLimit(skip, limit);

        try {
            Result<Record> records = query.fetch();
            log.debug("✅ FETCH SUCCESS: Retrieved {} records from [{}.{}]", records.size(), schemaName, tableName);
            return mapRecordsToJson(records, queryParams.get("select"));
        } catch (Exception e) {
            log.error("❌ FETCH FAILED on [{}.{}]: {}", schemaName, tableName, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // ♻️ REFACTORED FILTER LOGIC (Fixed for PostgreSQL & Case Sensitivity)
    private void applyUniversalFilters(SelectQuery<Record> query, Map<String, String> params) {
        params.forEach((key, value) -> {
            if (List.of("limit", "skip", "sort", "select", "table", "formId").contains(key)) return;

            // 🟢 PRESERVE EXACT CASE: We extract the raw column string from the API parameter
            // without using .toLowerCase() to ensure PostgreSQL looks for the exact cased column.
            String rawCol = key.replace("data.", "");
            String colName = rawCol;
            String operator = "";

            if (rawCol.contains("__")) {
                int lastIndex = rawCol.lastIndexOf("__");
                colName = rawCol.substring(0, lastIndex);
                operator = rawCol.substring(lastIndex);
            }

            // DSL.name() securely wraps the column in quotes (e.g. "ORDER_NO_C")
            Field<Object> field = DSL.field(DSL.name(colName));

            // POSTGRES UPDATE: Use '~*' (Case insensitive Regex) instead of MySQL 'REGEXP'
            if ("__regex".equals(operator)) query.addConditions(DSL.condition("{0} ~* ?", field, value));
            else if ("__gt".equals(operator)) query.addConditions(field.gt(value));
            else if ("__gte".equals(operator)) query.addConditions(field.ge(value));
            else if ("__lt".equals(operator)) query.addConditions(field.lt(value));
            else if ("__lte".equals(operator)) query.addConditions(field.le(value));
            else if ("__ne".equals(operator)) query.addConditions(field.ne(value));
            else if ("__in".equals(operator)) query.addConditions(field.in(Arrays.asList(value.split(","))));
            else query.addConditions(field.eq(value));
        });
    }

    private void applyUniversalSorting(SelectQuery<Record> query, Map<String, String> params) {
        String sortParam = params.get("sort");
        if (sortParam != null && !sortParam.isEmpty()) {
            SortOrder order = sortParam.startsWith("-") ? SortOrder.DESC : SortOrder.ASC;

            // 🟢 PRESERVE EXACT CASE for sorting to avoid "column does not exist" crashes
            String colName = sortParam.startsWith("-") ? sortParam.substring(1) : sortParam;
            colName = colName.replace("data.", "");

            query.addOrderBy(DSL.field(DSL.name(colName)).sort(order));
        }
    }

    private List<Map<String, Object>> mapRecordsToJson(Result<Record> records, String selectParam) {
        // Kept lowercasing here ONLY for the API requested fields, since API requested fields
        // are often lowercased natively by JavaScript frameworks.
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
                // 🟢 Preserves exact case retrieved straight from the Database
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