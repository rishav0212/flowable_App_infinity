package com.example.flowable_app.config;

import com.example.flowable_app.features.iam.service.SystemResourceSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupSyncListener {

    private final SystemResourceSyncService syncService;

    // This annotation tells Spring to run this method the exact moment the app is fully ready
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("🚀 Application is ready! Triggering System Resource Sync...");
        try {
            syncService.syncSystemResourcesAcrossAllTenants();
        } catch (Exception e) {
            log.error("❌ Failed to sync system resources on startup: {}", e.getMessage(), e);
        }
    }
}