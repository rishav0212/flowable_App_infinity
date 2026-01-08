package com.example.flowable_app.controller;

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

    @GetMapping("/system-overview")
    public ResponseEntity<?> getSystemOverview() {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("activeInstances", runtimeService.createProcessInstanceQuery().count());
            stats.put("completedInstances", historyService.createHistoricProcessInstanceQuery().finished().count());
            stats.put("activeTasks", taskService.createTaskQuery().count());
            stats.put("completedTasks", historyService.createHistoricTaskInstanceQuery().finished().count());
            stats.put("failedJobs", managementService.createDeadLetterJobQuery().count());
            stats.put("timerJobs", managementService.createTimerJobQuery().count());

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("❌ STATS ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch system overview"));
        }
    }

    @GetMapping("/process-distribution")
    public ResponseEntity<?> getProcessDistribution() {
        try {
            List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery().latestVersion().list();
            List<Map<String, Object>> distribution = defs.stream().map(def -> {
                Map<String, Object> item = new HashMap<>();
                item.put("key", def.getKey());
                item.put("name", def.getName());
                item.put("version", def.getVersion());
                item.put("activeInstances",
                        runtimeService.createProcessInstanceQuery().processDefinitionKey(def.getKey()).count());
                item.put("activeTasks", taskService.createTaskQuery().processDefinitionKey(def.getKey()).count());
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