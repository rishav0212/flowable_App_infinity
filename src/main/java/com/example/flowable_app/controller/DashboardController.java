package com.example.flowable_app.controller;

import com.example.flowable_app.service.UserContextService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final TaskService taskService;
    private final HistoryService historyService;
    private final UserContextService userContextService; // 🟢 Replaces the getUserId helper

    /**
     * Retrieves key workflow statistics for the current user's dashboard.
     * Logic Preserved: Grouping, Priorities, and Overdue checks are identical to original.
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            // 1. Get Context securely (Replaces manual helper)
            String userId = userContextService.getCurrentUserId();
            String tenantId = userContextService.getCurrentTenantId();

            log.info("📊 Fetching Dashboard Stats for User: [{}] Tenant: [{}]", userId, tenantId);

            // 2. Fetch Active Tasks (Scoped to User AND Tenant)
            // 🔒 Added .taskTenantId(tenantId)
            List<Task> activeTasksList = taskService.createTaskQuery()
                    .taskAssignee(userId)
                    .taskTenantId(tenantId)
                    .list();

            // 3. Group Tasks by Name (Logic Preserved)
            Map<String, Long> distribution = activeTasksList.stream()
                    .collect(Collectors.groupingBy(
                            task -> task.getName() != null ? task.getName() : "Untitled Task",
                            Collectors.counting()
                    ));

            // 4. Calculate Metrics (Logic Preserved)
            long highPriority = activeTasksList.stream().filter(t -> t.getPriority() > 50).count();

            // 🔒 Added .taskTenantId(tenantId)
            long completedTotal = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(userId)
                    .taskTenantId(tenantId)
                    .finished()
                    .count();

            long overdue = activeTasksList.stream()
                    .filter(t -> t.getDueDate() != null && t.getDueDate().before(new java.util.Date()))
                    .count();

            return ResponseEntity.ok(DashboardStats.builder()
                    .active((long) activeTasksList.size())
                    .highPriority(highPriority)
                    .completed(completedTotal)
                    .overdue(overdue)
                    .taskDistribution(distribution)
                    .build());

        } catch (Exception e) {
            log.error("❌ DASHBOARD STATS ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch stats"));
        }
    }

    /**
     * Fetches a paginated list of completed tasks for the history table.
     * Logic Preserved: Search filters and Pagination mapping are identical to original.
     */
    @GetMapping("/completed")
    public ResponseEntity<?> getCompletedTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search
    ) {
        try {
            // 1. Get Context
            String userId = userContextService.getCurrentUserId();
            String tenantId = userContextService.getCurrentTenantId();

            // 2. Build the History Query (Scoped to User AND Tenant)
            // 🔒 Added .taskTenantId(tenantId)
            HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(userId)
                    .taskTenantId(tenantId)
                    .finished()
                    .orderByHistoricTaskInstanceEndTime().desc();

            // 3. Apply Search Filter (Logic Preserved)
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim() + "%";
                query.or().taskNameLikeIgnoreCase(pattern).taskDescriptionLikeIgnoreCase(pattern).endOr();
            }

            // 4. Execute Pagination (Logic Preserved)
            long totalRecords = query.count();
            List<HistoricTaskInstance> tasks = query.listPage(page * size, size);

            // 5. Map to Response (Logic Preserved)
            List<Map<String, Object>> content = tasks.stream().map(t -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", t.getId());
                map.put("name", t.getName());
                map.put("description", t.getDescription());
                map.put("endTime", t.getEndTime());
                return map;
            }).collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("content", content);
            response.put("totalElements", totalRecords);
            response.put("totalPages", (int) Math.ceil((double) totalRecords / size));
            response.put("currentPage", page);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ DASHBOARD HISTORY ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch completed tasks"));
        }
    }

    @Data
    @Builder
    public static class DashboardStats {
        private Long active;
        private Long highPriority;
        private Long completed;
        private Long overdue;
        private Map<String, Long> taskDistribution;
    }
}