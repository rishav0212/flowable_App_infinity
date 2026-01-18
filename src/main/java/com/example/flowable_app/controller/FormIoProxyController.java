package com.example.flowable_app.controller;

import com.example.flowable_app.client.FormIoClient;
import com.example.flowable_app.service.DataMirrorService;
import com.example.flowable_app.service.FormIoAuthService;
import com.example.flowable_app.service.FormSchemaService;
import com.example.flowable_app.service.SchemaSyncService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/forms")
@Slf4j
public class FormIoProxyController {

    private final FormIoAuthService authService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final SchemaSyncService schemaSyncService;
    private final DataMirrorService dataMirrorService;
    private final FormIoClient formIoClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FormSchemaService formSchemaService;

    // Regex: Matches .../form/{formId}/submission
    private final Pattern SUBMISSION_PATTERN = Pattern.compile(".*/form/([^/]+)/submission");

    @Value("${formio.url}")
    private String formIoUrl;

    public FormIoProxyController(FormIoAuthService authService,
                                 SchemaSyncService schemaSyncService,
                                 DataMirrorService dataMirrorService,
                                 FormIoClient formIoClient,
                                 FormSchemaService formSchemaService) {
        this.authService = authService;
        this.schemaSyncService = schemaSyncService;
        this.dataMirrorService = dataMirrorService;
        this.formIoClient = formIoClient;
        this.formSchemaService = formSchemaService;
    }

