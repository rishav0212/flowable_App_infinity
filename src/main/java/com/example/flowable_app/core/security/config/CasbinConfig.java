package com.example.flowable_app.core.security.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.casbin.adapter.JDBCAdapter;
import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class CasbinConfig {

    private final DataSource dataSource;
    // Caches Enforcer per tenant schema for microsecond evaluations
    private final Map<String, Enforcer> enforcerCache = new ConcurrentHashMap<>();

    public Enforcer getEnforcer(String schemaName) {
        return enforcerCache.computeIfAbsent(schemaName, schema -> {
            try {
                log.info("Initializing Casbin enforcer for schema: {}", schema);

                // 🛡️ THE MULTI-TENANT FIX: Wrap the DataSource
                // This intercepts the database connection and strictly sets the
                // schema search_path before Casbin executes any SQL.
                DataSource tenantDataSource = new DelegatingDataSource(dataSource) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection conn = super.getConnection();
                        try (Statement st = conn.createStatement()) {
                            st.execute("SET search_path TO " + schema);
                        }
                        return conn;
                    }
                };

                // Initialize the adapter with the wrapped, tenant-safe DataSource
                JDBCAdapter adapter = new JDBCAdapter(tenantDataSource);

                Model model = new Model();
                String modelText = new String(getClass().getResourceAsStream("/casbin_model.conf").readAllBytes());
                model.loadModelFromText(modelText);

                Enforcer enforcer = new Enforcer(model, adapter);
                enforcer.enableAutoSave(true); // Auto-saves to DB
                enforcer.loadPolicy();

                return enforcer;
            } catch (Exception e) {
                throw new RuntimeException("Failed to init Casbin for schema: " + schema, e);
            }
        });
    }

    public void invalidateCache(String schemaName) {
        enforcerCache.remove(schemaName);
    }
}