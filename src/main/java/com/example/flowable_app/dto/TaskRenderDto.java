package com.example.flowable_app.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
@Builder
public class TaskRenderDto {
    // Identity
    private String taskId;
    private String taskName;
    private String assignee;
    private String description;

    // Metadata (Send these! Your UI will need them eventually)
    private int priority;
    private Date createTime;
    private Date dueDate;

    // Process Context
    private String processInstanceId;
    private String businessKey; // Optional, but good for Header display (e.g., "Order #123")

    // The Payload
    private Map<String, Object> data; // The Merged Variables

    // The Visuals
    private Object formSchema;
}