    // =================================================================
    // 🟢 1. GENERIC SQL ENDPOINT (For Select Components)
    // URL: /api/forms/sql-data?table=infinity.tbl_clients&city=Delhi
    // =================================================================
    @RequestMapping(value = "/sql-data", method = RequestMethod.GET)
    public ResponseEntity<List<Map<String, Object>>> getSqlData(HttpServletRequest request) {
        String tableName = request.getParameter("table");

        if (tableName == null || tableName.isEmpty()) {
            return ResponseEntity.badRequest().body(null);
        }

        // Convert request params to Map
        Map<String, String> queryParams = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                queryParams.put(key, values[0]);
            }
        });

        log.info("🔍 SQL PROXY: Fetching from [{}] | Params: {}", tableName, queryParams);

        // REUSE: Call the universal fetch method
        List<Map<String, Object>> results = dataMirrorService.fetchTableData(tableName, queryParams);

        return ResponseEntity.ok(results);
    }

    @RequestMapping(value = "/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<Object> proxyRequest(
            @RequestBody(required = false) String body,
            HttpMethod method,
            HttpServletRequest request) {

        String requestPath = request.getRequestURI().substring("/api/forms".length());

        log.info("🌍 PROXY REQUEST: [{}] {}", method, requestPath);

        URI uri = UriComponentsBuilder.fromHttpUrl(formIoUrl + requestPath)
                .query(request.getQueryString())
                .build(true)
                .toUri();

        // =================================================================
        // 🛑 READ INTERCEPT (GET) - REUSING SQL LOGIC
        // =================================================================
        if (method == HttpMethod.GET && request.getRequestURI().endsWith("/submission")) {
            Matcher matcher = SUBMISSION_PATTERN.matcher(request.getRequestURI());
            if (matcher.find()) {
                String formId = matcher.group(1);
                try {
                    Map<String, Object> formDef = formIoClient.getForm(formId);

                    if (hasSqlTag(formDef)) {
                        String formPath = (String) formDef.get("path");
                        Map<String, String> queryParams = new HashMap<>();
                        request.getParameterMap().forEach((key, values) -> {
                            if (values != null && values.length > 0) {
                                queryParams.put(key, values[0]);
                            }
                        });

                        log.info("🏷️ SQL TAG FOUND: Redirecting read for '{}' to SQL Table", formPath);

                        // REUSE: Calling the SAME universal method as /sql-data
                        List<Map<String, Object>> sqlData =
                                dataMirrorService.fetchTableData("tbl_"+formPath, queryParams);

                        log.info("✅ Served {} records from SQL for form '{}'", sqlData.size(), formPath);
                        return ResponseEntity.ok(sqlData);
                    }
                } catch (Exception e) {
                    log.warn("⚠️ READ INTERCEPT FAILED: {}. Fallback to MongoDB.", e.getMessage());
                }
            }
        }

        // 2. PROXY EXECUTION
        String token = authService.getAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-jwt-token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(uri, method, entity, String.class);
            log.info("⬅️ Response from Form.io: {}", response.getStatusCode());

            // =================================================================
            // 🛑 WRITE INTERCEPT (POST/PUT): SQL Mirroring
            // =================================================================
            boolean isSubmission = request.getRequestURI().endsWith("/submission");
            boolean isWrite = (method == HttpMethod.POST || method == HttpMethod.PUT);

            if (isSubmission && isWrite && response.getStatusCode().is2xxSuccessful()) {
                Matcher matcher = SUBMISSION_PATTERN.matcher(request.getRequestURI());
                if (matcher.find()) {
                    String formId = matcher.group(1);
                    String responseBody = response.getBody();

                    // Async Thread
                    new Thread(() -> {
                        try {
                            // A. Check Tags
                            Map<String, Object> formDef = formIoClient.getForm(formId);
                            if (hasSqlTag(formDef)) {
                                String formPath = (String) formDef.get("path");

                                // B. Parse Data
                                Map<String, Object> submission =
                                        objectMapper.readValue(responseBody, new TypeReference<>() {
                                        });
                                Map<String, Object> data = (Map<String, Object>) submission.get("data");
                                String submissionId = (String) submission.get("_id");

                                // C. Get Config
                                JsonNode componentNode = objectMapper.valueToTree(formDef.get("components"));
                                Map<String, Object> buttonProps =
                                        formSchemaService.findSubmitButtonProperties(componentNode);

                                // 🟢 CLEANER: Use the Service Logic!
                                if (buttonProps.containsKey("sqlConfig")) {

                                    // 1. Delegate the complex parsing to the service
                                    List<Map<String, Object>> batchPayload =
                                            formSchemaService.buildBatchPayload(buttonProps, data, submissionId);

                                    // 2. Execute
                                    if (!batchPayload.isEmpty()) {
                                        log.info("🪞 MIRRORING: Batch write to {} targets.", batchPayload.size());
                                        dataMirrorService.mirrorBatch(batchPayload);
                                    }
                                } else {
                                    log.error("❌ MIRROR SKIPPED: Form '{}' has 'sql' tag but MISSING 'sqlConfig'.",
                                            formPath);
                                }
                            }
                        } catch (Exception e) {
                            log.error("❌ ASYNC MIRROR FAILED: {}", e.getMessage());
                        }
                    }).start();
                }
            }

            // Schema Sync
            boolean isFormDef = requestPath.contains("/form") && !requestPath.contains("/submission");
            if (isFormDef && isWrite && response.getStatusCode().is2xxSuccessful() && body != null) {
                new Thread(() -> schemaSyncService.syncFormDefinition(body)).start();
            }

            return cleanResponse(response);

        } catch (HttpClientErrorException.Unauthorized e) {
            authService.invalidateToken();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    private ResponseEntity<Object> cleanResponse(ResponseEntity<String> upstreamResponse) {
        HttpHeaders cleanHeaders = new HttpHeaders();
        cleanHeaders.putAll(upstreamResponse.getHeaders());
        cleanHeaders.remove("Access-Control-Allow-Origin");
        cleanHeaders.remove("Access-Control-Allow-Credentials");
        cleanHeaders.remove("Access-Control-Allow-Methods");
        cleanHeaders.remove("Access-Control-Allow-Headers");
        return new ResponseEntity<>(upstreamResponse.getBody(), cleanHeaders, upstreamResponse.getStatusCode());
    }

    private boolean hasSqlTag(Map<String, Object> formDef) {
        List<String> tags = (List<String>) formDef.get("tags");
        if (tags != null) {
            for (String tag : tags) {
                if (tag.equalsIgnoreCase("sql")) return true;
            }
        }
        return false;
    }
}