package com.example.flowable_app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaSyncService {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Transactional
    public void syncFormDefinition(String jsonBody) {
        try {
            JsonNode formNode = objectMapper.readTree(jsonBody);

            // 1. Determine Table Name
            String tableName;
            if (formNode.has("properties") && formNode.get("properties").has("targetTable")) {
                String rawName = formNode.get("properties").get("targetTable").asText();
                tableName = "tbl_" + rawName.toLowerCase().replaceAll("[^a-z0-9_]", "");
                log.info("🎯 Schema Sync: Custom Table '{}'", tableName);
            } else {
                if (!formNode.has("path")) return;
                String formPath = formNode.get("path").asText();
                tableName = "tbl_" + formPath.toLowerCase().replaceAll("[^a-z0-9_]", "");
            }

            // 2. Create Table if not exists (MySQL Compatible)
            dsl.createTableIfNotExists(DSL.name(tableName))
                    .column("id", SQLDataType.VARCHAR(64))
                    .column("created_at", SQLDataType.TIMESTAMP.defaultValue(DSL.currentTimestamp()))
                    .constraints(
                            DSL.constraint("pk_" + tableName).primaryKey("id")
                    )
                    .execute();

            // 3. Fetch Existing Columns from DB
            // Updated for MySQL: Added table_schema check to prevent cross-database conflicts
            List<String> existingColumns = dsl.select(DSL.field("column_name"))
                    .from("information_schema.columns")
                    .where(DSL.field("table_name").eq(tableName))
                    .and(DSL.field("table_schema").eq(DSL.function("DATABASE", String.class)))
                    .fetchInto(String.class);

            Set<String> existingColSet = new HashSet<>(existingColumns);

            // 4. Scan JSON and Add New Columns
            JsonNode components = formNode.get("components");
            scanAndAddColumns(tableName, components, existingColSet);

            log.info("✅ Table '{}' synced successfully.", tableName);

        } catch (Exception e) {
            log.error("❌ Schema Sync Failed: {}", e.getMessage());
        }
    }

    private void scanAndAddColumns(String tableName, JsonNode components, Set<String> existingCols) {
        if (components == null || !components.isArray()) return;

        for (JsonNode comp : components) {
            String type = comp.path("type").asText();
            String key = comp.path("key").asText().toLowerCase().replaceAll("[^a-z0-9_]", "");

            // Ignore layout components
            if (isLayout(type)) {
                if (comp.has("components")) scanAndAddColumns(tableName, comp.get("components"), existingCols);
                if (comp.has("columns")) {
                    for (JsonNode col : comp.get("columns")) {
                        scanAndAddColumns(tableName, col.get("components"), existingCols);
                    }
                }
                continue;
            }

            // ALTER TABLE: Add column if it doesn't exist
            if (!existingCols.contains(key)) {
                log.info("   -> Adding new column: {} ({})", key, type);

                try {
                    dsl.alterTable(DSL.name(tableName))
                            .addColumn(DSL.name(key), getSqlType(type))
                            .execute();

                    existingCols.add(key);
                } catch (Exception e) {
                    log.warn("Failed to add column {}: {}", key, e.getMessage());
                }
            }
        }
    }

    private org.jooq.DataType<?> getSqlType(String type) {
        switch (type) {
            case "number":
            case "currency":
                return SQLDataType.NUMERIC;
            case "checkbox":
                return SQLDataType.BOOLEAN;
            case "datetime":
            case "day":
                return SQLDataType.TIMESTAMP;

            // Updated for MySQL: Changed JSONB to JSON
            case "file":
            case "tags":
            case "select":
                return SQLDataType.JSON;

            case "textarea":
                return SQLDataType.CLOB;

            default:
                return SQLDataType.VARCHAR(255);
        }
    }

    private boolean isLayout(String type) {
        return List.of("button", "htmlelement", "content", "columns", "panel", "fieldset", "well", "table")
                .contains(type);
    }
}













