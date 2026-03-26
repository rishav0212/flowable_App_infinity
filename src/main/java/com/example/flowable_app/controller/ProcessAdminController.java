package com.example.flowable_app.controller;

import com.example.flowable_app.config.RequiresPermission;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
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
    private final RuntimeService runtimeService; // 🟢 Inject this

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


    /**
     * 🟢 NEW: Securely fetch all active process instances for the current tenant.
     * Replaces the raw /process-api/runtime/process-instances call.
     */
    @GetMapping("/instances")
    @RequiresPermission(resource = "page:instance_manager", action = "view")
    public ResponseEntity<?> getInstances() {
        try {
            String tenantId = userContextService.getCurrentTenantId();

            // 1. Secure Native Query
            List<ProcessInstance> instances = runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .orderByStartTime().desc()
                    .list();

            // 2. 🟢 Clean, one-line mapping using Java Streams and our Record
            List<ProcessInstanceDto> safeData = instances.stream()
                    .map(ProcessInstanceDto::from)
                    .toList();

            // 3. Return the safe data
            return ResponseEntity.ok(Map.of(
                    "data", safeData,
                    "total", safeData.size()
            ));

        } catch (Exception e) {
            log.error("❌ Failed to fetch process instances: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }


    /**
     * Securely terminates a running process instance.
     * Enforces Casbin permissions and strict tenant isolation.
     */
    @DeleteMapping("/instances/{id}")
    @RequiresPermission(resource = "action:delete_instance", action = "execute")
    public ResponseEntity<?> terminateInstance(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "Terminated by Admin via UI") String reason) {

        try {
            String tenantId = userContextService.getCurrentTenantId();

            // 1. Security Check: Verify the instance exists AND belongs strictly to the current tenant
            long count = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(id)
                    .processInstanceTenantId(tenantId)
                    .count();

            if (count == 0) {
                // Return a generic 404 so attackers cannot probe for valid instance IDs across tenants
                return ResponseEntity.status(404)
                        .body(Map.of("error", "Process instance not found or access denied."));
            }

            // 2. Terminate the instance in the Flowable engine
            // The reason string is saved in the historic tables for auditing purposes
            runtimeService.deleteProcessInstance(id, reason);

            log.info("✅ Process instance {} terminated securely by tenant {}", id, tenantId);

            return ResponseEntity.ok(Map.of(
                    "message", "Process instance terminated successfully.",
                    "id", id
            ));

        } catch (Exception e) {
            log.error("❌ Failed to terminate process instance {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    public record ProcessInstanceDto(
            String id,
            String processDefinitionId,
            String processDefinitionKey,
            String processDefinitionName,
            String businessKey,
            java.util.Date startTime,
            boolean suspended,
            String tenantId
    ) {
        // A quick helper method to convert Flowable's object into our clean object
        public static ProcessInstanceDto from(ProcessInstance pi) {
            return new ProcessInstanceDto(
                    pi.getId(), pi.getProcessDefinitionId(), pi.getProcessDefinitionKey(),
                    pi.getProcessDefinitionName(), pi.getBusinessKey(), pi.getStartTime(),
                    pi.isSuspended(), pi.getTenantId()
            );
        }
    }
}

