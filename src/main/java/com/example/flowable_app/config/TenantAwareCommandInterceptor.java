package com.example.flowable_app.config;

import com.example.flowable_app.entity.Tenant;
import com.example.flowable_app.repository.TenantRepository;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.impl.interceptor.AbstractCommandInterceptor;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandConfig;
import org.flowable.common.engine.impl.interceptor.CommandExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.List;

/**
 * A completely bulletproof interceptor that deeply scans Flowable's core
 * command engine to extract the Tenant ID and safely inject the schema context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantAwareCommandInterceptor extends AbstractCommandInterceptor {

    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate; // 🟢 Silver bullet for direct DB lookups

    @Override
    public <T> T execute(CommandConfig config, Command<T> command, CommandExecutor commandExecutor) {
        String tenantId = null;

        // 1. Try to extract 'tenantId' directly from the Command object via method
        try {
            tenantId = (String) command.getClass().getMethod("getTenantId").invoke(command);
        } catch (Exception ignored) {}

        // 2. Try to extract a 'job' object, then get its tenantId
        if (tenantId == null || tenantId.isEmpty()) {
            Object job = getFieldValue(command, "job");
            if (job != null) {
                try {
                    tenantId = (String) job.getClass().getMethod("getTenantId").invoke(job);
                } catch (Exception ignored) {}
            }
        }

        // 3. 🟢 THE SILVER BULLET: If Flowable stripped the Tenant ID from memory and we
        // only have 'jobId' (happens in ExecuteAsyncJobCmd), we query Flowable's
        // raw database tables using Spring JDBC to get it back.
        if (tenantId == null || tenantId.isEmpty()) {
            String jobId = (String) getFieldValue(command, "jobId");
            if (jobId != null && !jobId.isEmpty()) {
                tenantId = getTenantIdFromDb(jobId);
            }
        }

        // If we successfully resolved it via any method, inject it!
        if (tenantId != null && !tenantId.trim().isEmpty()) {
            Tenant tenant = tenantRepository.findById(tenantId).orElse(null);

            if (tenant != null) {
                log.info("🔑 [CommandInterceptor] Background job executing. Injecting schema: [{}]", tenant.getSchemaName());

                UserContextService.setBackgroundSchema(tenant.getSchemaName());
                try {
                    // Execute the Flowable command (e.g., your Groovy script)
                    return next.execute(config, command, commandExecutor);
                } finally {
                    // ALWAYS clean up immediately to prevent thread leaks!
                    UserContextService.clearBackgroundSchema();
                    log.info("🧹 [CommandInterceptor] Cleared background schema context.");
                }
            }
        }

        // Normal execution for standard HTTP web requests
        return next.execute(config, command, commandExecutor);
    }

    /**
     * Recursively searches the class hierarchy for a specific private field.
     * This makes it immune to Flowable subclass changes.
     */
    private Object getFieldValue(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    /**
     * Bypasses Flowable's cache and directly queries the raw tables for the Tenant ID.
     */
    private String getTenantIdFromDb(String jobId) {
        String sql = "SELECT TENANT_ID_ FROM ACT_RU_JOB WHERE ID_ = ? " +
                "UNION " +
                "SELECT TENANT_ID_ FROM ACT_RU_TIMER_JOB WHERE ID_ = ? " +
                "UNION " +
                "SELECT TENANT_ID_ FROM ACT_RU_DEADLETTER_JOB WHERE ID_ = ?";
        try {
            List<String> results = jdbcTemplate.queryForList(sql, String.class, jobId, jobId, jobId);
            if (!results.isEmpty()) {
                return results.get(0);
            }
        } catch (Exception e) {
            log.warn("⚠️ [CommandInterceptor] JDBC Error finding job tenant: {}", e.getMessage());
        }
        return null;
    }
}