package com.example.flowable_app.controller;

import com.example.flowable_app.client.FormIoClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/internal/tooljet/dynamic-data")
public class ToolJetDynamicBulkController {

    private final HistoryService historyService;
    private final FormIoClient formIoClient;
    private final ExecutorService executorService = Executors.newFixedThreadPool(50);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolJetDynamicBulkController(HistoryService historyService, FormIoClient formIoClient) {
        this.historyService = historyService;
        this.formIoClient = formIoClient;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }

    // 🟢 NEW: A simple record to hold both the Task ID and the Process Key for each row
    public record TaskQueryRequest(String taskId, String processKey) {}

    @PostMapping("/bulk-by-task-ids")
    public ResponseEntity<?> getBulkFormData(
            @RequestBody List<TaskQueryRequest> taskRequests) { // 🟢 Accept the new object structure

        if (taskRequests == null || taskRequests.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        // 🟢 Pass the specific processKey for each individual row into the background thread
        List<CompletableFuture<Map<String, Object>>> futures = taskRequests.stream()
                .filter(req -> req.taskId() != null && !req.taskId().trim().isEmpty())
                .map(req -> CompletableFuture.supplyAsync(() ->
                        fetchSingleTaskData(req.taskId(), req.processKey()), executorService))
                .collect(Collectors.toList());

        List<Map<String, Object>> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    private Map<String, Object> fetchSingleTaskData(String taskIdC, String processDefinitionKey) {
        Map<String, Object> response = new HashMap<>();
        response.put("TASK_ID_C", taskIdC);
        response.put("ACTION_TAKEN", "PENDING");

        try {
            // HOP 1: Find Process Instance
            var query = historyService.createHistoricProcessInstanceQuery()
                    .variableValueEquals("TASK_ID_C", taskIdC)
                    .orderByProcessInstanceStartTime().desc();

            // 🟢 Uses the specific key passed from this exact row
            if (processDefinitionKey != null && !processDefinitionKey.trim().isEmpty()) {
                query.processDefinitionKey(processDefinitionKey);
            }

            List<HistoricProcessInstance> instances = query.list();
            if (instances.isEmpty()) {
                return response;
            }

            HistoricProcessInstance processInstance = instances.get(0);
            String processInstanceId = processInstance.getId();
            String tenantId = processInstance.getTenantId();

            response.put("PROCESS_INSTANCE_ID", processInstanceId);

            // HOP 2: Fetch Variables
            List<HistoricVariableInstance> variables = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .list();

            variables.sort((v1, v2) -> {
                Date d1 = v1.getLastUpdatedTime() != null ? v1.getLastUpdatedTime() : v1.getCreateTime();
                Date d2 = v2.getLastUpdatedTime() != null ? v2.getLastUpdatedTime() : v2.getCreateTime();
                if (d1 == null && d2 == null) return 0;
                if (d1 == null) return 1;
                if (d2 == null) return -1;
                return d2.compareTo(d1);
            });

            String submissionId = null;
            String formKey = null;

            for (HistoricVariableInstance var : variables) {
                String name = var.getVariableName();
                Object val = var.getValue();

                if ("action".equals(name) && "PENDING".equals(response.get("ACTION_TAKEN"))) {
                    if (val != null && !val.toString().trim().isEmpty()) {
                        response.put("ACTION_TAKEN", val.toString());
                    }
                } else if ("formSubmissionId".equals(name) && submissionId == null) {
                    submissionId = (String) val;
                } else if (("submittedFormKey".equals(name) || "formKey".equals(name)) && formKey == null) {
                    formKey = (String) val;
                }
            }

            if (submissionId == null || formKey == null) {
                return response;
            }

            // HOP 3: Fetch Form Data via native FormIoClient
            Map<String, Object> formPayload = formIoClient.getSubmission(formKey, submissionId);

            if (formPayload != null && formPayload.containsKey("data")) {
                Map<String, Object> actualFormData = (Map<String, Object>) formPayload.get("data");

                Map<String, Object> flatData = new HashMap<>();
                flattenJson(flatData, actualFormData, "");
                response.putAll(flatData);
            }

        } catch (Exception e) {
            response.put("_error", e.getMessage());
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    private void flattenJson(Map<String, Object> out, Map<String, Object> currentMap, String prefix) {
        for (Map.Entry<String, Object> entry : currentMap.entrySet()) {
            String key = prefix + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                flattenJson(out, (Map<String, Object>) value, key + "_");
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        flattenJson(out, (Map<String, Object>) item, key + "_" + i + "_");
                    } else {
                        out.put(key + "_" + i, item);
                    }
                }
            } else {
                out.put(key, value);
            }
        }
    }
}