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
    private ProcessDeploymentValidator processDeploymentValidator; // 🟢 Inject generic validator
    @Override
    public void configure(SpringProcessEngineConfiguration engineConfiguration) {
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

        System.out.println("✅ REGISTERED: BusinessKeyEnforcer has been added to Flowable Engine.");
    }
}