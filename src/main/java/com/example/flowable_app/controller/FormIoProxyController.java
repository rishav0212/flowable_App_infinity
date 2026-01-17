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

    @RequestMapping(value = "/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<Object> proxyRequest(
            @RequestBody(required = false) String body,
            HttpMethod method,
            HttpServletRequest request) {

        String requestPath = request.getRequestURI().substring("/api/forms".length());

        // 🔍 DEBUG: Log Entry
        log.info("🌍 PROXY REQUEST: [{}] {}", method, requestPath);

        // 1. Build URI
        URI uri = UriComponentsBuilder.fromHttpUrl(formIoUrl + requestPath)
                .query(request.getQueryString())
                .build(true)
                .toUri();

        // =================================================================
        // 🛑 READ INTERCEPT (GET): Serves data from SQL if 'sql' tag exists
        // =================================================================
        if (method == HttpMethod.GET && request.getRequestURI().endsWith("/submission")) {
            Matcher matcher = SUBMISSION_PATTERN.matcher(request.getRequestURI());
            if (matcher.find()) {
                String formId = matcher.group(1);
                try {
                    log.debug("🔎 Checking tags for Form ID: {}", formId);
                    Map<String, Object> formDef = formIoClient.getForm(formId);

                    if (hasSqlTag(formDef)) {
                        String formPath = (String) formDef.get("path");

                        // Convert Params
                        Map<String, String> queryParams = new HashMap<>();
                        request.getParameterMap().forEach((key, values) -> {
                            if (values != null && values.length > 0) {
                                queryParams.put(key, values[0]);
                            }
                        });

                        log.info("🏷️ SQL TAG FOUND: Intercepting READ for 'tbl_{}' | Params: {}",
                                formPath,
                                queryParams);

                        // Fetch from SQL
                        List<Map<String, Object>>
                                sqlData =
                                dataMirrorService.fetchSubmissionsFromSql(formPath, queryParams);

                        log.info("✅ Served {} records from SQL for form '{}'", sqlData.size(), formPath);
                        return ResponseEntity.ok(sqlData);
                    } else {
                        log.debug("📝 No 'sql' tag found. Proceeding to MongoDB.");
                    }
                } catch (Exception e) {
                    log.warn("⚠️ READ INTERCEPT FAILED: {}. Fallback to MongoDB.", e.getMessage());
                }
            }
        }

        // 2. PROXY EXECUTION (Forward to Form.io / MongoDB)
        String token = authService.getAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-jwt-token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            log.debug("🚀 Forwarding to Form.io: {}", uri);
            ResponseEntity<String> response = restTemplate.exchange(uri, method, entity, String.class);
            log.info("⬅️ Response from Form.io: {}", response.getStatusCode());

            // =================================================================
            // 🛑 WRITE INTERCEPT (POST/PUT): Mirrors to SQL after Mongo success
            // =================================================================
            boolean isSubmission = request.getRequestURI().endsWith("/submission");
            boolean isWrite = (method == HttpMethod.POST || method == HttpMethod.PUT);

            if (isSubmission && isWrite && response.getStatusCode().is2xxSuccessful()) {
                Matcher matcher = SUBMISSION_PATTERN.matcher(request.getRequestURI());
                if (matcher.find()) {
                    String formId = matcher.group(1);
                    String responseBody = response.getBody();

                    // Run Async to keep UI fast
                    new Thread(() -> {
                        try {
                            log.debug("⏳ ASYNC: Checking if new submission needs SQL Mirror...");

                            // A. Check Tags
                            Map<String, Object> formDef = formIoClient.getForm(formId);
                            if (hasSqlTag(formDef)) {
                                String formPath = (String) formDef.get("path");

                                // B. Parse Submission Data
                                Map<String, Object> submission = objectMapper.readValue(responseBody, new TypeReference<>() {});
                                Map<String, Object> data = (Map<String, Object>) submission.get("data");
                                String submissionId = (String) submission.get("_id");

                                // C. Extract Keys (UPDATED LOGIC)
                                JsonNode componentNode = objectMapper.valueToTree(formDef.get("components"));
                                Map<String, Object> buttonProps = formSchemaService.findSubmitButtonProperties(componentNode);

                                Map<String, Object> identifiers = formSchemaService.extractIdentifiers(buttonProps, data);

                                // Fallback: If no keys configured, use Form.io _id -> id
                                if (identifiers.isEmpty()) {
                                    identifiers.put("id", submissionId);
                                    data.put("id", submissionId);
                                }

                                // D. Call Updated Service
                                log.info("🪞 MIRRORING: Writing to 'tbl_{}' | Keys: {}", formPath, identifiers);
                                dataMirrorService.mirrorDataToTable(formPath, identifiers, data);
                            }
                        } catch (Exception e) {
                            log.error("❌ ASYNC MIRROR FAILED: {}", e.getMessage());
                        }
                    }).start();
                }
            }

            // =================================================================
            // 🛑 SCHEMA SYNC INTERCEPT (Form Definition Changes)
            // =================================================================
            boolean isFormDef = requestPath.contains("/form") && !requestPath.contains("/submission");
            if (isFormDef && isWrite && response.getStatusCode().is2xxSuccessful() && body != null) {
                log.info("🛠️ SCHEMA CHANGE DETECTED: Triggering Schema Sync...");
                new Thread(() -> schemaSyncService.syncFormDefinition(body)).start();
            }

            return cleanResponse(response);

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("🚫 TOKEN EXPIRED: Invalidating token and returning 401.");
            authService.invalidateToken();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("💥 PROXY ERROR: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // Helper: Clean Headers
    private ResponseEntity<Object> cleanResponse(ResponseEntity<String> upstreamResponse) {
        HttpHeaders cleanHeaders = new HttpHeaders();
        cleanHeaders.putAll(upstreamResponse.getHeaders());
        cleanHeaders.remove("Access-Control-Allow-Origin");
        cleanHeaders.remove("Access-Control-Allow-Credentials");
        cleanHeaders.remove("Access-Control-Allow-Methods");
        cleanHeaders.remove("Access-Control-Allow-Headers");
        return new ResponseEntity<>(upstreamResponse.getBody(), cleanHeaders, upstreamResponse.getStatusCode());
    }

    // Helper: Check for 'sql' tag
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