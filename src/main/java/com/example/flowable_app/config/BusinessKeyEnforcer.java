package com.example.flowable_app.config;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BusinessKeyEnforcer implements FlowableEventListener {

    private final HistoryService historyService;
    private final RuntimeService runtimeService;

    // Use @Lazy to avoid circular dependency issues
    @Autowired
    public BusinessKeyEnforcer(@Lazy HistoryService historyService, @Lazy RuntimeService runtimeService) {
        this.historyService = historyService;
        this.runtimeService = runtimeService;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() == FlowableEngineEventType.PROCESS_CREATED) {
            if (event instanceof FlowableEntityEvent) {
                Object entity = ((FlowableEntityEvent) event).getEntity();

                if (entity instanceof ExecutionEntity) {
                    ExecutionEntity execution = (ExecutionEntity) entity;

                    // Ensure this is the ROOT execution (the process instance itself)
                    if (execution.isProcessInstanceType() && execution.getParentId() == null) {
                        validateBusinessKey(
                                execution.getBusinessKey(),
                                execution.getProcessDefinitionKey()
                        );
                    }
                }
            }
        }
    }

    private void validateBusinessKey(String businessKey, String processDefinitionKey) {
        // 1. RULE: Mandatory Presence
        if (businessKey == null || businessKey.trim().isEmpty()) {
            throw new FlowableIllegalArgumentException("GLOBAL ERROR: Business Key is required to start any process.");
        }

        // 2. RULE: Check for Active Instances (Running right now)
        long activeCount = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(processDefinitionKey)
                .processInstanceBusinessKey(businessKey)
                .count();

        if (activeCount > 0) {
            throw new FlowableIllegalArgumentException(
                    "GLOBAL ERROR: Business Key '" + businessKey + "' is already in use by an ACTIVE instance."
            );
        }

        // 3. RULE: Check for Completed Instances (Finished successfully)
        // We exclude instances that were deleted/terminated (deleteReason != null)
        List<HistoricProcessInstance> finishedInstances = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(processDefinitionKey)
                .processInstanceBusinessKey(businessKey)
                .finished()
                .list();

        boolean existsCompleted = finishedInstances.stream()
                .anyMatch(inst -> inst.getDeleteReason() == null); // Null means completed normally

        if (existsCompleted) {
            throw new FlowableIllegalArgumentException(
                    "GLOBAL ERROR: Business Key '" + businessKey + "' was already used by a COMPLETED instance."
            );
        }
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}