//package com.example.flowable_app.service;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.jooq.DSLContext;
//import org.jooq.impl.DSL;
//import org.jooq.impl.SQLDataType;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class SchemaSyncService {
//
//    private final DSLContext dsl;
//    private final ObjectMapper objectMapper;
//
//    @Transactional
//    public void syncFormDefinition(String jsonBody) {
//        try {
//            JsonNode formNode = objectMapper.readTree(jsonBody);
//
//            // 🛑 CHANGE 1: Look for 'targetTable' property
//            String tableName;
//            if (formNode.has("properties") && formNode.get("properties").has("targetTable")) {
//                String rawName = formNode.get("properties").get("targetTable").asText();
//                tableName = "tbl_" + rawName.toLowerCase().replaceAll("[^a-z0-9_]", "");
//                log.info("🎯 Schema Sync: Custom Table '{}'", tableName);
//            } else {
//                if (!formNode.has("path")) return;
//                String formPath = formNode.get("path").asText();
//                tableName = "tbl_" + formPath.toLowerCase().replaceAll("[^a-z0-9_]", "");
//            }
//
//            // 🛑 CHANGE 2: Ensure 'business_key' column exists (Required for your Logic)
//            dsl.createTableIfNotExists(DSL.name(tableName))
//                    .column("id", SQLDataType.VARCHAR(64))
//                    .column("created_at", SQLDataType.TIMESTAMP.defaultValue(DSL.currentTimestamp()))
//                    .constraints(
//                            DSL.constraint("pk_" + tableName).primaryKey("id")
//                            // You must manually add UNIQUE(business_key) in your DB if not present
//                    )
//                    .execute();
//
//            // 2. Fetch Existing Columns from DB
//            // We ask Postgres what columns currently exist
//            List<String> existingColumns = dsl.select(DSL.field("column_name"))
//                    .from("information_schema.columns")
//                    .where(DSL.field("table_name").eq(tableName))
//                    .fetchInto(String.class);
//
//            Set<String> existingColSet = new HashSet<>(existingColumns);
//
//            // 3. Scan JSON and Add New Columns
//            JsonNode components = formNode.get("components");
//            scanAndAddColumns(tableName, components, existingColSet);
//
//            log.info("✅ Table '{}' synced successfully.", tableName);
//
//        } catch (Exception e) {
//            log.error("❌ Schema Sync Failed: {}", e.getMessage());
//        }
//    }
//
//    private void scanAndAddColumns(String tableName, JsonNode components, Set<String> existingCols) {
//        if (components == null || !components.isArray()) return;
//
//        for (JsonNode comp : components) {
//            String type = comp.path("type").asText();
//            String key = comp.path("key").asText().toLowerCase().replaceAll("[^a-z0-9_]", "");
//
//            // Ignore layout components
//            if (isLayout(type)) {
//                if (comp.has("components")) scanAndAddColumns(tableName, comp.get("components"), existingCols);
//                if (comp.has("columns")) {
//                    for (JsonNode col : comp.get("columns")) {
//                        scanAndAddColumns(tableName, col.get("components"), existingCols);
//                    }
//                }
//                continue;
//            }
//
//            // 🛑 ALTER TABLE: Add column if it doesn't exist
//            if (!existingCols.contains(key)) {
//                log.info("   -> Adding new column: {} ({})", key, type);
//
//                try {
//                    dsl.alterTable(DSL.name(tableName))
//                            .addColumn(DSL.name(key), getSqlType(type))
//                            .execute();
//
//                    existingCols.add(key); // Mark as added
//                } catch (Exception e) {
//                    log.warn("Failed to add column {}: {}", key, e.getMessage());
//                }
//            }
//        }
//    }
//
//    private org.jooq.DataType<?> getSqlType(String type) {
//        switch (type) {
//            case "number":
//            case "currency":
//                return SQLDataType.NUMERIC;
//            case "checkbox":
//                return SQLDataType.BOOLEAN;
//            case "datetime":
//            case "day":
//                return SQLDataType.TIMESTAMP;
//
//            // 🟢 NEW: Handle File and Tags as JSONB
//            case "file":
//            case "tags":
//            case "select": // Select boxes can also be arrays
//                return SQLDataType.JSONB;
//
//            case "textarea":
//                return SQLDataType.CLOB; // Unlimited Text
//
//            default:
//                return SQLDataType.VARCHAR(255);
//        }
//    }
//    private boolean isLayout(String type) {
//        return List.of("button", "htmlelement", "content", "columns", "panel", "fieldset", "well", "table")
//                .contains(type);
//    }
//}