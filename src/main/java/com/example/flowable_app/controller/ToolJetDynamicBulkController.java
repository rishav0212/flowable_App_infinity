package com.example.flowable_app.controller;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/internal/tooljet/dynamic-data")
public class ToolJetDynamicBulkController {

    private final HistoryService historyService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executorService = Executors.newFixedThreadPool(50);

    @Value("${app.backend.url:http://localhost:8080}")
    private String backendUrl;

    public ToolJetDynamicBulkController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }

    @PostMapping("/bulk-by-task-ids")
    public ResponseEntity<?> getBulkFormData(
            @RequestBody List<String> taskIds,
            @RequestParam(required = false) String processDefinitionKey,
            HttpServletRequest request) {

        if (taskIds == null || taskIds.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        // 🟢 BUG FIX 1: Extract Auth headers from the MAIN thread.
        // Spring Security Context is lost when passed to the background ExecutorService.
        // We MUST extract the tokens here and pass them as raw strings to the threads.
        final String authHeader = request.getHeader("Authorization");
        final String cookieHeader = request.getHeader("Cookie");

        List<CompletableFuture<Map<String, Object>>> futures = taskIds.stream()
                .map(taskId -> CompletableFuture.supplyAsync(() ->
                        fetchSingleTaskData(taskId, processDefinitionKey, authHeader, cookieHeader), executorService))
                .collect(Collectors.toList());

        List<Map<String, Object>> results = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return ResponseEntity.ok(results);
    }

    private Map<String, Object> fetchSingleTaskData(String taskIdC, String processDefinitionKey, String authHeader, String cookieHeader) {
        Map<String, Object> response = new HashMap<>();
        response.put("TASK_ID_C", taskIdC);
        response.put("ACTION_TAKEN", "PENDING"); // Initialize early just like Python

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
                return response;
            }

            String processInstanceId = instances.get(0).getId();
            response.put("PROCESS_INSTANCE_ID", processInstanceId);

            // HOP 2: Fetch Variables
            List<HistoricVariableInstance> variables = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .list();

            // 🟢 BUG FIX 2: Replicate Python's exact sorting logic (Newest Updates First)
            variables.sort((v1, v2) -> {
                Date d1 = v1.getLastUpdatedTime() != null ? v1.getLastUpdatedTime() : v1.getCreateTime();
                Date d2 = v2.getLastUpdatedTime() != null ? v2.getLastUpdatedTime() : v2.getCreateTime();
                if (d1 == null && d2 == null) return 0;
                if (d1 == null) return 1;
                if (d2 == null) return -1;
                return d2.compareTo(d1); // Reverse sort (descending)
            });

            String submissionId = null;
            String formKey = null;

            for (HistoricVariableInstance var : variables) {
                String name = var.getVariableName();
                Object val = var.getValue();

                // 🟢 BUG FIX 3: Replicate Python's exact conditional assignment logic
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

            // HOP 3: Fetch Form Data via Internal Proxy
            String proxyUrl = backendUrl + "/api/forms/" + formKey + "/submission/" + submissionId;

            // 🟢 BUG FIX 4: Inject Authorization into RestTemplate
            // Without this, the proxy was returning 401 Unauthorized silently
            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            if (cookieHeader != null) headers.set("Cookie", cookieHeader);
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> proxyRes = restTemplate.exchange(proxyUrl, HttpMethod.GET, entity, Map.class);

            if (proxyRes.getStatusCode().is2xxSuccessful() && proxyRes.getBody() != null) {
                Map<String, Object> formPayload = proxyRes.getBody();
                if (formPayload.containsKey("data")) {
                    Map<String, Object> actualFormData = (Map<String, Object>) formPayload.get("data");

                    Map<String, Object> flatData = new HashMap<>();
                    flattenJson(flatData, actualFormData, "");
                    response.putAll(flatData);
                }
            }

        } catch (Exception e) {
            response.put("_error", e.getMessage()); // Named _error so it doesn't overwrite business data
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