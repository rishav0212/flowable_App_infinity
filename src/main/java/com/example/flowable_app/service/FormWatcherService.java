package com.example.flowable_app.service;

import com.example.flowable_app.client.FormIoClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FormWatcherService {

    private final FormIoClient formIoClient;
    private final SchemaSyncService schemaSyncService;
    private final ObjectMapper objectMapper;

    // Keep track of the last time we checked
    private Instant lastCheckTime = Instant.now().minusSeconds(60); // Start looking back 1 minute

    // Run every 30 seconds
//    @Scheduled(fixedDelay = 30000)
//    public void checkForModifiedForms() {
//        try {
//            log.debug("👀 Watchdog: Checking for modified forms since {}", lastCheckTime);
//
//            // 1. Prepare Query Params for Form.io API
//            // Form.io allows filtering by 'modified' date using '__gt' (greater than)
//            Map<String, String> params = new HashMap<>();
//            params.put("modified__gt", lastCheckTime.toString());
//            params.put("limit", "100");
//            params.put("type", "resource"); // Only sync actual forms, not resources/wizards if you prefer
//
//            // 2. Fetch changes
//            List<Map<String, Object>> forms = formIoClient.getForms(params);
//
//            if (!forms.isEmpty()) {
//                log.info("📢 Watchdog found {} modified forms!", forms.size());
//
//                // 3. Sync each form
//                for (Map<String, Object> formMap : forms) {
//                    try {
//                        String jsonBody = objectMapper.writeValueAsString(formMap);
//                        schemaSyncService.syncFormDefinition(jsonBody);
//                    } catch (Exception e) {
//                        log.error("Failed to sync form: " + formMap.get("path"), e);
//                    }
//                }
//
//                // 4. Update Check Time (Safety buffer: subtract 1 second to avoid gaps)
//                lastCheckTime = Instant.now().minusSeconds(1);
//            }
//
//        } catch (Exception e) {
//            log.error("Watchdog failed to contact Form.io: {}", e.getMessage());
//        }
//    }
}