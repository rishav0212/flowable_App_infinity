package com.example.flowable_app.controller;

import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.*;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class WorkflowModifierController {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final ProcessMigrationService processMigrationService;
    private final HistoryService historyService;
    private final UserContextService userContextService; // 🟢 Injected for Tenant Awareness

    /**
     * 🟢 MIGRATE INSTANCES (Tenant Scoped)
     */
    @PostMapping("/migrate/{processKey}/{targetVersion}")
    public ResponseEntity<?> migrateInstances(@PathVariable String processKey, @PathVariable int targetVersion) {
        String tenantId = userContextService.getCurrentTenantId(); // 🔒 Get Tenant
        log.info("🚀 MIGRATION: Moving instances of [{}] to version [{}] for Tenant [{}]",
                processKey,
                targetVersion,
                tenantId);

        try {
            // 1. Get Target Definition (Scoped to Tenant)
            ProcessDefinition targetDef = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey)
                    .processDefinitionVersion(targetVersion)
                    .processDefinitionTenantId(tenantId) // 🔒 Security Fix
                    .singleResult();

            if (targetDef == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Target version not found for key: " + processKey));
            }

            // 2. Find instances that are NOT on the target version (Scoped to Tenant)
            List<ProcessInstance> instancesToMigrate = runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey(processKey)
                    .processInstanceTenantId(tenantId) // 🔒 Security Fix
                    .list()
                    .stream()
                    .filter(pi -> !pi.getProcessDefinitionId().equals(targetDef.getId()))
                    .collect(Collectors.toList());

            if (instancesToMigrate.isEmpty()) {
                return ResponseEntity.ok(Map.of("message", "No active instances need migration for this tenant."));
            }

            log.info("Found {} instances to migrate.", instancesToMigrate.size());

            // 3. Execute Migration
            int successCount = 0;
            int failCount = 0;
            StringBuilder errors = new StringBuilder();

            for (ProcessInstance instance : instancesToMigrate) {
                try {
                    processMigrationService.createProcessInstanceMigrationBuilder()
                            .migrateToProcessDefinition(targetDef.getId())
                            .migrate(instance.getId());

                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    String errMsg = "Instance " + instance.getId() + " failed: " + e.getMessage();
                    log.error(errMsg);
                    errors.append(errMsg).append("; ");
                }
            }

            // 4. Build Response
            String message = String.format("Migration Complete. Success: %d, Failed: %d.", successCount, failCount);

            if (failCount > 0) {
                return ResponseEntity.status(HttpStatus.MULTI_STATUS)
                        .body(Map.of("message", message, "errors", errors.toString()));
            }
            return ResponseEntity.ok(Map.of("message", message));

        } catch (Exception e) {
            log.error("❌ MIGRATION ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Migration process failed", "details", e.getMessage()));
        }
    }

    /**
     * 🟢 ADD STATIC BUTTONS (Tenant Scoped)
     */
    @PostMapping("/add-static-buttons")
    public ResponseEntity<?> addStaticButtonsToTask(
            @RequestParam String processDefinitionKey,
            @RequestParam String taskDefinitionKey,
            @RequestBody String buttonsJson) {

        String tenantId = userContextService.getCurrentTenantId(); // 🔒 Get Tenant
        log.info("🛠️ MODIFIER: Adding buttons to Task [{}] in Process [{}] for Tenant [{}]",
                taskDefinitionKey, processDefinitionKey, tenantId);

        try {
            // 🔒 Security Fix: Only find latest version for THIS tenant
            ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .processDefinitionTenantId(tenantId)
                    .latestVersion()
                    .singleResult();

            if (def == null) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Process Definition not found for this tenant"));

            BpmnModel bpmnModel = repositoryService.getBpmnModel(def.getId());
            org.flowable.bpmn.model.Process process = bpmnModel.getMainProcess();
            FlowElement flowElement = process.getFlowElement(taskDefinitionKey);

            if (flowElement instanceof UserTask) {
                UserTask userTask = (UserTask) flowElement;

                // (Same logic to add properties...)
                ExtensionElement propertyElement = new ExtensionElement();
                propertyElement.setName("property");
                propertyElement.setNamespacePrefix("flowable");
                propertyElement.setNamespace("http://flowable.org/bpmn");

                ExtensionAttribute nameAttr = new ExtensionAttribute("name");
                nameAttr.setValue("externalActions");
                propertyElement.addAttribute(nameAttr);
                propertyElement.setElementText(buttonsJson);

                List<ExtensionElement> existingProps = userTask.getExtensionElements().get("property");
                if (existingProps != null) {
                    existingProps.removeIf(e -> "externalActions".equals(e.getAttributeValue(null, "name")));
                }

                userTask.addExtensionElement(propertyElement);

                // 🟢 CRITICAL DEPLOYMENT FIX
                repositoryService.createDeployment()
                        .addBpmnModel("dispatch-process-static.bpmn", bpmnModel)
                        .name("Dispatch Workflow (Static Props)")
                        .tenantId(tenantId) // 🔒 MUST SET TENANT ID
                        .deploy();

                return ResponseEntity.ok(Map.of("message",
                        "Static buttons added successfully",
                        "taskKey",
                        taskDefinitionKey));
            }

            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Task definition key not found", "taskKey", taskDefinitionKey));

        } catch (Exception e) {
            log.error("❌ MODIFIER ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to modify workflow", "message", e.getMessage()));
        }
    }

    /**
     * 🟢 PURGE DATA ENDPOINT (Tenant Scoped)
     */
    @DeleteMapping("/purge/{processKey}")
    public ResponseEntity<?> purgeWorkflowData(@PathVariable String processKey) {
        String tenantId = userContextService.getCurrentTenantId(); // 🔒 Get Tenant
        log.info("🔥 PURGE REQUEST: Wiping all data for Process Key: [{}] Tenant: [{}]", processKey, tenantId);

        try {
            // 1. Delete Running Instances (Active) - Scoped to Tenant
            List<ProcessInstance> runningInstances = runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey(processKey)
                    .processInstanceTenantId(tenantId) // 🔒 Security Fix
                    .list();

            int runningCount = runningInstances.size();
            for (ProcessInstance pi : runningInstances) {
                runtimeService.deleteProcessInstance(pi.getId(), "Admin Purge Request");
            }

            // 2. Delete Historic Instances (Completed/Cancelled) - Scoped to Tenant
            List<HistoricProcessInstance> historicInstances = historyService.createHistoricProcessInstanceQuery()
                    .processDefinitionKey(processKey)
                    .processInstanceTenantId(tenantId) // 🔒 Security Fix
                    .list();

            int historyCount = historicInstances.size();
            for (HistoricProcessInstance hpi : historicInstances) {
                historyService.deleteHistoricProcessInstance(hpi.getId());
            }

            log.info("✅ PURGE COMPLETE: Deleted {} active and {} historic instances.", runningCount, historyCount);

            return ResponseEntity.ok(Map.of(
                    "message", "Purge Successful",
                    "deletedActive", runningCount,
                    "deletedHistory", historyCount
            ));

        } catch (Exception e) {
            log.error("❌ PURGE FAILED: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to purge data", "details", e.getMessage()));
        }
    }
}