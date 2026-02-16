package com.example.flowable_app.config;

import com.example.flowable_app.service.*;
import com.fasterxml.jackson.databind.ObjectMapper; // 1. Import Jackson
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class FlowableExpressionConfig implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

    private final FlowableWorkflowService flowableWorkflowService;
    private final FlowableDataService flowableDataService;
    private final FlowableMapService flowableMapService;
    private final FlowableUserService flowableUserService;
    private final FlowableEmailService flowableEmailService;
    private final ObjectMapper objectMapper; // 2. Inject ObjectMapper

    public FlowableExpressionConfig(@Lazy FlowableWorkflowService flowableWorkflowService,
                                    @Lazy FlowableDataService flowableDataService,
                                    @Lazy FlowableMapService flowableMapService,
                                    @Lazy FlowableUserService flowableUserService,
                                    @Lazy FlowableEmailService flowableEmailService,
                                    ObjectMapper objectMapper) { // 3. Constructor Injection
        this.flowableWorkflowService = flowableWorkflowService;
        this.flowableDataService = flowableDataService;
        this.flowableMapService = flowableMapService;
        this.flowableUserService = flowableUserService;
        this.flowableEmailService = flowableEmailService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(SpringProcessEngineConfiguration engineConfiguration) {
        Map<Object, Object> allowedBeans = new HashMap<>();

        // --- Your Custom Secure Services ---
        allowedBeans.put("secureWorkflow", flowableWorkflowService);
        allowedBeans.put("data", flowableDataService);
        allowedBeans.put("map", flowableMapService);
        allowedBeans.put("user", flowableUserService);
        allowedBeans.put("email", flowableEmailService);

        // 🟢 FIX: Re-expose Jackson for JSON parsing in expressions
        allowedBeans.put("jacksonObjectMapper", objectMapper);

        engineConfiguration.setBeans(allowedBeans);
    }
}