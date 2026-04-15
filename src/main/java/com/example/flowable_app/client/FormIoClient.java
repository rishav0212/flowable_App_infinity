package com.example.flowable_app.client;

import com.example.flowable_app.config.FormIoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// This name "formio" acts as an ID. The URL is read from application.properties
// Add 'formio.base-url=https://your-project.form.io' to your properties file
@FeignClient(name = "formio", url = "${formio.url}", configuration = FormIoConfiguration.class)
public interface FormIoClient {

    @GetMapping("/{formKey}")
    Map<String, Object> getFormSchema(@PathVariable("formKey") String formKey);

    @PostMapping("/{formKey}/submission")
    Map<String, Object> submitForm(
            @PathVariable("formKey") String formKey, @RequestBody Map<String, Object> submissionWrapper);
    // 🛑 NEW: Fetch forms with filters
    // We expect a list of forms. The response from Form.io is usually an array.
    @GetMapping("/form")
    List<Map<String, Object>> getForms(@RequestParam Map<String, String> queryParams);

    @GetMapping("/form/{formId}")
    Map<String, Object> getForm(@PathVariable("formId") String formId);
    // 🟢 ADD THIS MISSING METHOD
    // Automatically fetches and converts the submission JSON into a Map
    @GetMapping("/{formKey}/submission/{submissionId}")
    Map<String, Object> getSubmission(
            @PathVariable("formKey") String formKey,
            @PathVariable("submissionId") String submissionId
    );

}