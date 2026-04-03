package com.example.flowable_app.controller;

import com.example.flowable_app.entity.Tenant;
import com.example.flowable_app.entity.ToolJetWorkspace;
import com.example.flowable_app.repository.ToolJetWorkspaceRepository;
import com.example.flowable_app.service.AllowedUserService;
import com.example.flowable_app.service.CasbinService;
import com.example.flowable_app.service.GoogleDriveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static org.jooq.impl.DSL.*;

/**
 * 🟢 DEDICATED TOOLJET API CONTROLLER
 * All endpoints here start with /api/internal/tooljet/ and are secured
 * by the ToolJetOAuth2ServerConfig (Machine-to-Machine RSA Tokens).
 */
@RestController @RequestMapping("/api/internal/tooljet") @RequiredArgsConstructor @Slf4j
@Tag(name = "ToolJet Internal APIs",
        description = "Endpoints exclusively called by ToolJet via OAuth2 Client Credentials")
public class ToolJetInternalController {

    private final GoogleDriveService googleDriveService;
    private final CasbinService casbinService;
    private final AllowedUserService allowedUserService;
    private final ToolJetWorkspaceRepository toolJetWorkspaceRepository;
    private final DSLContext dsl;


    @Value("${app.backend.url}") private String backendUrl;

    @Operation(summary = "Upload Base64 File to Drive (Called by ToolJet)")
    @PostMapping(value = "/storage/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadFromToolJet(@RequestBody ToolJetUploadRequest request) {
        log.info("📤 TOOLJET API UPLOAD: File [{}], Target Folder [{}]", request.getFileName(), request.getFolderId());

        try {
            if (request.getBase64Data() == null || request.getBase64Data().isEmpty()) {
                throw new IllegalArgumentException("Base64 data cannot be empty.");
            }
            if (request.getFolderId() == null || request.getFolderId().trim().isEmpty()) {
                throw new IllegalArgumentException("Target Folder ID is required.");
            }

            // Clean the Base64 string (removes data:image/png;base64, etc.)
            String base64String = request.getBase64Data();
            if (base64String.contains(",")) {
                base64String = base64String.split(",")[1];
            }

            byte[] decodedBytes = Base64.getDecoder().decode(base64String);

            String originalName = request.getFileName() != null ? request.getFileName() : "tooljet_upload";
            originalName = new java.io.File(originalName).getName();

            String extension = "";
            String baseName = originalName;
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex >= 0) {
                extension = originalName.substring(dotIndex);
                baseName = originalName.substring(0, dotIndex);
            }
            String uniqueFileName = baseName + "-" + UUID.randomUUID().toString() + extension;

            String mimeType = request.getMimeType() != null ? request.getMimeType() : "application/octet-stream";
            String
                    fileId =
                    googleDriveService.uploadFile(decodedBytes, mimeType, uniqueFileName, request.getFolderId());

            // The proxy URL remains pointing to the standard public/user accessible endpoint
            String proxyUrl = backendUrl + "/api/storage/proxy/" + fileId;

            Map<String, Object> response = new HashMap<>();
            response.put("name", uniqueFileName);
            response.put("size", decodedBytes.length);
            response.put("type", mimeType);
            response.put("url", proxyUrl);
            response.put("folderId", request.getFolderId());
            response.put("fileId", fileId);

            log.info("✅ TOOLJET API UPLOAD SUCCESS: File ID [{}]", fileId);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ TOOLJET API BAD REQUEST: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ TOOLJET API ERROR: Upload failed. {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    // You can safely move the PermissionController's internal ToolJet logic here later
    // e.g., /api/internal/tooljet/permissions
    @GetMapping("/permissions") public ResponseEntity<?> getToolJetPermissions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String email,
            @RequestParam String organisationId) { // This is the workspace_uuid from ToolJet

        try {
            // 1. Bridge Lookup: Find the Tenant mapping via ToolJet Workspace UUID
            // WHY: ToolJet identifies itself with its own UUID. We use our mapping table
            // (tbl_tooljet_workspaces) to resolve which internal tenant this belongs to.
            ToolJetWorkspace
                    mapping =
                    toolJetWorkspaceRepository.findByWorkspaceUuid(organisationId)
                            .orElseThrow(() -> new RuntimeException("No tenant mapping found for Workspace UUID: " +
                                    organisationId));

            // 2. Resolve Tenant & Schema
            // WHY: mapping.getTenant() already provides the full Tenant object thanks to the
            // JPA @OneToOne relationship. We extract the ID and Schema name directly from it.
            Tenant tenant = mapping.getTenant();
            if (tenant == null) {
                throw new RuntimeException("Tenant relationship is null for mapping ID: " + mapping.getId());
            }

            String schema = tenant.getSchemaName();
            String internalTenantId = tenant.getId();

            // 3. 🟢 SMART LOOKUP: Find internal userId via email if missing (Common in Developer Mode)
            if ((userId == null || userId.trim().isEmpty()) && email != null && !email.trim().isEmpty()) {
                userId = allowedUserService.getUserIdByEmail(email, schema);
                if (userId == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body("User with email " + email + " not found in tenant schema " + schema);
                }
            }

            if (userId == null || userId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Either userId or email must be provided");
            }

            // 4. Fetch all registered resources & actions from the Resolved Schema
            // WHY: This ensures we check permissions for every single action defined
            // specifically for this tenant's environment.
            Result<Record2<String, String>>
                    resourceActions =
                    dsl.select(field("resource_key", String.class), field("action_name", String.class))
                            .from(table(name(schema, "tbl_resource_actions")))
                            .fetch();

            Map<String, Boolean> permissions = new LinkedHashMap<>();

            // 5. Evaluate Casbin rules
            // We use the internal tenant UUID for the Casbin domain check to ensure isolation.
            for (Record2<String, String> record : resourceActions) {
                String key = record.value1();
                String action = record.value2();
                if (casbinService.canDo(userId, internalTenantId, schema, key, action)) {
                    permissions.put(key + ":" + action, true);
                }
            }

            log.info("✅ Permissions successfully mapped for ToolJet Workspace: {} (Tenant: {})",
                    organisationId,
                    internalTenantId);

            return ResponseEntity.ok(Map.of("userId",
                    userId,
                    "organisationId",
                    organisationId,
                    "tenantId",
                    internalTenantId,
                    "permissions",
                    permissions));

        } catch (Exception e) {
            log.error("❌ Failed to fetch internal permissions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @Data public static class ToolJetUploadRequest {
        private String fileName;
        private String mimeType;
        private String base64Data;
        private String folderId;
    }
}