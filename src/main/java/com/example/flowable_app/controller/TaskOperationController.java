package com.example.flowable_app.controller;

import com.example.flowable_app.client.FormIoClient;
import com.example.flowable_app.dto.TaskRenderDto;
import com.example.flowable_app.dto.TaskSubmitDto;
import com.example.flowable_app.service.DataMirrorService;
import com.example.flowable_app.service.FormSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @GetMapping("/tasks/{taskId}/render")
    public ResponseEntity<?> renderTask(@PathVariable String taskId) {
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
                        taskId, e.getMessage());
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
                        task.getFormKey(), taskId, e.getMessage());
                // Continue so the user can at least see the task details, even if the form fails
            }

            log.info("✅ RENDER SUCCESS: Returning payload for task [{}], name=[{}]", taskId, task.getName());
            return ResponseEntity.ok(TaskRenderDto.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .assignee(task.getAssignee())
                    .description(task.getDescription())
                    .priority(task.getPriority())
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

    @PostMapping("/claim-task")
    public ResponseEntity<?> claimTask(@RequestParam String taskId, @RequestHeader("userId") String userId) {
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
    @PostMapping("/tasks/{taskId}/submit")
    public ResponseEntity<?> completeTask(@PathVariable String taskId, @RequestBody TaskSubmitDto payload) {
        log.info("🚀 SUBMIT: Processing task [{}]. CompleteFlag=[{}]", taskId, payload.getCompleteTask());

        try {
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null) {
                log.warn("⚠️ SUBMIT FAILED: Task [{}] not found", taskId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Task not found"));
            }

            String
                    targetFormKey =
                    (payload.getSubmittedFormKey() != null) ? payload.getSubmittedFormKey() : task.getFormKey();
            log.debug("📝 Processing submission using formKey=[{}]", targetFormKey);

            log.info("🧩 DELEGATING: Calling FormSchemaService for form=[{}]", targetFormKey);
            FormSchemaService.SubmissionResult result = formSchemaService.processSubmission(
                    targetFormKey,
                    payload.getFormData(),
                    payload.getVariables()
            );

            Map<String, Object> localVars = new HashMap<>();
            localVars.put("formSubmissionId", result.getSubmissionId());
            localVars.put("submittedFormKey", targetFormKey);

            log.debug("💾 STORING: Saving history markers to task local variables for ID: [{}]", taskId);
            taskService.setVariablesLocal(taskId, localVars);

            if (Boolean.TRUE.equals(payload.getCompleteTask())) {
                log.info("🏁 ACTION: Completing task [{}] and moving workflow forward", taskId);

                // 🟢 CRITICAL MOMENT: This triggers the Flowable Transaction.
                taskService.complete(taskId, result.getProcessVariables());

                return ResponseEntity.ok(Map.of(
                        "message", "Task Completed",
                        "submissionId", result.getSubmissionId()
                ));
            } else {
                log.info("💾 ACTION: Updating task [{}] variables (Draft Mode)", taskId);
                taskService.setVariables(taskId, result.getProcessVariables());

                return ResponseEntity.ok(Map.of(
                        "message", "Task Saved (Draft)",
                        "submissionId", result.getSubmissionId()
                ));
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
    @PostMapping("/process/{processDefinitionKey}/start")
    public ResponseEntity<?> startProcess(
            @PathVariable String processDefinitionKey, @RequestBody TaskSubmitDto payload) {
        log.info("🏗️ START PROCESS: Initiating instance for definition=[{}]", processDefinitionKey);
        try {
            FormSchemaService.SubmissionResult result = formSchemaService.processSubmission(
                    payload.getSubmittedFormKey(),
                    payload.getFormData(),
                    payload.getVariables()
            );

            log.info("🌟 STARTING: Mapping initial variables and creating process instance...");
            result.getProcessVariables().put("initiator", "user");

            ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(
                    processDefinitionKey,
                    result.getProcessVariables()
            );

            log.info("✅ START SUCCESS: Instance created with ID=[{}]", processInstance.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "message", "Process Started Successfully",
                    "processInstanceId", processInstance.getId()
            ));

        } catch (Exception e) {
            log.error("❌ START ERROR: Failed to initiate process [{}]: {}", processDefinitionKey, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start process", "message", e.getMessage()));
        }
    }

    // =========================================================================
    // 3. HISTORY TIMELINE ENDPOINT
    // =========================================================================
    @GetMapping("/process/{processInstanceId}/history")
    public ResponseEntity<?> getProcessHistory(@PathVariable String processInstanceId) {
        log.info("🕰️ HISTORY: Fetching timeline for process [{}]", processInstanceId);

        try {
            List<HistoricActivityInstance> activities = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .orderByHistoricActivityInstanceStartTime().desc()
                    .list();

            if (activities.isEmpty() &&
                    historyService.createHistoricProcessInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .singleResult() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Process instance not found"));
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
                    HistoricTaskInstance task = historyService.createHistoricTaskInstanceQuery()
                            .taskId(act.getTaskId())
                            .singleResult();

                    if (task != null) {
                        event.put("taskId", task.getId());
                        event.put("taskName", task.getName());
                        event.put("formKey", task.getFormKey());

                        List<HistoricVariableInstance>
                                taskVariables =
                                historyService.createHistoricVariableInstanceQuery()
                                        .taskId(act.getTaskId())
                                        .list();

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

    @GetMapping("/process/{processInstanceId}/xml")
    public ResponseEntity<?> getProcessXml(@PathVariable String processInstanceId) {
        log.info("📜 XML REQUEST: Fetching BPMN for process [{}]", processInstanceId);
        try {
            HistoricProcessInstance processInstance = historyService.createHistoricProcessInstanceQuery()
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

    @GetMapping("/process/{processInstanceId}/highlights")
    public ResponseEntity<?> getHighlights(@PathVariable String processInstanceId) {
        log.info("🎨 HIGHLIGHTS: Fetching active/completed nodes for process [{}]", processInstanceId);
        try {
            List<String> completedIds = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .finished()
                    .list()
                    .stream()
                    .map(HistoricActivityInstance::getActivityId)
                    .distinct()
                    .toList();

            List<String> activeIds = runtimeService.createActivityInstanceQuery()
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
}