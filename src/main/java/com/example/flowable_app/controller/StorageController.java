package com.example.flowable_app.controller;

import com.example.flowable_app.service.GoogleDriveService;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
@Slf4j
public class StorageController {

    private final GoogleDriveService googleDriveService;
    @Value("${app.backend.url}")
    private String backendUrl;

    @PostMapping(value = "/gdrive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadToDrive(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = true) String folderId
    ) {
        log.info("📤 UPLOAD REQUEST: File [{}], Target Folder [{}]", file.getOriginalFilename(), folderId);

        try {
            // 1. Validate File
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File cannot be empty.");
            }

            // 2. Generate Name
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "unknown_file";

            // Sanitize filename (remove paths)
            originalName = new java.io.File(originalName).getName();

            String extension = "";
            String baseName = originalName;
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex >= 0) {
                extension = originalName.substring(dotIndex);
                baseName = originalName.substring(0, dotIndex);
            }
            String uniqueFileName = baseName + "-" + UUID.randomUUID().toString() + extension;

            // 3. Upload (with specific error handling)
            String fileId = googleDriveService.uploadFile(file, uniqueFileName, folderId);

            // 4. Success Response

            String proxyUrl = backendUrl + "/api/storage/proxy/" + fileId;
            Map<String, Object> response = new HashMap<>();
            response.put("name", uniqueFileName);
            response.put("size", file.getSize());
            response.put("type", file.getContentType());
            response.put("url", proxyUrl);

            log.info("✅ UPLOAD SUCCESS: File ID [{}]", fileId);
            return ResponseEntity.ok(response);

            // 🟢 SPECIFIC GOOGLE DRIVE ERRORS
        } catch (GoogleJsonResponseException e) {
            String errorMsg = "Google Drive Error";

            if (e.getStatusCode() == 404) {
                errorMsg = "Target Folder ID not found. Please check your Form URL configuration.";
            } else if (e.getStatusCode() == 403) {
                errorMsg = "Permission Denied. The service account cannot write to this folder.";
            } else if (e.getDetails() != null) {
                errorMsg = "Drive Error: " + e.getDetails().getMessage();
            }

            log.error("❌ GOOGLE API ERROR: {} - {}", e.getStatusCode(), errorMsg);

            // Return plain text message for Form.io or a simple JSON with 'message'
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("message", errorMsg));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ BAD REQUEST: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage()); // Simple string is best for Form.io

        } catch (Exception e) {
            log.error("❌ SYSTEM ERROR: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Upload failed: " + e.getMessage());
        }
    }

    @GetMapping("/proxy/{fileId}")
    public ResponseEntity<?> viewFile(@PathVariable String fileId) {
        try {
            com.google.api.services.drive.model.File gFile = googleDriveService.getFileMetadata(fileId);
            InputStreamResource resource = new InputStreamResource(googleDriveService.downloadFile(fileId));

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(gFile.getMimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + gFile.getName() + "\"")
                    .body(resource);

        } catch (GoogleJsonResponseException e) {
            log.warn("⚠️ PROXY GOOGLE ERROR: {} {}", e.getStatusCode(), e.getDetails().getMessage());
            if (e.getStatusCode() == 404) return ResponseEntity.notFound().build();
            return ResponseEntity.status(e.getStatusCode()).body("Drive Error: " + e.getDetails().getMessage());

        } catch (Exception e) {
            log.error("⚠️ PROXY FAILED: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to retrieve file.");
        }
    }
}