package com.example.flowable_app.config;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.validator.ValidatorSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

@Configuration
public class FlowableListenerConfig implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

    @Autowired
    private BusinessKeyEnforcer businessKeyEnforcer;

    @Autowired
    private ProcessDeploymentValidator processDeploymentValidator;

    @Autowired
    private TenantAwareCommandInterceptor tenantAwareCommandInterceptor; // 🟢 Inject our new command interceptor

    @Override
    public void configure(SpringProcessEngineConfiguration engineConfiguration) {

        // 1. Register Event Listeners
        if (engineConfiguration.getEventListeners() == null) {
            engineConfiguration.setEventListeners(new ArrayList<>());
        }
        engineConfiguration.getEventListeners().add(businessKeyEnforcer);

        // 2. Register Custom Validator
        ProcessValidatorFactory validatorFactory = new ProcessValidatorFactory();
        ProcessValidator processValidator = validatorFactory.createDefaultProcessValidator();

        ValidatorSet customSet = new ValidatorSet("CustomChecks");
        customSet.addValidator(processDeploymentValidator);

        processValidator.getValidatorSets().add(customSet);
        engineConfiguration.setProcessValidator(processValidator);

        // 3. 🟢 Register Command Interceptor for Background/Timer Jobs
        if (engineConfiguration.getCustomPreCommandInterceptors() == null) {
            engineConfiguration.setCustomPreCommandInterceptors(new ArrayList<>());
        }
        engineConfiguration.getCustomPreCommandInterceptors().add(tenantAwareCommandInterceptor);

        System.out.println("✅ REGISTERED: BusinessKeyEnforcer, Validators, and TenantAwareCommandInterceptor.");
    }
}