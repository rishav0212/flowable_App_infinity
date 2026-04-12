package com.example.flowable_app.controller;

import com.example.flowable_app.client.FormIoClient;
import com.example.flowable_app.core.security.UserContextService;
import com.example.flowable_app.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final String PREFIX_SEPARATOR = "--";

    private final FormIoAuthService authService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final SchemaSyncService schemaSyncService;
    private final DataMirrorService dataMirrorService;
    private final FormIoClient formIoClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FormSchemaService formSchemaService;
    private final UserContextService userContextService; // Added to retrieve secure tenant context

    // Regex: Matches .../form/{formId}/submission
    private final Pattern SUBMISSION_PATTERN = Pattern.compile(".*/form/([^/]+)/submission");

    @Value("${formio.url}")
    private String formIoUrl;

    public FormIoProxyController(FormIoAuthService authService,
                                 SchemaSyncService schemaSyncService,
                                 DataMirrorService dataMirrorService,
                                 FormIoClient formIoClient,
                                 FormSchemaService formSchemaService,
                                 UserContextService userContextService) {
        this.authService = authService;
        this.schemaSyncService = schemaSyncService;
        this.dataMirrorService = dataMirrorService;
        this.formIoClient = formIoClient;
        this.formSchemaService = formSchemaService;
        this.userContextService = userContextService;
    }

    // =================================================================
    // 🟢 1. GENERIC SQL ENDPOINT (For Select Components)
    // URL: /api/forms/sql-data?table=tbl_clients&ORDER_NO_C=123
    // =================================================================
    @RequestMapping(value = "/sql-data", method = RequestMethod.GET)
    public ResponseEntity<List<Map<String, Object>>> getSqlData(HttpServletRequest request) {
        String tableName = request.getParameter("table");

        if (tableName == null || tableName.isEmpty()) {
            return ResponseEntity.badRequest().body(null);
        }

        // Convert request params to Map (Exact casing is preserved from the frontend)
        Map<String, String> queryParams = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                queryParams.put(key, values[0]);
            }
        });

        log.info("🔍 SQL PROXY: Fetching from Form/Table [{}] | Params: {}", tableName, queryParams);

        // REUSE: Call the universal fetch method.
        // The DataMirrorService will automatically route to the correct tenant schema
        // and safely quote the parameters to protect uppercase constraints.
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
        String tenantSlug = userContextService.getCurrentTenantSlug();
        String tenantId = userContextService.getCurrentTenantId();

        log.info("🌍 PROXY REQUEST: [{}] {} | Tenant: {}", method, requestPath, tenantSlug);

        // ==============================================================
        // 🛑 TENANT TRANSFORMATION — intercept before forwarding
        // ==============================================================
        String transformedPath = requestPath;
        String transformedBody = body;
        String transformedQuery = request.getQueryString();

        if (tenantSlug != null) {
            // UPDATED: We now pass the HttpMethod so we can properly differentiate
            // between a GET /form (list) and a POST /form (create) request.
            PathType pathType = classifyPath(requestPath, method);

            // We intercept the request data and dynamically modify it to append the tenant prefix.
            // This ensures that Form.io stores tenant data in isolated paths without the frontend knowing.
            switch (pathType) {
                case FORM_LIST:
                    transformedQuery = injectTenantFilter(transformedQuery, tenantId);
                    break;
                case FORM_CREATE:
                case FORM_UPDATE_BY_ID:
                    // UPDATED: This now triggers on both POST (create) and PUT (update by ID).
                    // This fixes the "Invalid alias" bug because the JSON body will always
                    // contain the properly prefixed tenant path.
                    if (body != null) {
                        transformedBody = injectTenantIntoFormBody(body, tenantSlug, tenantId);
                    }
                    break;
                case FORM_SPECIFIC:
                    transformedPath = addPrefixToSpecificPath(requestPath, tenantSlug);
                    break;
                case FORM_SPECIFIC_CHILD:
                    transformedPath = addPrefixToChildPath(requestPath, tenantSlug);
                    break;
                case OTHER:
                    break;
            }
        }

        URI uri = UriComponentsBuilder.fromHttpUrl(formIoUrl + transformedPath)
                .query(transformedQuery)
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

                        // Parameters exact casing preserved from the API request
                        request.getParameterMap().forEach((key, values) -> {
                            if (values != null && values.length > 0) {
                                queryParams.put(key, values[0]);
                            }
                        });

                        log.info("🏷️ SQL TAG FOUND: Redirecting read for form '{}' to SQL Database", formPath);

                        // REUSE: Calling the SAME universal method as /sql-data
                        // DataMirrorService handles auto-tenant schema insertion and case sensitivity.
                        // We strip the tenant prefix from the formPath so it accurately matches the local SQL table name.
                        String strippedPath = stripTenantPrefix(formPath, tenantSlug);
                        List<Map<String, Object>> sqlData =
                                dataMirrorService.fetchTableData("tbl_" + strippedPath, queryParams);

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
        HttpEntity<String> entity = new HttpEntity<>(transformedBody, headers);

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

                                    // 2. Execute Batch via updated Mirror Service
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
                            log.error("❌ ASYNC MIRROR FAILED: {}", e.getMessage(), e);
                        }
                    }).start();
                }
            }

            // Schema Sync
