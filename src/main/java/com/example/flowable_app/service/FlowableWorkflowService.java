package com.example.flowable_app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 🔒 GENERIC SECURE WORKFLOW HELPER
 * Exposed as "${secureWorkflow}" in BPMN.
 * Write once, use for ANY query logic in the future.
 */
@Service("workflow")
@RequiredArgsConstructor
@Slf4j
public class FlowableWorkflowService {
    private final TaskService taskService;

    private final RuntimeService runtimeService; // 🟢 Inject RuntimeService

    // ... (Your existing getTaskId / queryTaskIds methods) ...

    /**
     * 🟢 FUNCTION 3: START PROCESS INSTANCE
     * Securely starts a new process instance for the current tenant.
     * * @param execution   Context (for Tenant ID)
     * @param processKey  The ID of the process to start (e.g. "dispatchProcess")
     * @param businessKey The unique Order ID (e.g. "ORD-123")
     * @param variables   Form data/Variables
     * @return The new ProcessInstance ID
     */
    public String startProcess(Object executionObj, String processKey, String businessKey, Map<String, Object> variables) {
        // 1. Safety Check & Cast
        if (!(executionObj instanceof DelegateExecution)) {
            log.error("❌ startProcess called with invalid object type: {}", executionObj.getClass());
            return null;
        }
        DelegateExecution execution = (DelegateExecution) executionObj;
        String tenantId = execution.getTenantId();

        log.info("🚀 Starting Process [{}] | Key: {} | Tenant: {}", processKey, businessKey, tenantId);

        try {
            // 2. Build & Start
            ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey(processKey)
                    .businessKey(businessKey)
                    .tenantId(tenantId) // 🔒 Inherit Tenant from Scheduler
                    .variables(variables)
                    .start();

            return instance.getId();

        } catch (Exception e) {
            log.error("❌ Failed to start process: {}", e.getMessage());
            throw new RuntimeException("Workflow Start Error: " + e.getMessage());
        }
    }

    /**
     * 🟢 FUNCTION 1: FIND TASK ID (Native API)
     * Finds a single task using standard Flowable filters.
     * * @param execution   Context (for Tenant ID security)
     *
     * @param processKey  The Process Definition Key (e.g. "order_process")
     * @param taskKey     The Task Definition Key (e.g. "upload_invoice")
     * @param businessKey The Business Key (e.g. "ORD-123")
     * @return The Task ID (String) or null if not found.
     */
    public String getTaskId(DelegateExecution execution, String processKey, String taskKey, String businessKey) {
        String tenantId = execution.getTenantId();

        // 🟢 NATIVE FLOWABLE QUERY
        // This is exactly equivalent to your SQL Join, but handled by the engine.
        Task task = taskService.createTaskQuery()
                .taskTenantId(tenantId)                     // 🔒 Security: Force Tenant
                .processDefinitionKey(processKey)           // Filter by Process Type (e.g. "order_process")
                .taskDefinitionKey(taskKey)                 // Filter by specific Step
                .processInstanceBusinessKey(businessKey)    // Filter by Order Number
                .active()                                   // Only unfinished tasks
                .singleResult();

        if (task != null) {
            return task.getId();
        } else {
            log.warn("⚠️ Native Lookup: No task found for BusinessKey [{}] at step [{}]", businessKey, taskKey);
            return null;
        }
    }

    /**
     * 🟢 FUNCTION 2: COMPLETE TASK
     * Completes the task with the provided variables.
     * * @param taskId    The ID found by Function 1
     *
     * @param variables Map of variables to save (e.g. invoiceUrl, status)
     */
    public void completeTask(String taskId, Map<String, Object> variables) {
        if (taskId == null || taskId.trim().isEmpty()) {
            log.warn("⚠️ Cannot complete task: ID is null or empty.");
            return;
        }

        try {
            taskService.complete(taskId, variables);
            log.info("✅ Task [{}] completed successfully.", taskId);
        } catch (Exception e) {
            log.error("❌ Failed to complete Task [{}]: {}", taskId, e.getMessage());
            throw new RuntimeException("Workflow Completion Error: " + e.getMessage());
        }
    }
}