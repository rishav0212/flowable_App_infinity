package com.example.flowable_app.config;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

@Configuration
public class FlowableListenerConfig implements EngineConfigurationConfigurer<SpringProcessEngineConfiguration> {

    @Autowired
    private BusinessKeyEnforcer businessKeyEnforcer;

    @Override
    public void configure(SpringProcessEngineConfiguration engineConfiguration) {
        if (engineConfiguration.getEventListeners() == null) {
            engineConfiguration.setEventListeners(new ArrayList<>());
        }

        // 🟢 Manually register the listener to ensure it runs
        engineConfiguration.getEventListeners().add(businessKeyEnforcer);
        System.out.println("✅ REGISTERED: BusinessKeyEnforcer has been added to Flowable Engine.");
    }
}