//            boolean isFormDef = requestPath.contains("/form") && !requestPath.contains("/submission");
//            if (isFormDef && isWrite && response.getStatusCode().is2xxSuccessful() && transformedBody != null) {
//                // Create an effectively final copy for the lambda thread
//                final String payloadToSync = transformedBody;
//                new Thread(() -> schemaSyncService.syncFormDefinition(payloadToSync)).start();
//            }

            // ==============================================================
            // 🛑 RESPONSE TRANSFORMATION — strip tenant prefix before returning
            // ==============================================================
// ==============================================================// ==============================================================
            Object finalResponseBody = response.getBody();
            if (response.getBody() != null) {
                try {
                    // Parse into a Jackson Node so Spring Boot properly returns JSON, not a JSON String Literal
                    JsonNode root = objectMapper.readTree(response.getBody());
                    if (tenantSlug != null) {
                        if (root.isArray()) {
                            for (JsonNode node : root) stripPrefixFromNode(node, tenantSlug);
                        } else {
                            stripPrefixFromNode(root, tenantSlug);
                        }
                    }
                    finalResponseBody = root;
                } catch (Exception e) {
                    // Fallback to raw string if it's not valid JSON
                    finalResponseBody = response.getBody();
                }
            }

            return cleanResponse(response, finalResponseBody);


        } catch (HttpClientErrorException.Unauthorized e) {
            authService.invalidateToken();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("❌ PROXY ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // UPDATED: Now accepts an Object body instead of extracting it from upstreamResponse
// UPDATED: Now accepts an Object body instead of extracting it from upstreamResponse
    private ResponseEntity<Object> cleanResponse(ResponseEntity<String> upstreamResponse, Object body) {
        HttpHeaders cleanHeaders = new HttpHeaders();
        if (upstreamResponse != null && upstreamResponse.getHeaders() != null) {
            cleanHeaders.putAll(upstreamResponse.getHeaders());
            cleanHeaders.remove("Access-Control-Allow-Origin");
            cleanHeaders.remove("Access-Control-Allow-Credentials");
            cleanHeaders.remove("Access-Control-Allow-Methods");
            cleanHeaders.remove("Access-Control-Allow-Headers");

            // 🛑 CRITICAL FIX: We modified the size of the JSON body by removing the tenant prefixes.
            // We MUST delete the old Content-Length header so Spring Boot recalculates the new, correct size!
            cleanHeaders.remove("Content-Length");
        }
        HttpStatus status = upstreamResponse != null ? HttpStatus.valueOf(upstreamResponse.getStatusCode().value()) : HttpStatus.OK;
        return new ResponseEntity<>(body, cleanHeaders, status);
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

    // =============================================================================
    // 🔒 TENANT TRANSFORMATION HELPERS
    // =============================================================================

    // UPDATED: Takes HttpMethod to tell POST vs GET apart for the exact same `/form` URL
    private PathType classifyPath(String path, HttpMethod method) {
        String p = path.startsWith("/") ? path.substring(1) : path;

        // /form Endpoint (POST is Creation, GET is List Fetch)
        if (p.equals("form") || p.startsWith("form?")) {
            if (method == HttpMethod.POST) return PathType.FORM_CREATE;
            return PathType.FORM_LIST;
        }

        // /form/{id} Endpoint (PUT is Update)
        if (p.startsWith("form/")) {
            if (method == HttpMethod.PUT && !p.contains("/submission")) return PathType.FORM_UPDATE_BY_ID;
            return PathType.OTHER; // It's likely a submission or other nested resource
        }

        if (p.isEmpty()) return PathType.FORM_CREATE;
        if (p.contains("/")) return PathType.FORM_SPECIFIC_CHILD;
        return PathType.FORM_SPECIFIC;
    }

    private String injectTenantFilter(String existingQuery, String tenantId) {
        // Querying by tag is native, fast, and avoids all Regex encoding bugs!
        String filter = "tags=tenant:" + tenantId;
        if (existingQuery == null || existingQuery.isEmpty()) return filter;
        return existingQuery + "&" + filter;
    }

    private String injectTenantIntoFormBody(String body, String tenantSlug, String tenantId) {
        try {
            ObjectNode formJson = (ObjectNode) objectMapper.readTree(body);

            String originalPath = formJson.has("path") ? formJson.get("path").asText() : "";
            if (!originalPath.startsWith(tenantSlug + PREFIX_SEPARATOR)) {
                formJson.put("path", tenantSlug + PREFIX_SEPARATOR + originalPath);
            }

            // UPDATED: Form.io names reject spaces/brackets. Using 'tenantSlug-' instead of '[tenantSlug] '
            if (formJson.has("name")) {
                String name = formJson.get("name").asText();
                String namePrefix = tenantSlug + "-";
                if (!name.startsWith(namePrefix)) {
                    formJson.put("name", namePrefix + name);
                }
            }

            // UPDATED: Ensure machineName follows the same safe format if it was auto-generated
            if (formJson.has("machineName")) {
                String machineName = formJson.get("machineName").asText();
                String namePrefix = tenantSlug + "-";
                if (!machineName.startsWith(namePrefix)) {
                    formJson.put("machineName", namePrefix + machineName);
                }
            }

            ArrayNode tags = formJson.has("tags") && formJson.get("tags").isArray()
                    ? (ArrayNode) formJson.get("tags")
                    : objectMapper.createArrayNode();

            String tenantTag = "tenant:" + tenantId;
            boolean hasTag = false;
            for (JsonNode t : tags) {
                if (t.asText().equals(tenantTag)) {
                    hasTag = true;
                    break;
                }
            }
            if (!hasTag) tags.add(tenantTag);
            formJson.set("tags", tags);

            return objectMapper.writeValueAsString(formJson);
        } catch (Exception e) {
            log.warn("⚠️ Could not inject tenant into form body: {}", e.getMessage());
            return body;
        }
    }

    private String addPrefixToSpecificPath(String requestPath, String tenantSlug) {
        String clean = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        if (clean.startsWith(tenantSlug + PREFIX_SEPARATOR)) return requestPath;
        return "/" + tenantSlug + PREFIX_SEPARATOR + clean;
    }

    private String addPrefixToChildPath(String requestPath, String tenantSlug) {
        int slashIdx = requestPath.indexOf("/", 1);
        if (slashIdx == -1) return requestPath;
        String formPart = requestPath.substring(1, slashIdx);
        String childPart = requestPath.substring(slashIdx);
        if (formPart.startsWith(tenantSlug + PREFIX_SEPARATOR)) return requestPath;
        return "/" + tenantSlug + PREFIX_SEPARATOR + formPart + childPart;
    }

    private String stripTenantPrefix(String path, String tenantSlug) {
        if (tenantSlug == null || path == null) return path;
        String prefix = tenantSlug + PREFIX_SEPARATOR;
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private String stripTenantPrefixFromResponse(String responseBody, String tenantSlug) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isArray()) {
                for (JsonNode node : root) stripPrefixFromNode(node, tenantSlug);
            } else {
                stripPrefixFromNode(root, tenantSlug);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return responseBody;
        }
    }

    private void stripPrefixFromNode(JsonNode node, String tenantSlug) {
        if (!(node instanceof ObjectNode)) return;
        ObjectNode obj = (ObjectNode) node;

        if (obj.has("path")) {
            String path = obj.get("path").asText();
            obj.put("path", stripTenantPrefix(path, tenantSlug));
        }

        // UPDATED: Strip the safe hyphen-based name prefix
        if (obj.has("name")) {
            String name = obj.get("name").asText();
            String namePrefix = tenantSlug + "-";
            if (name.startsWith(namePrefix)) {
                obj.put("name", name.substring(namePrefix.length()));
            }
        }

        // UPDATED: Strip the safe hyphen-based machineName prefix
        if (obj.has("machineName")) {
            String machineName = obj.get("machineName").asText();
            String namePrefix = tenantSlug + "-";
            if (machineName.startsWith(namePrefix)) {
                obj.put("machineName", machineName.substring(namePrefix.length()));
            }
        }
    }

    // UPDATED: Added FORM_UPDATE_BY_ID to clearly distinguish PUT updates
    private enum PathType {
        FORM_LIST,
        FORM_CREATE,
        FORM_UPDATE_BY_ID,
        FORM_SPECIFIC,
        FORM_SPECIFIC_CHILD,
        OTHER
    }
}