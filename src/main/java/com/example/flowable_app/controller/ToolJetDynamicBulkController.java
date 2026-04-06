package com.example.flowable_app.controller;

import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy; // Use javax.annotation.PreDestroy if on older Spring/Java EE
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/internal/tooljet/dynamic-data")
public class ToolJetDynamicBulkController {

    private final HistoryService historyService;
    private final RestTemplate restTemplate;

    // 🚀 DEDICATED THREAD POOL: Processes 50 tasks at the exact same time without crashing Tomcat
    private final ExecutorService executorService = Executors.newFixedThreadPool(50);

    @Value("${app.backend.url:http://localhost:8080}")
    private String backendUrl;

    public ToolJetDynamicBulkController(HistoryService historyService, RestTemplate restTemplate) {
        this.historyService = historyService;
        this.restTemplate = restTemplate;
    }

    // Gracefully shut down the thread pool when the application stops
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * Accepts a JSON Array of TASK_ID_C strings from ToolJet
     */
    @PostMapping("/bulk-by-task-ids")
    public ResponseEntity<?> getBulkFormData(
            @RequestBody List<String> taskIds,
            @RequestParam(required = false) String processDefinitionKey) {

        if (taskIds == null || taskIds.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        // 🚀 FIRE CONCURRENTLY: Maps each task ID to a background thread
        List<CompletableFuture<Map<String, Object>>> futures = taskIds.stream()
                .map(taskId -> CompletableFuture.supplyAsync(() ->
                        fetchSingleTaskData(taskId, processDefinitionKey), executorService))
                .collect(Collectors.toList());

        // ⏱️ WAIT & COLLECT: Joins all threads together once they finish
        List<Map<String, Object>> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    private Map<String, Object> fetchSingleTaskData(String taskIdC, String processDefinitionKey) {
        Map<String, Object> response = new HashMap<>();
        response.put("TASK_ID_C", taskIdC); // Crucial: Allows ToolJet to match rows

        try {
            // HOP 1: Find Process Instance
            var query = historyService.createHistoricProcessInstanceQuery()
                    .variableValueEquals("TASK_ID_C", taskIdC)
                    .orderByProcessInstanceStartTime().desc();

            if (processDefinitionKey != null && !processDefinitionKey.isEmpty()) {
                query.processDefinitionKey(processDefinitionKey);
            }

            List<HistoricProcessInstance> instances = query.list();
            if (instances.isEmpty()) {
                response.put("ACTION_TAKEN", "PENDING");
                return response;
            }

            String processInstanceId = instances.get(0).getId();
            response.put("PROCESS_INSTANCE_ID", processInstanceId);

            // HOP 2: Fetch Variables
            List<HistoricVariableInstance> variables = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .list();

            String submissionId = null;
            String formKey = null;
            String actionTaken = "PENDING";

            for (HistoricVariableInstance var : variables) {
                String name = var.getVariableName();
                Object val = var.getValue();

                if ("action".equals(name) && val != null) actionTaken = val.toString();
                else if ("formSubmissionId".equals(name) && submissionId == null) submissionId = (String) val;
                else if (("submittedFormKey".equals(name) || "formKey".equals(name)) && formKey == null) formKey = (String) val;
            }

            response.put("ACTION_TAKEN", actionTaken);

            if (submissionId == null || formKey == null) {
                return response;
            }

            // HOP 3: Fetch Form Data via Internal Proxy
            String proxyUrl = backendUrl + "/api/forms/" + formKey + "/submission/" + submissionId;
            Map<String, Object> formPayload = restTemplate.getForObject(proxyUrl, Map.class);

            if (formPayload != null && formPayload.containsKey("data")) {
                Map<String, Object> actualFormData = (Map<String, Object>) formPayload.get("data");

                // 🛠️ FLATTEN: Mirroring your Python logic to prep for ToolJet Data Grids
                Map<String, Object> flatData = new HashMap<>();
                flattenJson(flatData, actualFormData, "");
                response.putAll(flatData);
            }

        } catch (Exception e) {
            response.put("error", e.getMessage());
        }

        return response;
    }

    /**
     * 🛠️ Java implementation of your Python flatten_json method
     */
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