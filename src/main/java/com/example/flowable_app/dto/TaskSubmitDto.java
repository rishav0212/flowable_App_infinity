package com.example.flowable_app.dto;

import lombok.Data;

import java.util.Map;

@Data public class TaskSubmitDto {
    private Map<String, Object> formData; // The actual data from the form
//    private String action;                // The button clicked (e.g. "FOLLOW_UP")
    private String submittedFormKey;          // Optional: The form key being submitted
    private Map<String, Object> variables;      // 🟢 NEW: Process variables (e.g. status="approved")
    private Boolean completeTask = true;
}