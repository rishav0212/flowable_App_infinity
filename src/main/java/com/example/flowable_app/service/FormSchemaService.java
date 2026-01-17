package com.example.flowable_app.service;

import com.example.flowable_app.client.FormIoClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FormSchemaService {

    private final ObjectMapper objectMapper;
    private final FormIoClient formIoClient;
    private final DataMirrorService dataMirrorService;

    public SubmissionResult processSubmission(String targetFormKey, Map<String, Object> inputData, Map<String, Object> frontendVariables) {
        log.info("🏁 Processing submission for form: [{}]", targetFormKey);

        // 1. FETCH SCHEMA
        Map<String, Object> formSchema = formIoClient.getFormSchema(targetFormKey);

        // 2. PREPARE DATA
        Map<String, Object> userFormData = new HashMap<>();
        if (inputData != null) userFormData.putAll(inputData);

        // 3. SUBMIT TO FORM.IO
        String submissionId;
        try {
            Map<String, Object> formioRequest = new HashMap<>();
            formioRequest.put("data", userFormData);
            Map<String, Object> response = formIoClient.submitForm(targetFormKey, formioRequest);
            submissionId = (String) response.get("_id");
            log.info("✅ Form.io submission successful. ID: {}", submissionId);
        } catch (Exception e) {
            log.error("❌ Form.io Save Failed: {}", e.getMessage());
            throw new RuntimeException("Form.io Save Failed", e);
        }

        // 4. MIRROR TO SQL (If "sql" tag exists)
// ... inside processSubmission method ...

        // 4. MIRROR TO SQL (If "sql" tag exists)
        if (hasSqlTag(formSchema)) {
            log.info("🏷️ SQL tag detected. Starting SQL mirroring process...");

            JsonNode componentNode = objectMapper.valueToTree(formSchema.get("components"));
            Map<String, Object> buttonProps = findSubmitButtonProperties(componentNode);

            String targetTable = (String) buttonProps.get("targetTable");

            if (targetTable != null) {
                // 🟢 NEW LOGIC: Build the Identifiers Map
                Map<String, Object> identifiers = extractIdentifiers(buttonProps, userFormData);

                try {
                    // 🟢 CALL UPDATED SERVICE
                    dataMirrorService.mirrorDataToTable(targetTable, identifiers, userFormData);
                    log.info("🪞 SQL Mirroring completed for table [{}]", targetTable);
                } catch (Exception e) {
                    log.error("❌ SQL Mirroring failed: {}", e.getMessage());
                }
            } else {
                log.warn("⚠️ SQL Tag found, but no 'targetTable' property found.");
            }
        } else {
            log.debug("ℹ️ No SQL tag found for form [{}]. Skipping mirror.", targetFormKey);
        }

        // 5. PREPARE VARIABLES
        log.info("⚙️ Extracting process variables...");
        Map<String, Object> processVariables = extractProcessVariables(formSchema, userFormData);

        if (frontendVariables != null) {
            processVariables.putAll(frontendVariables);
            log.info("➕ Added {} frontend variables to process context", frontendVariables.size());
        }

        processVariables.put("formSubmissionId", submissionId);
        processVariables.put("submittedFormKey", targetFormKey);

        log.info("🎯 Process submission finished for form [{}]. Variables count: {}",
                targetFormKey,
                processVariables.size());
        return SubmissionResult.builder()
                .submissionId(submissionId)
                .processVariables(processVariables)
                .build();
    }

    public Map<String, Object> findSubmitButtonProperties(JsonNode components) {
        if (components == null || !components.isArray()) return new HashMap<>();
        for (JsonNode comp : components) {
            if (comp.has("key") && "submit".equalsIgnoreCase(comp.get("key").asText())) {
                if (comp.has("properties")) {
                    log.debug("🔘 Found 'submit' button properties");
                    return objectMapper.convertValue(comp.get("properties"), Map.class);
                }
            }
            if (comp.has("components")) {
                Map<String, Object> found = findSubmitButtonProperties(comp.get("components"));
                if (!found.isEmpty()) return found;
            }
            if (comp.has("columns")) {
                for (JsonNode col : comp.get("columns")) {
                    if (col.has("components")) {
                        Map<String, Object> found = findSubmitButtonProperties(col.get("components"));
                        if (!found.isEmpty()) return found;
                    }
                }
            }
        }
        return new HashMap<>();
    }

    // Helper methods remain exactly as provided in your prompt,
    // but ensured they align with the logic above.

    public Map<String, Object> extractIdentifiers(Map<String, Object> buttonProps, Map<String, Object> data) {
        Map<String, Object> identifiers = new HashMap<>();

        // Strategy A: Clean Properties (upsertKey.colName = jsonPath)
        buttonProps.forEach((key, value) -> {
            if (key.startsWith("upsertKey.") && value instanceof String) {
                String colName = key.substring("upsertKey.".length());
                String jsonPath = ((String) value).trim();
                String val = extractValueByPath(data, jsonPath);
                if (val != null) identifiers.put(colName, val);
            }
        });

//        // Strategy B: Legacy String (upsertKeys = "path:col, path:col")
//        if (identifiers.isEmpty() && buttonProps.containsKey("upsertKeys")) {
//            String rawConfig = (String) buttonProps.get("upsertKeys");
//            for (String def : rawConfig.split(",")) {
//                String[] parts = def.split(":");
//                String path = parts[0].trim();
//                String col = (parts.length > 1) ? parts[1].trim() : path.substring(path.lastIndexOf(".") + 1);
//                String val = extractValueByPath(data, path);
//                if (val != null) identifiers.put(col, val);
//            }
//        }
//
//        // Strategy C: Single Key Legacy
//        if (identifiers.isEmpty() && buttonProps.containsKey("upsertKey")) {
//            String path = (String) buttonProps.get("upsertKey");
//            String val = extractValueByPath(data, path);
//            if (val != null) identifiers.put("id", val);
//        }

        return identifiers;
    }

    public boolean hasSqlTag(Map<String, Object> formDef) {
        if (formDef == null || !formDef.containsKey("tags")) return false;
        List<String> tags = (List<String>) formDef.get("tags");
        return tags.stream().anyMatch(tag -> "sql".equalsIgnoreCase(tag));
    }

    public String extractValueByPath(Map<String, Object> data, String path) {
        if (data == null || path == null) return null;
        String[] keys = path.split("\\.");
        Object current = data;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                log.warn("⚠️ Path extraction stopped: [{}] is not a map", key);
                return null;
            }
        }
        return current != null ? String.valueOf(current) : null;
    }

    public Map<String, Object> extractProcessVariables(Map<String, Object> schema, Map<String, Object> submittedData) {
        Map<String, Object> result = new HashMap<>();
        if (schema == null || !schema.containsKey("components")) return result;
        JsonNode componentNode = objectMapper.valueToTree(schema.get("components"));
        scanComponents(componentNode, submittedData, result);
        return result;
    }

    private void scanComponents(JsonNode components, Map<String, Object> data, Map<String, Object> result) {
        if (components == null || !components.isArray()) return;
        for (JsonNode comp : components) {
            String key = comp.path("key").asText();
            if (comp.has("tags") && comp.get("tags").isArray()) {
                for (JsonNode tag : comp.get("tags")) {
                    if ("processVariable".equalsIgnoreCase(tag.asText())) {
                        if (data.containsKey(key)) {
                            result.put(key, data.get(key));
                            log.debug("📌 Variable extracted: [{}]", key);
                        }
                        break;
                    }
                }
            }
            if (comp.has("components")) scanComponents(comp.get("components"), data, result);
            if (comp.has("columns") && comp.get("columns").isArray()) {
                for (JsonNode col : comp.get("columns")) {
                    if (col.has("components")) scanComponents(col.get("components"), data, result);
                }
            }
        }
    }

    @Data
    @Builder
    public static class SubmissionResult {
        private String submissionId;
        private Map<String, Object> processVariables;
    }
}