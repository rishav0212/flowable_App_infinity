package com.example.flowable_app.controller;

import com.example.flowable_app.service.UserContextService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Controller responsible for securely fetching dynamic database schemas (tables and columns)
 * for the Form.io builder UI. This allows users to build components mapped to real database
 * tables without needing to hardcode table names in the frontend.
 */
@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
@Slf4j
public class SchemaController {

    private final JdbcTemplate jdbcTemplate;
    private final UserContextService userContextService;

    // Define tables that should NEVER appear in the frontend Form Builder dropdowns.
    // This is crucial for security so users cannot query passwords, auth mappings, or migration logs.
    private static final Set<String> HIDDEN_TABLES = Set.of(
            "tbl_users",
            "tbl_user_resources",
            "casbin_rule",
            "tbl_user_email_mapping",
            "databasechangelog",
            "databasechangeloglock"
    );

    // =========================================================================================
    // INNER DTO CLASSES
    // Kept inside the controller to reduce file clutter, as these are exclusively used here.
    // =========================================================================================

    @Data
    @AllArgsConstructor
    public static class TableDefinitionDto {
        private String tableName;
    }

    @Data
    @AllArgsConstructor
    public static class ColumnDefinitionDto {
        private String columnName;
    }


    // =========================================================================================
    // ENDPOINTS
    // =========================================================================================

    /**
     * Fetches all allowed tables for the current tenant's specific schema.
     */
    @GetMapping("/tables")
    public ResponseEntity<List<TableDefinitionDto>> getTables() {
        // Automatically extract the tenant's schema name from the JWT/Context to ensure data isolation
        String currentTenantSchema = userContextService.getCurrentTenantSchema();
        log.info("Fetching allowed tables for schema: {}", currentTenantSchema);

        // Query the built-in PostgreSQL information_schema to dynamically find physical tables
        String sql = "SELECT table_name " +
                "FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                "ORDER BY table_name ASC";

        List<TableDefinitionDto> tables = jdbcTemplate.query(sql, (rs, rowNum) -> {
                    String tableName = rs.getString("table_name");
                    return new TableDefinitionDto(tableName);
                }, currentTenantSchema).stream()
                // Filter out any sensitive internal tables defined in our HIDDEN_TABLES set
                .filter(table -> !HIDDEN_TABLES.contains(table.getTableName().toLowerCase()))
                .toList();

        return ResponseEntity.ok(tables);
    }

    /**
     * Fetches all columns for a specific table within the tenant's schema.
     */
    @GetMapping("/columns")
    public ResponseEntity<List<ColumnDefinitionDto>> getColumns(@RequestParam("table") String tableName) {
        String currentTenantSchema = userContextService.getCurrentTenantSchema();
        log.info("Fetching columns for table: {} in schema: {}", tableName, currentTenantSchema);

        // Hard security check to prevent users from bypassing the UI and manually passing a
        // hidden table name via the API parameters.
        if (HIDDEN_TABLES.contains(tableName.toLowerCase())) {
            throw new IllegalArgumentException("Access to this table is restricted.");
        }

        // Query information_schema.columns to get the exact columns for the requested table
        String sql = "SELECT column_name " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "ORDER BY ordinal_position ASC";

        List<ColumnDefinitionDto> columns = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String columnName = rs.getString("column_name");
            return new ColumnDefinitionDto(columnName);
        }, currentTenantSchema, tableName);

        return ResponseEntity.ok(columns);
    }
}