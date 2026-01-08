package com.example.flowable_app;

import com.example.flowable_app.client.FormIoClient;
import com.example.flowable_app.dto.TaskSubmitDto;
import com.example.flowable_app.service.GoogleDriveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // 👈 NEW IMPORT
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class WorkflowEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // We inject real Flowable services to verify DB state
    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;

    // We MOCK external APIs because we are testing YOUR logic, not Google's up-time.
    @MockitoBean private FormIoClient formIoClient;       // 👈 REPLACED @MockBean
    @MockitoBean private GoogleDriveService googleDriveService; // 👈 REPLACED @MockBean

    private String deploymentId;

    @BeforeEach
    public void setup() {
        // 1. Deploy a temporary dummy process for this test
        deploymentId = repositoryService.createDeployment()
                .addString("test-process.bpmn20.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"http://flowable.org/bpmn\">" +
                                "  <process id=\"testProcess\" name=\"Test Process\">" +
                                "    <startEvent id=\"start\"/>" +
                                "    <sequenceFlow sourceRef=\"start\" targetRef=\"userTask\"/>" +
                                "    <userTask id=\"userTask\" name=\"Approve Request\" flowable:assignee=\"kermit\" xmlns:flowable=\"http://flowable.org/bpmn\"/>" +
                                "    <sequenceFlow sourceRef=\"userTask\" targetRef=\"end\"/>" +
                                "    <endEvent id=\"end\"/>" +
                                "  </process>" +
                                "</definitions>")
                .deploy()
                .getId();

        // 2. Mock Form.io responses (so your Controller doesn't crash)
        given(formIoClient.getFormSchema(anyString())).willReturn(Map.of("components", Collections.emptyList()));
        given(formIoClient.submitForm(anyString(), any())).willReturn(Map.of("_id", "dummy-submission-id"));
    }

    @AfterEach
    public void cleanup() {
        // Clean up H2 after test
        repositoryService.deleteDeployment(deploymentId, true);
    }

    @Test
    @WithMockUser(username = "kermit", roles = "USER") // Simulate a logged-in user
    public void testFullWorkflowLifecycle() throws Exception {

        // =================================================================
        // STEP 1: START PROCESS via API
        // =================================================================
        TaskSubmitDto startPayload = new TaskSubmitDto();
        startPayload.setSubmittedFormKey("form-start");
        startPayload.setVariables(Map.of("initiator", "kermit"));

        MvcResult startResult = mockMvc.perform(post("/api/workflow/process/testProcess/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processInstanceId").exists())
                .andReturn();

        String procId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                .get("processInstanceId").asText();

        System.out.println("✅ Process Started: " + procId);

        // =================================================================
        // STEP 2: VERIFY TASK CREATION (Using Flowable Service directly)
        // =================================================================
        org.flowable.task.api.Task task = taskService.createTaskQuery()
                .processInstanceId(procId)
                .singleResult();

        assertEquals("Approve Request", task.getName());
        assertEquals("kermit", task.getAssignee());

        // =================================================================
        // STEP 3: CLAIM TASK via API
        // =================================================================
        mockMvc.perform(post("/api/workflow/claim-task")
                        .param("taskId", task.getId())
                        .header("userId", "kermit"))
                .andExpect(status().isOk());

        // =================================================================
        // STEP 4: RENDER TASK via API
        // =================================================================
        mockMvc.perform(get("/api/workflow/tasks/" + task.getId() + "/render"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.getId()))
                .andExpect(jsonPath("$.taskName").value("Approve Request"));

        // =================================================================
        // STEP 5: SUBMIT/COMPLETE TASK via API
        // =================================================================
        TaskSubmitDto completePayload = new TaskSubmitDto();
        completePayload.setCompleteTask(true);
        completePayload.setFormData(Map.of("approvalStatus", "APPROVED"));

        mockMvc.perform(post("/api/workflow/tasks/" + task.getId() + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completePayload)))
                .andExpect(status().isOk())
                .andDo(print());

        // =================================================================
        // STEP 6: VERIFY COMPLETION
        // =================================================================
        long count = runtimeService.createProcessInstanceQuery()
                .processInstanceId(procId)
                .count();

        assertEquals(0, count, "Process should be finished and removed from runtime tables");
    }
}