package com.example.flowable_app.controller;

import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.*;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
@Slf4j
public class AdminStatsController {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final ManagementService managementService;
    private final UserContextService userContextService; // 🟢 Injected for Tenant Awareness

    /**
     * 🟢 SYSTEM OVERVIEW (Tenant Scoped)
     * Fetches high-level statistics (Active Instances, Tasks, Jobs) strictly for the current tenant.
     */
    @GetMapping("/system-overview")
    public ResponseEntity<?> getSystemOverview() {
        try {
            // 🔒 SECURITY: Retrieve current tenant to enforce data isolation
            String tenantId = userContextService.getCurrentTenantId();

            Map<String, Object> stats = new HashMap<>();

            // 1. Process Instances (Active & Completed)
            stats.put("activeInstances", runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId) // 🔒 Filter by Tenant
                    .count());

            stats.put("completedInstances", historyService.createHistoricProcessInstanceQuery()
                    .processInstanceTenantId(tenantId) // 🔒 Filter by Tenant
                    .finished()
                    .count());

            // 2. User Tasks (Active & Completed)
            stats.put("activeTasks", taskService.createTaskQuery()
                    .taskTenantId(tenantId) // 🔒 Filter by Tenant
                    .count());

            stats.put("completedTasks", historyService.createHistoricTaskInstanceQuery()
                    .taskTenantId(tenantId) // 🔒 Filter by Tenant
                    .finished()
                    .count());

            // 3. System Jobs (Timers & Dead Letters)
            // Note: Job queries support .jobTenantId() in standard Flowable distributions.
            stats.put("failedJobs", managementService.createDeadLetterJobQuery()
                    .jobTenantId(tenantId) // 🔒 Filter by Tenant
                    .count());

            stats.put("timerJobs", managementService.createTimerJobQuery()
                    .jobTenantId(tenantId) // 🔒 Filter by Tenant
                    .count());

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("❌ STATS ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch system overview"));
        }
    }

    /**
     * 🟢 PROCESS DISTRIBUTION (Tenant Scoped)
     * Returns a breakdown of active instances and tasks per Process Definition.
     * Only returns definitions deployed for this tenant.
     */
    @GetMapping("/process-distribution")
    public ResponseEntity<?> getProcessDistribution() {
        try {
            String tenantId = userContextService.getCurrentTenantId(); // 🔒 Get Tenant

            // 1. Fetch Definitions belonging ONLY to this tenant
            List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                    .latestVersion()
                    .processDefinitionTenantId(tenantId) // 🔒 Filter definitions
                    .list();

            // 2. Map statistics for each definition
            List<Map<String, Object>> distribution = defs.stream().map(def -> {
                Map<String, Object> item = new HashMap<>();
                item.put("key", def.getKey());
                item.put("name", def.getName());
                item.put("version", def.getVersion());

                // 🔒 Count instances for this specific definition AND tenant
                item.put("activeInstances", runtimeService.createProcessInstanceQuery()
                        .processDefinitionKey(def.getKey())
                        .processInstanceTenantId(tenantId) // 🔒 Double-check tenant
                        .count());

                // 🔒 Count tasks for this specific definition AND tenant
                item.put("activeTasks", taskService.createTaskQuery()
                        .processDefinitionKey(def.getKey())
                        .taskTenantId(tenantId) // 🔒 Double-check tenant
                        .count());

                return item;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(distribution);

        } catch (Exception e) {
            log.error("❌ STATS ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch process distribution"));
        }
    }
}