package com.example.flowable_app.controller;

import com.example.flowable_app.dto.TaskSubmitDto;
import com.example.flowable_app.service.FormSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
@Slf4j
public class DataOperationController {

    private final FormSchemaService formSchemaService;

    @PostMapping("/{formKey}/save")
    public ResponseEntity<?> saveData(@PathVariable String formKey, @RequestBody TaskSubmitDto payload) {
        log.info("💾 SAVE REQUEST: Standalone save for form [{}]", formKey);
        try {
            FormSchemaService.SubmissionResult result = formSchemaService.processSubmission(
                    formKey,
                    payload.getFormData(),
                    payload.getVariables()
            );

            return ResponseEntity.ok(Map.of(
                    "message", "Data Saved Successfully",
                    "id", result.getSubmissionId()
            ));

        } catch (Exception e) {
            log.error("❌ SAVE FAILED: Form [{}], Error: {}", formKey, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Data Save Failed", "message", e.getMessage()));
        }
    }
}