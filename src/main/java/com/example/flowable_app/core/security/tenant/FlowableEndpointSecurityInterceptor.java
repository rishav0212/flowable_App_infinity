package com.example.flowable_app.core.security.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDOR Protection for Standard Flowable APIs.
 * Prevents Tenant A from accessing Tenant B's specific resources by ID.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowableEndpointSecurityInterceptor implements HandlerInterceptor {

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    // Regex to detect "Single Resource" endpoints
    private static final Pattern TASK_PATTERN = Pattern.compile("/process-api/runtime/tasks/([^/]+).*");
    private static final Pattern PROCESS_PATTERN = Pattern.compile("/process-api/runtime/process-instances/([^/]+).*");
    private static final Pattern HISTORIC_PROCESS_PATTERN = Pattern.compile("/process-api/history/historic-process-instances/([^/]+).*");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        // 1. Only target Flowable APIs
        if (!path.startsWith("/process-api/")) {
            return true;
        }

        // 2. Extract Tenant from JWT
        String userTenantId = getCurrentTenantId();
        if (userTenantId == null) {
            // If no tenant is present (e.g. admin or system), maybe allow?
            // For safety, we block. Adjust if you have "Super Admin" logic.
            log.warn("⛔ BLOCKED: Request to [{}] with no tenant context.", path);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant context missing");
            return false;
        }

        // 3. Check Task Access
        Matcher taskMatcher = TASK_PATTERN.matcher(path);
        if (taskMatcher.matches()) {
            String taskId = taskMatcher.group(1);
            if (!isTaskOwner(taskId, userTenantId)) {
                log.warn("⛔ IDOR BLOCKED: Tenant [{}] tried to access Task [{}] belonging to another tenant.", userTenantId, taskId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND); // 404 is safer than 403 (don't reveal existence)
                return false;
            }
        }

        // 4. Check Process Instance Access
        Matcher processMatcher = PROCESS_PATTERN.matcher(path);
        if (processMatcher.matches()) {
            String processId = processMatcher.group(1);
            if (!isProcessOwner(processId, userTenantId)) {
                log.warn("⛔ IDOR BLOCKED: Tenant [{}] tried to access Process [{}]", userTenantId, processId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return false;
            }
        }

        // 5. Check Historic Process Access
        Matcher histProcessMatcher = HISTORIC_PROCESS_PATTERN.matcher(path);
        if (histProcessMatcher.matches()) {
            String processId = histProcessMatcher.group(1);
            if (!isHistoricProcessOwner(processId, userTenantId)) {
                log.warn("⛔ IDOR BLOCKED: Tenant [{}] tried to access History [{}]", userTenantId, processId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return false;
            }
        }

        return true;
    }

    private boolean isTaskOwner(String taskId, String userTenantId) {
        // Fetch specific task securely
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) return true; // Let the controller handle 404
        return userTenantId.equals(task.getTenantId());
    }

    private boolean isProcessOwner(String processId, String userTenantId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(processId).singleResult();
        if (pi == null) return true;
        return userTenantId.equals(pi.getTenantId());
    }

    private boolean isHistoricProcessOwner(String processId, String userTenantId) {
        HistoricProcessInstance pi = historyService.createHistoricProcessInstanceQuery().processInstanceId(processId).singleResult();
        if (pi == null) return true;
        return userTenantId.equals(pi.getTenantId());
    }

    private String getCurrentTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) auth.getPrincipal();
            return (String) details.get("tenantId");
        }
        return null;
    }
}