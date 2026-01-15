package com.example.flowable_app.controller;

import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.*;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@Slf4j
public class WorkflowModifierController {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ProcessMigrationService processMigrationService;

    /**
     * 🟢 MIGRATE INSTANCES (Fixed Builder Logic)
     */
    @PostMapping("/migrate/{processKey}/{targetVersion}")
    public ResponseEntity<?> migrateInstances(@PathVariable String processKey, @PathVariable int targetVersion) {
        log.info("🚀 MIGRATION: Moving instances of [{}] to version [{}]", processKey, targetVersion);

        try {
            // 1. Get Target Definition
            ProcessDefinition targetDef = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey)
                    .processDefinitionVersion(targetVersion)
                    .singleResult();

            if (targetDef == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Target version not found for key: " + processKey));
            }

            // 2. Find instances that are NOT on the target version
            List<ProcessInstance> instancesToMigrate = runtimeService.createProcessInstanceQuery()
                    .processDefinitionKey(processKey)
                    .list()
                    .stream()
                    .filter(pi -> !pi.getProcessDefinitionId().equals(targetDef.getId()))
                    .collect(Collectors.toList());

            if (instancesToMigrate.isEmpty()) {
                return ResponseEntity.ok(Map.of("message", "No active instances need migration."));
            }

            log.info("Found {} instances to migrate.", instancesToMigrate.size());

            // 3. Execute Migration One-by-One (For better error reporting)
            int successCount = 0;
            int failCount = 0;
            StringBuilder errors = new StringBuilder();

            for (ProcessInstance instance : instancesToMigrate) {
                try {
                    // 🟢 CORRECTED: Use service to create the builder
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
                return ResponseEntity.status(HttpStatus.MULTI_STATUS) // 207 Multi-Status
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
     * Existing Endpoint: Add static buttons to task
     */
    @PostMapping("/add-static-buttons")
    public ResponseEntity<?> addStaticButtonsToTask(
            @RequestParam String processDefinitionKey,
            @RequestParam String taskDefinitionKey,
            @RequestBody String buttonsJson) {

        log.info("🛠️ MODIFIER: Adding buttons to Task [{}] in Process [{}]", taskDefinitionKey, processDefinitionKey);

        try {
            ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .latestVersion()
                    .singleResult();

            if (def == null) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Process Definition not found"));

            BpmnModel bpmnModel = repositoryService.getBpmnModel(def.getId());
            org.flowable.bpmn.model.Process process = bpmnModel.getMainProcess();
            FlowElement flowElement = process.getFlowElement(taskDefinitionKey);

            if (flowElement instanceof UserTask) {
                UserTask userTask = (UserTask) flowElement;

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

                repositoryService.createDeployment()
                        .addBpmnModel("dispatch-process-static.bpmn", bpmnModel)
                        .name("Dispatch Workflow (Static Props)")
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
}