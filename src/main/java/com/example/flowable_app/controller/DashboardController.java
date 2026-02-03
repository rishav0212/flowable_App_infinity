package com.example.flowable_app.controller;

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
import org.springframework.security.core.Authentication;
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

    /**
     * 🟢 HELPER: Securely extracts the User ID from the JWT Claims.
     * * Why we use this instead of principal.getName():
     * The default Principal object often returns a string representation of the user map
     * (e.g., "{id=Rishab_J, email=...}") rather than just the ID.
     * This helper casts the Principal back to a Map (populated by JwtAuthenticationFilter)
     * and extracts the clean "id" string to ensure database queries match correctly.
     */
    private String getUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Map)) {
            throw new RuntimeException("Invalid Security Context: Principal is not a Map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = (Map<String, Object>) authentication.getPrincipal();
        return (String) claims.get("id");
    }

    /**
     * Retrieves key workflow statistics for the current user's dashboard.
     * * Logic Update:
     * Switched method signature to accept 'Authentication' instead of 'Principal'
     * to allow access to the raw JWT claims map for accurate ID extraction.
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(Authentication authentication) {
        try {
            // 1. Get the correct ID (e.g., "Rishab_J") using our helper
            String userId = getUserId(authentication);
            log.info("📊 Fetching Dashboard Stats for User ID: [{}]", userId);

            // 2. Fetch Active Tasks
            // We use the extracted userId to query tasks assigned specifically to this user.
            List<Task> activeTasksList = taskService.createTaskQuery()
                    .taskAssignee(userId)
                    .list();

            // 3. Group Tasks by Name (for the "Active Categories" widget)
            // This provides a breakdown of what kinds of tasks are currently pending.
            Map<String, Long> distribution = activeTasksList.stream()
                    .collect(Collectors.groupingBy(
                            task -> task.getName() != null ? task.getName() : "Untitled Task",
                            Collectors.counting()
                    ));

            // 4. Calculate Metrics
            // - High Priority: Tasks with priority > 50 (standard Flowable convention)
            // - Completed: Queries the history tables for finished tasks assigned to this user
            // - Overdue: Checks active tasks where the due date is in the past
            long highPriority = activeTasksList.stream().filter(t -> t.getPriority() > 50).count();

            long completedTotal = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(userId)
                    .finished()
                    .count();

            long overdue = activeTasksList.stream()
                    .filter(t -> t.getDueDate() != null && t.getDueDate().before(new java.util.Date()))
                    .count();

            return ResponseEntity.ok(DashboardStats.builder()
                    .active((long) activeTasksList.size())
                    .highPriority(highPriority)
                    .completed(completedTotal) // This will now correctly show > 0 thanks to the ID fix
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
     * * Logic Update:
     * Now filters by the correct userId extracted from the token.
     * Includes basic search functionality to filter history by task name or description.
     */
    @GetMapping("/completed")
    public ResponseEntity<?> getCompletedTasks(
            Authentication authentication, // Changed from Principal to access raw claims
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search
    ) {
        try {
            // 1. Get the correct ID
            String userId = getUserId(authentication);

            // 2. Build the History Query
            // We only want finished tasks assigned to the current user, sorted by most recent.
            HistoricTaskInstanceQuery query = historyService.createHistoricTaskInstanceQuery()
                    .taskAssignee(userId)
                    .finished()
                    .orderByHistoricTaskInstanceEndTime().desc();

            // 3. Apply Search Filter (if provided)
            // Uses a wildcard match (%) on both name and description.
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim() + "%";
                query.or().taskNameLikeIgnoreCase(pattern).taskDescriptionLikeIgnoreCase(pattern).endOr();
            }

            // 4. Execute Pagination
            long totalRecords = query.count();
            List<HistoricTaskInstance> tasks = query.listPage(page * size, size);

            // 5. Map to Response DTO
            // We simplify the Flowable object to a clean Map for the frontend DataGrid.
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