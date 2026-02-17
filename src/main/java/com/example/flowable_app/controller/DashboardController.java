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

import java.util.Date;
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
    private final UserContextService userContextService; // Handles secure extraction of User ID and Tenant ID

    /**
     * Retrieves key workflow statistics for the current user's dashboard.
     * * Implementation Details:
     * - Uses UserContextService to enforce Multi-tenancy (Tenant ID isolation).
     * - Aggregates data for: Active Tasks, High Priority items (>50), Completed count, and Overdue items.
     * - Returns a specific DTO (DashboardStats) for type safety in the frontend.
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            // 1. Securely fetch Context (User and Tenant)
            // We use the service instead of manual token parsing to ensure we get the Tenant ID
            // required for multi-tenant data isolation in PostgreSQL.
            String userId = userContextService.getCurrentUserId();
            String tenantId = userContextService.getCurrentTenantId();

            log.info("📊 Fetching Dashboard Stats for User: [{}] Tenant: [{}]", userId, tenantId);

            // 2. Fetch Active Tasks (Scoped to User AND Tenant)
            // We fetch the list once to perform in-memory aggregations (priority, overdue)
            // rather than hitting the DB multiple times for active task metrics.
            List<Task> activeTasksList = taskService.createTaskQuery()
                    .taskAssignee(userId)
                    .taskTenantId(tenantId) // Critical for multi-tenancy
                    .list();

            // 3. Group Tasks by Name
            // Useful for "Task Distribution" charts. Groups by task name (e.g., "Approve Request": 5).
            Map<String, Long> distribution = activeTasksList.stream()
                    .collect(Collectors.groupingBy(
                            task -> task.getName() != null ? task.getName() : "Untitled Task",
                            Collectors.counting()
                    ));

            // 4. Calculate Metrics
            // High Priority: Flowable standard is priority > 50.
            long highPriority = activeTasksList.stream()
                    .filter(t -> t.getPriority() > 50)
                    .count();

            // Completed Total: Must query HistoryService as these are no longer in the runtime Task table.
            // Also scoped by Tenant ID.
            long completedTotal = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(userId)
                    .taskTenantId(tenantId)
                    .finished()
                    .count();

            // Overdue: Checks if the Due Date is strictly before the current time.
            long overdue = activeTasksList.stream()
                    .filter(t -> t.getDueDate() != null && t.getDueDate().before(new Date()))
                    .count();

            // 5. Build and Return Response
            return ResponseEntity.ok(DashboardStats.builder()
                    .active((long) activeTasksList.size())
                    .highPriority(highPriority)
                    .completed(completedTotal)
                    .overdue(overdue)
                    .taskDistribution(distribution)
                    .build());

        } catch (Exception e) {
            log.error("❌ DASHBOARD STATS ERROR: {}", e.getMessage(), e);
            // Return a structured error map so the UI can handle it gracefully without crashing
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch stats"));
        }
    }

    /**
     * Fetches a paginated list of completed tasks for the history table.
     * * Implementation Details:
     * - Supports server-side pagination (page, size) to optimize PostgreSQL performance.
     * - Supports filtering by a search string (matches Name or Description).
     * - Ensures results are restricted to the current Tenant.
     *
     * @param page   Zero-based page index.
     * @param size   Number of records per page.
     * @param search Optional search term.
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
            // We prioritize finished tasks and sort by end time (newest first).
            HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(userId)
                    .taskTenantId(tenantId) // Critical for multi-tenancy
                    .finished()
                    .orderByHistoricTaskInstanceEndTime().desc();

            // 3. Apply Search Filter
            // Logic preserved: checks if search string exists, then applies LIKE query on Name OR Description.
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim() + "%";
                query.or()
                        .taskNameLikeIgnoreCase(pattern)
                        .taskDescriptionLikeIgnoreCase(pattern)
                        .endOr();
            }

            // 4. Execute Pagination
            // We count total records first to calculate total pages for the frontend pagination UI.
            long totalRecords = query.count();
            List<HistoricTaskInstance> tasks = query.listPage(page * size, size);

            // 5. Map to Response
            // We transform the complex HistoricTaskInstance object into a simple Map
            // to reduce payload size and expose only necessary fields to the UI.
            List<Map<String, Object>> content = tasks.stream().map(t -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", t.getId());
                map.put("name", t.getName());
                map.put("description", t.getDescription());
                map.put("endTime", t.getEndTime());
                return map;
            }).collect(Collectors.toList());

            // 6. Construct Final Response Object
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

    /**
     * DTO for Dashboard Statistics.
     * Using a static inner class keeps the contract close to the controller.
     */
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