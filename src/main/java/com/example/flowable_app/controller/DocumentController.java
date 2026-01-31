package com.example.flowable_app.controller;

import com.example.flowable_app.service.DocumentGenerationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentGenerationService docGenService;

    @Data
    public static class DocRequest {
        private String type;            // "WORD", "EXCEL", "PDF", "EMAIL"
        private String templateId;      // Drive ID (or Template Name for Email)
        private String targetFolderId;  // Where to save it
        private String fileName;        // Output filename
        private Map<String, Object> data; // JSON data
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody DocRequest req) {
        try {
            String result;
            String fileType = req.getType().toUpperCase();

            switch (fileType) {
                case "WORD":
                    result = docGenService.generateWord(req.templateId, req.targetFolderId, req.fileName, req.data);
                    break;
                case "EXCEL":
                    result = docGenService.generateExcel(req.templateId, req.targetFolderId, req.fileName, req.data);
                    break;
                case "PDF":
                    result = docGenService.generatePdf(req.templateId, req.targetFolderId, req.fileName, req.data);
                    break;
                case "EMAIL":
                    // For email, result is the HTML content string
                    result = docGenService.renderEmail(req.templateId, req.data); // templateId here is template name (e.g. "invoice")
                    return ResponseEntity.ok(Map.of("status", "success", "htmlContent", result));
                default:
                    return ResponseEntity.badRequest().body("Invalid Type. Use WORD, EXCEL, PDF, or EMAIL.");
            }

            // For files, return the Google Drive File ID
            return ResponseEntity.ok(Map.of("status", "success", "fileId", result, "type", fileType));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}