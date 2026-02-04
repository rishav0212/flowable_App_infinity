package com.example.flowable_app.controller;

import com.example.flowable_app.client.FormIoClient;
import com.example.flowable_app.dto.TaskRenderDto;
import com.example.flowable_app.dto.TaskSubmitDto;
import com.example.flowable_app.service.DataMirrorService;
import com.example.flowable_app.service.FormSchemaService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // =========================================================================
    // 1. RENDER ENDPOINT
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
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null) {
                log.warn("⚠️ RENDER FAILED: Task [{}] not found", taskId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Task not found", "taskId", taskId));
            }

            Map<String, Object> variables = taskService.getVariables(taskId);
            log.debug("📦 Loaded {} variables for task [{}]", variables.size(), taskId);

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
                        log.debug("⚙️ Scanning BPMN extension elements for 'externalActions' on task [{}]", taskId);
                        for (org.flowable.bpmn.model.ExtensionElement prop : props) {
                            if ("externalActions".equals(prop.getAttributeValue(null, "name"))) {
                                String jsonActions = prop.getElementText();
                                variables.put("externalActions", jsonActions);
                                log.info("🔘 BPMN ACTIONS: Injected external actions for task [{}]", taskId);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("⚠️ BPMN PROPERTY ERROR: Could not load extension elements for task [{}]. Error: {}",
                        taskId,
                        e.getMessage());
                // Don't fail the whole render just because buttons failed to load
            }

            Object schema = null;
            try {
                if (task.getFormKey() != null) {
                    log.info("📄 FORM FETCH: Loading Form.io schema for key=[{}]", task.getFormKey());
                    schema = formIoClient.getFormSchema(task.getFormKey());
                } else {
                    log.warn("⚠️ SCHEMA MISSING: No formKey associated with task [{}]", taskId);
                }
            } catch (Exception e) {
                log.error("❌ FORM.IO ERROR: Fetch failed for key=[{}] on task [{}]: {}",
                        task.getFormKey(),
                        taskId,
                        e.getMessage());
                // Continue so the user can at least see the task details, even if the form fails
            }
            // 👇 1. FETCH PROCESS CONTEXT (Business Key & Process Name)
            String businessKey = null;
            String processName = null;

            if (task.getProcessInstanceId() != null) {
                ProcessInstance
                        processInstance =
                        runtimeService.createProcessInstanceQuery()
                                .processInstanceId(task.getProcessInstanceId())
                                .singleResult();

                if (processInstance != null) {
                    businessKey = processInstance.getBusinessKey();
                    processName = processInstance.getProcessDefinitionName();
                }
            }

            // Fallback: If processName is still null (rare), fetch from definition
            if (processName == null && task.getProcessDefinitionId() != null) {
                try {
                    processName = repositoryService.getProcessDefinition(task.getProcessDefinitionId()).getName();
                } catch (Exception e) {
                    log.warn("⚠️ Could not fetch process name for definition [{}]", task.getProcessDefinitionId());
                }
            }
            log.info("✅ RENDER SUCCESS: Returning payload for task [{}], name=[{}]", taskId, task.getName());
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
            description = "Assigns a specific task to the user identified by the 'userId' header. " +
                    "This prevents other users from seeing or working on this task."
    )
    @PostMapping("/claim-task")
    public ResponseEntity<?> claimTask(
            @Parameter(description = "ID of the task to claim", required = true) @RequestParam String taskId,
            @Parameter(description = "ID of the user claiming the task", required = true) @RequestHeader("userId")
            String userId) {
        log.info("✋ CLAIM: User [{}] is claiming task [{}]", userId, taskId);
        try {
            taskService.claim(taskId, userId);
            log.info("✅ CLAIM SUCCESS: Task [{}] owned by [{}]", taskId, userId);
            return ResponseEntity.ok(Map.of("message", "Task claimed successfully", "taskId", taskId));
        } catch (Exception e) {
            log.error("❌ CLAIM FAILED: {}", e.getMessage(), e);
            throw e; // Global Exception Handler will catch standard Flowable exceptions
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
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null) {
                log.warn("⚠️ SUBMIT FAILED: Task [{}] not found", taskId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Task not found"));
            }

            String
                    targetFormKey =
                    (payload.getSubmittedFormKey() != null) ? payload.getSubmittedFormKey() : task.getFormKey();
            log.debug("📝 Processing submission using formKey=[{}]", targetFormKey);

            log.info("🧩 DELEGATING: Calling FormSchemaService for form=[{}]", targetFormKey);
            FormSchemaService.SubmissionResult
                    result =
                    formSchemaService.processSubmission(targetFormKey, payload.getFormData(), payload.getVariables());

            Map<String, Object> localVars = new HashMap<>();
            localVars.put("formSubmissionId", result.getSubmissionId());
            localVars.put("submittedFormKey", targetFormKey);

            log.debug("💾 STORING: Saving history markers to task local variables for ID: [{}]", taskId);
            taskService.setVariablesLocal(taskId, localVars);

            if (Boolean.TRUE.equals(payload.getCompleteTask())) {
                log.info("🏁 ACTION: Completing task [{}] and moving workflow forward", taskId);

                // 🟢 CRITICAL MOMENT: This triggers the Flowable Transaction.
                taskService.complete(taskId, result.getProcessVariables());

                return ResponseEntity.ok(Map.of("message", "Task Completed", "submissionId", result.getSubmissionId()));
            } else {
                log.info("💾 ACTION: Updating task [{}] variables (Draft Mode)", taskId);
                taskService.setVariables(taskId, result.getProcessVariables());

                return ResponseEntity.ok(Map.of("message",
                        "Task Saved (Draft)",
                        "submissionId",
                        result.getSubmissionId()));
            }

        } catch (Exception e) {
            // 🟢 UPDATED ERROR HANDLING (Preserving your logic)
            log.error("🛑 ROLLBACK: Task completion failed for [{}]. Reason: {}", taskId, e.getMessage(), e);

            String userMessage = "Process failed.";

            // 1. Handle Network Timeouts
            if (e.getMessage() != null && e.getMessage().contains("IO exception")) {
                userMessage = "Network Timeout: The email server did not respond in time. Please try again.";
            }
            // 2. Handle Logic Failures (e.g. from Script Task)
            else if (e.getMessage() != null && e.getMessage().contains("Email Error:")) {
                userMessage = e.getMessage();
            }
            // 3. Handle Other Errors
            else {
                userMessage = "Error: " + e.getMessage();
            }

            // Return 422 (Unprocessable Entity) as JSON
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
            FormSchemaService.SubmissionResult
                    result =
                    formSchemaService.processSubmission(payload.getSubmittedFormKey(),
                            payload.getFormData(),
                            payload.getVariables());

            log.info("🌟 STARTING: Mapping initial variables and creating process instance...");
            result.getProcessVariables().put("initiator", "user");

            ProcessInstance
                    processInstance =
                    runtimeService.startProcessInstanceByKey(processDefinitionKey, result.getProcessVariables());

            log.info("✅ START SUCCESS: Instance created with ID=[{}]", processInstance.getId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message",
                            "Process Started Successfully",
                            "processInstanceId",
                            processInstance.getId()));

        } catch (Exception e) {
            log.error("❌ START ERROR: Failed to initiate process [{}]: {}", processDefinitionKey, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start process", "message", e.getMessage()));
        }
    }

    // =========================================================================
    // 3. HISTORY TIMELINE ENDPOINT
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
            List<HistoricActivityInstance>
                    activities =
                    historyService.createHistoricActivityInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .orderByHistoricActivityInstanceStartTime()
                            .desc()
                            .list();

            if (activities.isEmpty() &&
                    historyService.createHistoricProcessInstanceQuery()
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
                            if ("formSubmissionId".equals(var.getVariableName())) {
                                event.put("formSubmissionId", var.getValue());
                            }
                            if ("submittedFormKey".equals(var.getVariableName())) {
                                event.put("formKey", var.getValue());
                            }
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
            HistoricProcessInstance
                    processInstance =
                    historyService.createHistoricProcessInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .singleResult();

            if (processInstance == null) {
                log.warn("⚠️ XML FAILED: Process instance [{}] not found", processInstanceId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Process not found"));
            }

            try (InputStream resourceStream = repositoryService.getProcessModel(processInstance.getProcessDefinitionId())) {
                log.info("✅ XML SUCCESS: Returning BPMN model for process [{}]", processInstanceId);
                return ResponseEntity.ok(IOUtils.toString(resourceStream, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("❌ XML ERROR: Failed to load model for instance [{}]: {}", processInstanceId, e.getMessage(), e);
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
            List<String>
                    completedIds =
                    historyService.createHistoricActivityInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .finished()
                            .list()
                            .stream()
                            .map(HistoricActivityInstance::getActivityId)
                            .distinct()
                            .toList();

            List<String>
                    activeIds =
                    runtimeService.createActivityInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .unfinished()
                            .list()
                            .stream()
                            .map(org.flowable.engine.runtime.ActivityInstance::getActivityId)
                            .distinct()
                            .toList();

            log.info("📤 HIGHLIGHTS SUCCESS: CompletedNodes={}, ActiveNodes={}", completedIds.size(), activeIds.size());
            return ResponseEntity.ok(Map.of("completed", completedIds, "active", activeIds));
        } catch (Exception e) {
            log.error("❌ HIGHLIGHTS ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch highlights"));
        }
    }

    // =========================================================================
    // 🟢 NEW: SERVER-SIDE BATCH PROCESSING
    // =========================================================================

    @Operation(
            summary = "Batch Start Process Instances",
            description = "Accepts a list of variable maps and starts a process instance for each item. \n" +
                    "Executes on the server for high performance. Returns a summary of successes and failures."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Batch processing complete (check body for individual errors)"),
            @ApiResponse(responseCode = "500", description = "Critical server failure")
    })
    @PostMapping("/process/{processDefinitionKey}/batch-start")
    public ResponseEntity<?> batchStartProcess(
            @Parameter(description = "Key of the process to start (e.g. 'hiringProcess')", required = true)
            @PathVariable String processDefinitionKey,
            @RequestBody List<Map<String, Object>> batchData,
            Authentication authentication
    ) {
        log.info("🚀 BATCH START: Received [{}] items for process [{}]", batchData.size(), processDefinitionKey);

        // 1. Extract User ID safely to set as 'initiator'
        String userId = "batch-runner";
        if (authentication != null && authentication.getPrincipal() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = (Map<String, Object>) authentication.getPrincipal();
            userId = (String) claims.get("id");
        }

        int successCount = 0;
        int failCount = 0;
        List<String> logs = new ArrayList<>();

        // 2. Server-Side Loop
        for (int i = 0; i < batchData.size(); i++) {
            Map<String, Object> variables = batchData.get(i);
            try {
                // 1. Extract Business Key if present (and remove from vars to avoid duplication)
                String businessKey = null;
                if (variables.containsKey("businessKey")) {

                    businessKey = String.valueOf(variables.get("businessKey"));
                    variables.remove("businessKey");
                }

                // 2. Set Metadata
                variables.put("initiator", userId);
                variables.put("batchSource", "api-upload");

                // 3. Start Instance (Handle both cases)
                ProcessInstance instance;
                if (businessKey != null && !businessKey.isEmpty()) {
                    // 🟢 Start WITH Business Key
                    instance = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
                } else {
                    // ⚪ Start WITHOUT Business Key
                    instance = runtimeService.startProcessInstanceByKey(processDefinitionKey, variables);
                }

                successCount++;                // Optional: Reduce log noise for large batches
                if (batchData.size() < 100) {
                    logs.add("✅ Row " + (i + 1) + ": Started (ID: " + instance.getId() + ")");
                }

            } catch (Exception e) {
                failCount++;
                log.error("❌ BATCH ROW [{}] FAILED: {}", i + 1, e.getMessage());
                logs.add("❌ Row " + (i + 1) + " Failed: " + e.getMessage());
            }
        }

        // 3. Construct Summary Response
        Map<String, Object> response = new HashMap<>();
        response.put("total", batchData.size());
        response.put("success", successCount);
        response.put("failed", failCount);
        response.put("logs", logs);

        log.info("🏁 BATCH COMPLETE: {} Success, {} Failed", successCount, failCount);

        return ResponseEntity.ok(response);
    }
}