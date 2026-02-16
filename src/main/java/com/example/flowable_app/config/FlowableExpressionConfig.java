package com.example.flowable_app.config;

import com.example.flowable_app.service.*;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class FlowableExpressionConfig implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

    // 1. Inject ONLY the services you want to expose
    private final FlowableWorkflowService flowableWorkflowService;
    private final FlowableDataService flowableDataService;
    private final FlowableMapService flowableMapService;
    private final FlowableUserService flowableUserService;
    private final FlowableEmailService flowableEmailService;

    // Manual Constructor for Injection
    public FlowableExpressionConfig(@Lazy FlowableWorkflowService flowableWorkflowService,
                                    @Lazy FlowableDataService flowableDataService,
                                    @Lazy FlowableMapService flowableMapService,
                                    @Lazy FlowableUserService flowableUserService,
                                    @Lazy FlowableEmailService flowableEmailService) {
        this.flowableWorkflowService = flowableWorkflowService;
        this.flowableDataService = flowableDataService;
        this.flowableMapService = flowableMapService;
        this.flowableUserService = flowableUserService;
        this.flowableEmailService = flowableEmailService;
    }

    @Override
    public void configure(SpringProcessEngineConfiguration engineConfiguration) {
        // 2. Create the "Allow List" (Whitelist)
        // 🟢 FIX: Must be Map<Object, Object> to match the Engine API
        Map<Object, Object> allowedBeans = new HashMap<>();

        // 🟢 Core Workflow Logic (Task Querying, Completing)
        // Usage: ${secureWorkflow.getTaskId(...)}
        allowedBeans.put("secureWorkflow", flowableWorkflowService);

        // 🟢 Data Operations (SQL/Database)
        // Usage: ${data.selectVal(...)}
        allowedBeans.put("data", flowableDataService);

        // 🟢 Map Utilities (Creating/Manipulating Maps/JSON)
        // Usage: ${map.of('key', 'value')}
        allowedBeans.put("map", flowableMapService);

        // 🟢 User Utilities (Looking up emails, managers, groups)
        // Usage: ${user.findEmailByName(...)}
        allowedBeans.put("user", flowableUserService);

        // 🟢 Email Service (Sending notifications)
        // Usage: ${email.send(...)}
        allowedBeans.put("email", flowableEmailService);

        // 3. 🔒 LOCK DOWN: Replace the default bean lookup with this limited map.
        // DANGEROUS beans like 'runtimeService', 'taskService', 'jdbcTemplate'
        // are now HIDDEN from the BPMN engine.
        engineConfiguration.setBeans(allowedBeans);
    }
}