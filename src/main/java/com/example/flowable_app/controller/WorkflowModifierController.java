package com.example.flowable_app.controller;

import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.model.*;
import org.flowable.engine.RepositoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Slf4j
public class WorkflowModifierController {

    @Autowired
    private RepositoryService repositoryService;

    @PostMapping("/add-static-buttons")
    public ResponseEntity<?> addStaticButtonsToTask(
            @RequestParam String processDefinitionKey,
            @RequestParam String taskDefinitionKey,
            @RequestBody String buttonsJson) {

        log.info("🛠️ MODIFIER: Adding buttons to Task [{}] in Process [{}]", taskDefinitionKey, processDefinitionKey);

        try {
            // 1. Get Process Def
            org.flowable.engine.repository.ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processDefinitionKey)
                    .latestVersion()
                    .singleResult();

            if (def == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Process Definition not found", "key", processDefinitionKey));
            }

            // 2. Get BpmnModel
            BpmnModel bpmnModel = repositoryService.getBpmnModel(def.getId());
            org.flowable.bpmn.model.Process process = bpmnModel.getMainProcess();

            // 3. Find User Task
            FlowElement flowElement = process.getFlowElement(taskDefinitionKey);
            if (flowElement instanceof UserTask) {
                UserTask userTask = (UserTask) flowElement;

                // 4. Create Extension Element
                ExtensionElement propertyElement = new ExtensionElement();
                propertyElement.setName("property");
                propertyElement.setNamespacePrefix("flowable");
                propertyElement.setNamespace("http://flowable.org/bpmn");

                // 5. Add Attribute: name="externalActions"
                ExtensionAttribute nameAttr = new ExtensionAttribute("name");
                nameAttr.setValue("externalActions");
                propertyElement.addAttribute(nameAttr);

                // 6. Set JSON
                propertyElement.setElementText(buttonsJson);

                // 7. Remove existing property to avoid duplicates
                List<ExtensionElement> existingProps = userTask.getExtensionElements().get("property");
                if (existingProps != null) {
                    existingProps.removeIf(e -> "externalActions".equals(e.getAttributeValue(null, "name")));
                }

                // 8. Add new element
                userTask.addExtensionElement(propertyElement);

                // 9. Deploy
                repositoryService.createDeployment()
                        .addBpmnModel("dispatch-process-static.bpmn", bpmnModel)
                        .name("Dispatch Workflow (Static Props)")
                        .deploy();

                log.info("✅ SUCCESS: Buttons added to [{}]", taskDefinitionKey);
                return ResponseEntity.ok(Map.of("message",
                        "Static buttons added successfully",
                        "taskKey",
                        taskDefinitionKey));
            }

            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Task definition key not found in BPMN", "taskKey", taskDefinitionKey));

        } catch (Exception e) {
            log.error("❌ MODIFIER ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to modify workflow", "message", e.getMessage()));
        }
    }
}