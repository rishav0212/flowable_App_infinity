package com.example.flowable_app.controller;

import com.example.flowable_app.service.GoogleDriveService;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controller to handle file storage operations with Google Drive.
 * <p>
 * This controller acts as a bridge/proxy between the frontend (e.g., Form.io)
 * and the Google Drive API. It handles uploading files to specific Drive folders
 * and serving those files back via a proxy URL.
 * </p>
 */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Google Drive Storage", description = "Endpoints for uploading and proxying files via Google Drive")
public class StorageController {

    private final GoogleDriveService googleDriveService;

    @Value("${app.backend.url}")
    private String backendUrl;

    /**
     * Uploads a file to a specific Google Drive folder.
     */
    @Operation(
            summary = "Upload a file to Google Drive",
            description = "Accepts a multipart file and a target folder ID. Uploads the file to Drive and returns metadata compatible with Form.io."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "File uploaded successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid input (empty file or missing folder ID)"),
            @ApiResponse(responseCode = "403", description = "Permission denied (Service Account cannot access folder)"),
            @ApiResponse(responseCode = "404", description = "Target Folder ID not found"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error")
    })
    @PostMapping(value = "/gdrive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadToDrive(
            @Parameter(description = "The file to upload", required = true, content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "The ID of the Google Drive folder where the file will be stored", required = true, example = "1A2B3C...")
            @RequestParam(value = "folderId", required = true) String folderId
    ) {
        // Log entry with key details (excluding file content)
        log.info("📤 UPLOAD REQUEST: File [{}], Size [{} bytes], Target Folder [{}]",
                file.getOriginalFilename(), file.getSize(), folderId);

        try {
            // 1. Validate File
            if (file.isEmpty()) {
                log.warn("⚠️ UPLOAD FAILED: Input file is empty.");
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

            log.debug("🔄 Generated unique filename: [{}]", uniqueFileName);

            // 3. Upload (delegates to service)
            String fileId = googleDriveService.uploadFile(file, uniqueFileName, folderId);

            // 4. Success Response construction
            String proxyUrl = backendUrl + "/api/storage/proxy/" + fileId;
            Map<String, Object> response = new HashMap<>();
            response.put("name", uniqueFileName);
            response.put("size", file.getSize());
            response.put("type", file.getContentType());
            response.put("url", proxyUrl);

            // Return folderId for context
            response.put("folderId", folderId);

            log.info("✅ UPLOAD SUCCESS: File ID [{}] generated for [{}]", fileId, uniqueFileName);
            return ResponseEntity.ok(response);

        } catch (GoogleJsonResponseException e) {
            String errorMsg = "Google Drive Error";
            int statusCode = e.getStatusCode();

            if (statusCode == 404) {
                errorMsg = "Target Folder ID not found. Please check your Form URL configuration.";
            } else if (statusCode == 403) {
                errorMsg = "Permission Denied. The service account cannot write to this folder.";
            } else if (e.getDetails() != null) {
                errorMsg = "Drive Error: " + e.getDetails().getMessage();
            }

            log.error("❌ GOOGLE API ERROR: Code [{}], Message [{}]", statusCode, errorMsg);
            return ResponseEntity.status(statusCode).body(Map.of("message", errorMsg));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ BAD REQUEST: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());

        } catch (Exception e) {
            log.error("❌ SYSTEM ERROR: Upload failed unexpectedly. {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Upload failed: " + e.getMessage());
        }
    }

    /**
     * Proxies a file download from Google Drive.
     */
    @Operation(
            summary = "View or Stream file content",
            description = "Smart endpoint: Redirects to Google Viewer for Office files and streams PDF/Images directly."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "File stream retrieved successfully (Images/PDF)"),
            @ApiResponse(responseCode = "302", description = "Redirected to Google Drive Viewer (Excel/Word)"),
            @ApiResponse(responseCode = "404", description = "File ID not found"),
            @ApiResponse(responseCode = "500", description = "Failed to process request")
    })
    @GetMapping("/proxy/{fileId}")
    public ResponseEntity<?> viewFile(
            @Parameter(description = "The unique Google Drive File ID", required = true)
            @PathVariable String fileId
    ) {
        log.debug("📥 PROXY REQUEST: Fetching content for File ID [{}]", fileId);

        try {
            // 1. Fetch metadata
            com.google.api.services.drive.model.File gFile = googleDriveService.getFileMetadata(fileId);
            String mimeType = gFile.getMimeType();

            // 2. LOGIC SWITCH: Check if it's an Office file (Excel, Word, PPT)
            if (isOfficeFile(mimeType)) {

                // 🟢 CHANGE HERE: Manually construct the PREVIEW URL
                // This forces the "Gmail Attachment" style viewer
                String previewUrl = "https://drive.google.com/file/d/" + fileId + "/view";

                log.info("↪️ REDIRECT: Office file detected [{}]. Redirecting to Preview Mode.", gFile.getName());

                return ResponseEntity.status(HttpStatus.FOUND) // 302 Redirect
                        .location(URI.create(previewUrl))
                        .build();
            }

            // 3. STREAMING LOGIC: For Images, PDFs, Text (Keep this as is)
            log.info("✅ PROXY STREAM: Streaming file [{}], Type [{}]", gFile.getName(), mimeType);
            InputStreamResource resource = new InputStreamResource(googleDriveService.downloadFile(fileId));

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + gFile.getName() + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("❌ PROXY FAILED: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }
    /**
     * Helper to detect files that browsers cannot render natively.
     */
    private boolean isOfficeFile(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.contains("excel") ||       // .xlsx, .xls
                mimeType.contains("spreadsheet") || // Google Sheets
                mimeType.contains("word") ||        // .docx, .doc
                mimeType.contains("document") ||    // Google Docs
                mimeType.contains("presentation") || // .pptx
                mimeType.contains("powerpoint");
    }
}