package com.example.flowable_app.controller;

import com.example.flowable_app.client.FormIoClient;
import com.example.flowable_app.dto.TaskRenderDto;
import com.example.flowable_app.dto.TaskSubmitDto;
import com.example.flowable_app.service.DataMirrorService;
import com.example.flowable_app.service.FormSchemaService;
import com.example.flowable_app.service.UserContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 🟢 SECURE TASK OPERATIONS CONTROLLER
 * This controller serves as the secure BFF (Backend-for-Frontend) for all Flowable interactions.
 * It enforces multi-tenancy and user-level authorization to prevent cross-user data leaks
 * within the same tenant and across different tenants.
 */
@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Workflow Operations",
        description = "Core endpoints for managing User Tasks, starting Processes, and retrieving Process History.")
public class TaskOperationController {

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final FormIoClient formIoClient;
    private final ObjectMapper objectMapper;
    private final RepositoryService repositoryService;
    private final DataMirrorService dataMirrorService;
    private final FormSchemaService formSchemaService;
    private final UserContextService userContextService;

    // =========================================================================
    // 🟢 NEW: SECURE INBOX (Replaces fetchTasks in api.ts)
    // =========================================================================
    @Operation(
            summary = "Get User Inbox",
            description =
                    "Fetches all tasks currently assigned to the authenticated user or offered to them as a candidate. " +
                            "Strictly filters by the user's Token ID and Tenant ID."
    )
    @GetMapping("/my-tasks")
    public ResponseEntity<?> getMyTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10000") int size
    ) {

        String userId = userContextService.getCurrentUserId();     // 🔒 Securely fetched from JWT
        String tenantId = userContextService.getCurrentTenantId(); // 🔒 Securely fetched from JWT

        log.info("mailbox: Fetching tasks for user [{}] in tenant [{}]", userId, tenantId);

        // 🔒 SECURITY: We force the filter to the current user and tenant
        List<Task> tasks = taskService.createTaskQuery()
                .taskCandidateOrAssigned(userId)
                .taskTenantId(tenantId)
                .orderByTaskCreateTime().desc()
                .listPage(page * size, size);

//        List<Map<String, Object>> response = tasks.stream().map(task -> {
//            Map<String, Object> map = new HashMap<>();
//            map.put("id", task.getId());
//            map.put("name", task.getName());
//            map.put("assignee", task.getAssignee());
//            map.put("createTime", task.getCreateTime());
//            map.put("dueDate", task.getDueDate());
//            map.put("processDefinitionId", task.getProcessDefinitionId());
//            map.put("processInstanceId", task.getProcessInstanceId());
//            map.put("priority", task.getPriority());
//            return map;
//        }).collect(Collectors.toList());

        return ResponseEntity.ok(tasks);
    }

    // =========================================================================
    // 🟢 NEW: SECURE REASSIGN (Replaces reassignTask in api.ts)
    // =========================================================================
    @Operation(summary = "Reassign a Task",
            description = "Transfers task ownership to another user within the same tenant.")
    @PutMapping("/tasks/{taskId}/assign")
    public ResponseEntity<?> assignTask(
            @PathVariable String taskId,
            @RequestBody Map<String, String> body) {

        String tenantId = userContextService.getCurrentTenantId(); // 🔒
        String newAssignee = body.get("assignee");

        log.info("👮 ASSIGN: Reassigning task [{}] to [{}] (Tenant: {})", taskId, newAssignee, tenantId);

        // 🔒 SECURITY CHECK: Ensure the task belongs to the user's tenant before modifying
        Task task = taskService.createTaskQuery().taskId(taskId).taskTenantId(tenantId).singleResult();
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Task not found or access denied"));
        }

        taskService.setAssignee(taskId, newAssignee);
        return ResponseEntity.ok(Map.of("message", "Task assigned successfully"));
    }

    // =========================================================================
    // 🟢 NEW: SECURE DUE DATE (Replaces updateTaskDueDate in api.ts)
    // =========================================================================
    @Operation(summary = "Update Task Due Date", description = "Updates the deadline for a specific task.")
    @PutMapping("/tasks/{taskId}/due-date")
    public ResponseEntity<?> updateDueDate(
            @PathVariable String taskId,
            @RequestBody Map<String, Date> body) {

        String tenantId = userContextService.getCurrentTenantId(); // 🔒
        Date newDate = body.get("dueDate");

        // 🔒 SECURITY CHECK: Ensure task belongs to tenant
        Task task = taskService.createTaskQuery().taskId(taskId).taskTenantId(tenantId).singleResult();
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Task not found"));
        }

        taskService.setDueDate(taskId, newDate);
        return ResponseEntity.ok(Map.of("message", "Due date updated"));
    }

    // =========================================================================
    // 1. RENDER ENDPOINT (Fully Secure)
    // =========================================================================
    @Operation(
            summary = "Render Task Details & Form Schema",
            description = "Retrieves all necessary data to render a User Task in the frontend. This includes: \n" +
                    "1. **Task Metadata** (ID, name, assignee, priority).\n" +
                    "2. **Process Context** (Business Key, Process Name).\n" +
                    "3. **Form Schema** (Fetched dynamically from Form.io using the task's formKey).\n" +
                    "4. **Current Variables** (Pre-filled data for the form).\n" +
                    "5. **BPMN Extension Elements** (Scans XML for 'externalActions' to configure UI buttons)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved task render data"),
            @ApiResponse(responseCode = "404", description = "Task not found"),
            @ApiResponse(responseCode = "500", description = "Internal error during BPMN parsing or Form fetching")
    })
    @GetMapping("/tasks/{taskId}/render")
    public ResponseEntity<?> renderTask(
            @Parameter(description = "Unique identifier of the Flowable User Task", required = true)
            @PathVariable String taskId) {
        log.info("🔍 RENDER: Fetching details for taskId=[{}]", taskId);

        try {
            String tenantId = userContextService.getCurrentTenantId();
            String userId = userContextService.getCurrentUserId();

            // 🔒 SECURITY: Verify the user is either the Assignee OR a Candidate
            // This prevents "peeking" at tasks assigned to others in the same company.
            Task task = taskService.createTaskQuery()
                    .taskId(taskId)
                    .taskTenantId(tenantId)
                    .taskCandidateOrAssigned(userId) // 🛑 Strict identity check
                    .singleResult();

            if (task == null) {
                log.warn("⚠️ RENDER FAILED: Task [{}] not found or access denied for user [{}]", taskId, userId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Task not found or access denied"));
            }

            Map<String, Object> variables = taskService.getVariables(taskId);

            // 🟢 FETCH EXTERNAL ACTIONS FROM BPMN XML
            try {
                org.flowable.bpmn.model.BpmnModel
                        bpmnModel =
                        repositoryService.getBpmnModel(task.getProcessDefinitionId());
                org.flowable.bpmn.model.Process process = bpmnModel.getMainProcess();
                org.flowable.bpmn.model.FlowElement flowElement = process.getFlowElement(task.getTaskDefinitionKey());

                if (flowElement instanceof org.flowable.bpmn.model.UserTask) {
                    org.flowable.bpmn.model.UserTask userTask = (org.flowable.bpmn.model.UserTask) flowElement;
                    List<org.flowable.bpmn.model.ExtensionElement>
                            props =
                            userTask.getExtensionElements().get("property");

                    if (props != null) {
                        for (org.flowable.bpmn.model.ExtensionElement prop : props) {
                            if ("externalActions".equals(prop.getAttributeValue(null, "name"))) {
                                variables.put("externalActions", prop.getElementText());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("⚠️ BPMN PROPERTY ERROR: {}", e.getMessage());
            }

            Object schema = null;
            try {
                if (task.getFormKey() != null) {
                    schema = formIoClient.getFormSchema(task.getFormKey());
                }
            } catch (Exception e) {
                log.error("❌ FORM.IO ERROR for key=[{}]: {}", task.getFormKey(), e.getMessage());
            }

            String businessKey = null;
            String processName = null;

            if (task.getProcessInstanceId() != null) {
                ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(task.getProcessInstanceId())
                        .processInstanceTenantId(tenantId) // 🔒 Force tenant
                        .singleResult();

                if (processInstance != null) {
                    businessKey = processInstance.getBusinessKey();
                    processName = processInstance.getProcessDefinitionName();
                }
            }

            if (processName == null && task.getProcessDefinitionId() != null) {
                try {
                    processName = repositoryService.getProcessDefinition(task.getProcessDefinitionId()).getName();
                } catch (Exception ignored) {
                }
            }

            return ResponseEntity.ok(TaskRenderDto.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .assignee(task.getAssignee())
                    .description(task.getDescription())
                    .priority(task.getPriority())
                    .businessKey(businessKey)
                    .processName(processName)
                    .createTime(task.getCreateTime())
                    .dueDate(task.getDueDate())
                    .processInstanceId(task.getProcessInstanceId())
                    .data(variables)
                    .formSchema(schema)
                    .build());

        } catch (Exception e) {
            log.error("❌ RENDER EXCEPTION: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to render task", "message", e.getMessage()));
        }
    }

    @Operation(
            summary = "Claim a Task",
            description = "Assigns a specific task to the current authenticated user. " +
                    "Includes strict verification that the user is a valid candidate for the task."
    )
    @PostMapping("/claim-task")
    public ResponseEntity<?> claimTask(
            @Parameter(description = "ID of the task to claim", required = true) @RequestParam String taskId) {

        String userId = userContextService.getCurrentUserId();     // 🔒 Secure identity
        String tenantId = userContextService.getCurrentTenantId(); // 🔒 Secure tenant

        log.info("✋ CLAIM: User [{}] is claiming task [{}]", userId, taskId);
        try {
            // 🔒 SECURITY: Verify task belongs to tenant AND user is a valid candidate
            // This prevents claiming tasks intended for other groups/roles.
            Task task = taskService.createTaskQuery()
                    .taskId(taskId)
                    .taskTenantId(tenantId)
                    .taskCandidateUser(userId) // 🛑 User must be a candidate
                    .singleResult();

            if (task == null) {
                // Check if already assigned
                Task exists = taskService.createTaskQuery().taskId(taskId).taskTenantId(tenantId).singleResult();
                if (exists != null && exists.getAssignee() != null) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Task is already assigned"));
                }
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Task not found or access denied"));
            }

            taskService.claim(taskId, userId);
            log.info("✅ CLAIM SUCCESS: Task [{}] owned by [{}]", taskId, userId);
            return ResponseEntity.ok(Map.of("message", "Task claimed successfully", "taskId", taskId));
        } catch (Exception e) {
            log.error("❌ CLAIM FAILED: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // =========================================================================
    // 2. SUBMIT ENDPOINT (COMPLETE OR UPDATE TASK)
    // =========================================================================
    @Operation(
            summary = "Complete or Save Task",
            description = "Handles form submission for a User Task. Supports two modes:\n" +
                    "1. **Complete (True):** Validates data, finishes the task, and moves the workflow to the next step.\n" +
                    "2. **Draft (False):** Saves the variables to the task but keeps it active for later editing.\n" +
                    "Stores a 'submissionId' reference back to the original Form.io submission."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Task processed successfully (Completed or Saved)"),
            @ApiResponse(responseCode = "422", description = "Process logic failed (e.g. Email error, Script failure)"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @PostMapping("/tasks/{taskId}/submit")
    public ResponseEntity<?> completeTask(
            @PathVariable String taskId,
            @RequestBody TaskSubmitDto payload) {
        log.info("🚀 SUBMIT: Processing task [{}]. CompleteFlag=[{}]", taskId, payload.getCompleteTask());

        try {
            String userId = userContextService.getCurrentUserId();
            String tenantId = userContextService.getCurrentTenantId();

            // 🔒 SECURITY: Strictly verify that the current user is the current Assignee.
            // This prevents User A from submitting User B's task.
            Task task = taskService.createTaskQuery()
                    .taskId(taskId)
                    .taskTenantId(tenantId)
                    .taskAssignee(userId) // 🛑 Strict Ownership Check
                    .singleResult();

            if (task == null) {
                Task exists = taskService.createTaskQuery().taskId(taskId).taskTenantId(tenantId).singleResult();
                if (exists != null) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("error", "Access Denied. You are not the assignee."));
                }
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Task not found"));
            }

            String
                    targetFormKey =
                    (payload.getSubmittedFormKey() != null) ? payload.getSubmittedFormKey() : task.getFormKey();
            FormSchemaService.SubmissionResult
                    result =
                    formSchemaService.processSubmission(targetFormKey, payload.getFormData(), payload.getVariables());

            Map<String, Object> localVars = new HashMap<>();
            localVars.put("formSubmissionId", result.getSubmissionId());
            localVars.put("submittedFormKey", targetFormKey);
            taskService.setVariablesLocal(taskId, localVars);

            if (Boolean.TRUE.equals(payload.getCompleteTask())) {
                taskService.complete(taskId, result.getProcessVariables());
                return ResponseEntity.ok(Map.of("message", "Task Completed", "submissionId", result.getSubmissionId()));
            } else {
                taskService.setVariables(taskId, result.getProcessVariables());
                return ResponseEntity.ok(Map.of("message",
                        "Task Saved (Draft)",
                        "submissionId",
                        result.getSubmissionId()));
            }

        } catch (Exception e) {
            log.error("🛑 ROLLBACK: Task completion failed for [{}]. Reason: {}", taskId, e.getMessage(), e);

            String userMessage = "Process failed.";
            if (e.getMessage() != null && e.getMessage().contains("IO exception")) {
                userMessage = "Network Timeout: The email server did not respond in time. Please try again.";
            } else if (e.getMessage() != null && e.getMessage().contains("Email Error:")) {
                userMessage = e.getMessage();
            } else {
                userMessage = "Error: " + e.getMessage();
            }

            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "Process Execution Failed", "message", userMessage));
        }
    }

    // START PROCESS ENDPOINT
    @Operation(
            summary = "Start New Process Instance",
            description = "Initiates a new workflow process instance based on the definition key. " +
                    "Accepts initial form data, maps it to process variables, and sets the 'initiator' variable."
    )
    @PostMapping("/process/{processDefinitionKey}/start")
    public ResponseEntity<?> startProcess(
            @PathVariable String processDefinitionKey,
            @RequestBody TaskSubmitDto payload) {
        log.info("🏗️ START PROCESS: Initiating instance for definition=[{}]", processDefinitionKey);
        try {
            String tenantId = userContextService.getCurrentTenantId();
            String userId = userContextService.getCurrentUserId();

            FormSchemaService.SubmissionResult
                    result =
                    formSchemaService.processSubmission(payload.getSubmittedFormKey(),
                            payload.getFormData(),
                            payload.getVariables());

            result.getProcessVariables().put("initiator", userId);

            // 🟢 Tenant-Aware Start
            ProcessInstance processInstance = runtimeService.startProcessInstanceByKeyAndTenantId(processDefinitionKey,
                    result.getProcessVariables(),
                    tenantId);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message",
                            "Process Started Successfully",
                            "processInstanceId",
                            processInstance.getId()));

        } catch (Exception e) {
            log.error("❌ START ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start process", "message", e.getMessage()));
        }
    }

    // =========================================================================
    // 3. HISTORY TIMELINE ENDPOINT (Secured)
    // =========================================================================
    @Operation(
            summary = "Get Process History Timeline",
            description = "Fetches the full execution history of a process instance. \n" +
                    "Filters out technical nodes (SequenceFlows, Gateways) to return a clean timeline of User Tasks and Events. " +
                    "Includes 'formSubmissionId' for tasks where a form was submitted."
    )
    @GetMapping("/process/{processInstanceId}/history")
    public ResponseEntity<?> getProcessHistory(@PathVariable String processInstanceId) {
        log.info("🕰️ HISTORY: Fetching timeline for process [{}]", processInstanceId);

        try {
            String tenantId = userContextService.getCurrentTenantId();
            List<HistoricActivityInstance> activities = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .activityTenantId(tenantId) // 🔒 Tenant isolation
                    .orderByHistoricActivityInstanceStartTime()
                    .desc()
                    .list();

            if (activities.isEmpty() &&
                    historyService.createHistoricProcessInstanceQuery()
                            .processInstanceTenantId(tenantId)
                            .processInstanceId(processInstanceId)
                            .singleResult() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Process instance not found"));
            }

            List<Map<String, Object>> timeline = new ArrayList<>();

            for (HistoricActivityInstance act : activities) {
                if ("sequenceFlow".equals(act.getActivityType()) || "exclusiveGateway".equals(act.getActivityType())) {
                    continue;
                }

                Map<String, Object> event = new HashMap<>();
                event.put("type", act.getActivityType());
                event.put("startTime", act.getStartTime());
                event.put("endTime", act.getEndTime());
                event.put("status", act.getEndTime() != null ? "COMPLETED" : "ACTIVE");

                if ("userTask".equals(act.getActivityType()) && act.getTaskId() != null) {
                    HistoricTaskInstance
                            task =
                            historyService.createHistoricTaskInstanceQuery().taskId(act.getTaskId()).singleResult();

                    if (task != null) {
                        event.put("taskId", task.getId());
                        event.put("taskName", task.getName());
                        event.put("formKey", task.getFormKey());
                        event.put("completedBy", task.getAssignee());
                        List<HistoricVariableInstance>
                                taskVariables =
                                historyService.createHistoricVariableInstanceQuery().taskId(act.getTaskId()).list();

                        for (HistoricVariableInstance var : taskVariables) {
                            if ("formSubmissionId".equals(var.getVariableName()))
                                event.put("formSubmissionId", var.getValue());
                            if ("submittedFormKey".equals(var.getVariableName())) event.put("formKey", var.getValue());
                        }
                    }
                } else {
                    String name = act.getActivityName();
                    if (name == null || name.contains("${")) {
                        if ("startEvent".equals(act.getActivityType())) name = "Process Started";
                        else if ("endEvent".equals(act.getActivityType())) name = "Process Finished";
                        else name = act.getActivityType();
                    }
                    event.put("taskName", name);
                }
                timeline.add(event);
            }
            return ResponseEntity.ok(timeline);

        } catch (Exception e) {
            log.error("❌ HISTORY ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch history", "message", e.getMessage()));
        }
    }

    @Operation(
            summary = "Get Process BPMN XML",
            description = "Retrieves the raw BPMN 2.0 XML definition for a specific process instance. " +
                    "Used by the frontend to render the workflow diagram."
    )
    @GetMapping("/process/{processInstanceId}/xml")
    public ResponseEntity<?> getProcessXml(@PathVariable String processInstanceId) {
        log.info("📜 XML REQUEST: Fetching BPMN for process [{}]", processInstanceId);
        try {
            String tenantId = userContextService.getCurrentTenantId();
            HistoricProcessInstance processInstance = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .processInstanceId(processInstanceId)
                    .singleResult();

            if (processInstance == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Process not found"));
            }

            try (InputStream resourceStream = repositoryService.getProcessModel(processInstance.getProcessDefinitionId())) {
                return ResponseEntity.ok(IOUtils.toString(resourceStream, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("❌ XML ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to load XML", "message", e.getMessage()));
        }
    }

    @Operation(
            summary = "Get Diagram Highlights",
            description = "Returns a list of 'completed' and 'active' Activity IDs. " +
                    "Used by the frontend to color-code the BPMN diagram (Green for completed, Red for active)."
    )
    @GetMapping("/process/{processInstanceId}/highlights")
    public ResponseEntity<?> getHighlights(@PathVariable String processInstanceId) {
        log.info("🎨 HIGHLIGHTS: Fetching active/completed nodes for process [{}]", processInstanceId);
        try {
            String tenantId = userContextService.getCurrentTenantId();
            List<String> completedIds = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .activityTenantId(tenantId)
                    .finished()
                    .list()
                    .stream()
                    .map(HistoricActivityInstance::getActivityId)
                    .distinct()
                    .toList();

            List<String> activeIds = runtimeService.createActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .activityTenantId(tenantId)
                    .unfinished()
                    .list()
                    .stream()
                    .map(org.flowable.engine.runtime.ActivityInstance::getActivityId)
                    .distinct()
                    .toList();

            return ResponseEntity.ok(Map.of("completed", completedIds, "active", activeIds));
        } catch (Exception e) {
            log.error("❌ HIGHLIGHTS ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch highlights"));
        }
    }

    // =========================================================================
    // 🟢 SERVER-SIDE BATCH PROCESSING (Secured)
    // =========================================================================
    @Operation(
            summary = "Batch Start Process Instances",
            description = "Accepts a list of variable maps and starts a process instance for each item. \n" +
                    "Executes on the server for high performance. Returns a summary of successes and failures."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Batch processing complete"),
            @ApiResponse(responseCode = "500", description = "Critical server failure")
    })
    @PostMapping("/process/{processDefinitionKey}/batch-start")
    public ResponseEntity<?> batchStartProcess(
            @Parameter(description = "Key of the process to start", required = true)
            @PathVariable String processDefinitionKey,
            @RequestBody List<Map<String, Object>> batchData,
            Authentication authentication
    ) {
        log.info("🚀 BATCH START: Received [{}] items for process [{}]", batchData.size(), processDefinitionKey);

        String tenantId = userContextService.getCurrentTenantId();
        String userId = userContextService.getCurrentUserId();

        int successCount = 0;
        int failCount = 0;
        List<String> logs = new ArrayList<>();

        for (int i = 0; i < batchData.size(); i++) {
            Map<String, Object> variables = batchData.get(i);
            try {
                String businessKey = null;
                if (variables.containsKey("businessKey")) {
                    businessKey = String.valueOf(variables.get("businessKey"));
                    variables.remove("businessKey");
                }

                variables.put("initiator", userId);
                variables.put("batchSource", "api-upload");

                ProcessInstance instance;
                if (businessKey != null && !businessKey.isEmpty()) {
                    instance =
                            runtimeService.startProcessInstanceByKeyAndTenantId(processDefinitionKey,
                                    businessKey,
                                    variables,
                                    tenantId);
                } else {
                    instance =
                            runtimeService.startProcessInstanceByKeyAndTenantId(processDefinitionKey,
                                    variables,
                                    tenantId);
                }

                successCount++;
                if (batchData.size() < 100) {
                    logs.add("✅ Row " + (i + 1) + ": Started (ID: " + instance.getId() + ")");
                }
            } catch (Exception e) {
                failCount++;
                logs.add("❌ Row " + (i + 1) + " Failed: " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("total", batchData.size());
        response.put("success", successCount);
        response.put("failed", failCount);
        response.put("logs", logs);

        log.info("🏁 BATCH COMPLETE: {} Success, {} Failed", successCount, failCount);
        return ResponseEntity.ok(response);
    }
}