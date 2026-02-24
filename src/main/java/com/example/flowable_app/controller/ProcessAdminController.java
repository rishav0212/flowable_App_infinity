package com.example.flowable_app.controller;

import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

/**
 * Custom controller for Admin process operations, enforcing tenant isolation.
 */
@RestController
@RequestMapping("/api/admin/processes")
@RequiredArgsConstructor
@Slf4j
public class ProcessAdminController {

    private final RepositoryService repositoryService;
    private final UserContextService userContextService;

    @PostMapping("/deploy")
    public ResponseEntity<?> deployProcess(
            @RequestParam("file") MultipartFile file,
            @RequestParam("deployment-name") String deploymentName) {

        try {
            // 1. Extract Tenant ID securely from the JWT session
            String tenantId = userContextService.getCurrentTenantId();

            // 2. Fix the filename extension (Flowable ignores standard .xml files)
            String originalFilename = file.getOriginalFilename();
            String safeFilename = originalFilename != null && originalFilename.endsWith(".bpmn20.xml")
                    ? originalFilename
                    : (originalFilename != null ? originalFilename.replace(".xml", "") : "process") + ".bpmn20.xml";

            log.info("🚀 Deploying process '{}' for tenant '{}'", deploymentName, tenantId);

            // 3. Programmatically deploy using Flowable's Java API
            try (InputStream inputStream = file.getInputStream()) {
                Deployment deployment = repositoryService.createDeployment()
                        .addInputStream(safeFilename, inputStream)
                        .name(deploymentName)
                        .tenantId(tenantId) // 🔒 Hard-coded to the user's actual tenant
                        .deploy();

                log.info("✅ Deployment successful. ID: {}", deployment.getId());

                return ResponseEntity.ok(Map.of(
                        "id", deployment.getId(),
                        "name", deployment.getName(),
                        "tenantId", deployment.getTenantId(),
                        "message", "Process deployed successfully"
                ));
            }
        } catch (Exception e) {
            log.error("❌ Failed to deploy process: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Deployment Failed",
                    "message", e.getMessage()
            ));
        }
    }
}