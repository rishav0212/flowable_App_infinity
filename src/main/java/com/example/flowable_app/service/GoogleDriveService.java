package com.example.flowable_app.service;

import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * Service for handling interactions with the Google Drive API.
 * <p>
 * This service manages authentication using a robust fallback mechanism
 * (Secret Mount > Classpath Resource > Application Default) and handles
 * file uploads, downloads, and metadata retrieval.
 * </p>
 */
@Service
@Slf4j
public class GoogleDriveService {

    private static final String APPLICATION_NAME = "InfinityServices";

    /**
     * Path to the service account JSON key.
     * Defaults to the Cloud Run secret mount path if not specified in properties.
     */
    @Value("${google.drive.credentials.path:/app/secrets/drive-sa-key.json}")
    private String credentialsPath;

    /**
     * Initializes and returns an authenticated Drive service instance.
     * <p>
     * It attempts to load credentials in the following order:
     * 1. Specific File Path (Production/Secret Mount)
     * 2. Classpath Resource (Local Dev fallback)
     * 3. Google Application Default Credentials (GCP Environment)
     * </p>
     *
     * @return Authenticated Drive client
     * @throws IOException If authentication fails at all levels.
     */
    public Drive getDriveService() throws IOException {
        GoogleCredentials credentials;

        // 🟢 Logic: External File vs Internal Resource vs Default
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            // Case 1: Path provided (Production / Mounted Secret)
            try (InputStream keyStream = new FileInputStream(credentialsPath)) {
                credentials = GoogleCredentials.fromStream(keyStream);
                log.info("✅ AUTH: Loaded Drive Credentials from file system: {}", credentialsPath);
            } catch (IOException e) {
                log.warn("⚠️ AUTH: Failed to load credentials from path: [{}]. Falling back to default logic.", credentialsPath);
                // Fallback will be handled below implicitly if we re-assign,
                // but strictly following your logic structure, we assign Default here if file fails
                credentials = GoogleCredentials.getApplicationDefault();
                log.info("ℹ️ AUTH: Fallback to Application Default Credentials.");
            }
        } else {
            // Case 2: No path provided (Dev / Fallback)
            // Try to find "google-drive-key.json" inside the JAR (Classpath)
            InputStream keyFile = getClass().getResourceAsStream("/google-drive-key.json");
            if (keyFile != null) {
                credentials = GoogleCredentials.fromStream(keyFile);
                log.info("✅ AUTH: Loaded Drive Credentials from classpath resource.");
            } else {
                // Case 3: Cloud Run Default Identity
                credentials = GoogleCredentials.getApplicationDefault();
                log.info("ℹ️ AUTH: Using Google Application Default Credentials (ADC).");
            }
        }

        // Scope: grants access to files created or opened by the app
        credentials = credentials.createScoped(Collections.singleton(DriveScopes.DRIVE));

        return new Drive.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * Uploads a file to a specific Google Drive Folder.
     *
     * @param multipartFile  The file object from the HTTP request.
     * @param customFileName The name to be saved in Drive (usually randomized).
     * @param targetFolderId The ID of the Drive folder where the file will be stored.
     * @return The Google Drive File ID of the uploaded document.
     * @throws IOException If the upload fails or the folder is inaccessible.
     */
    public String uploadFile(MultipartFile multipartFile, String customFileName, String targetFolderId) throws IOException {
        if (targetFolderId == null || targetFolderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target Folder ID is required for upload.");
        }

        log.debug("🚀 START: Uploading file [{}] to Folder [{}]", customFileName, targetFolderId);

        Drive service = getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(customFileName);
        fileMetadata.setParents(Collections.singletonList(targetFolderId));

        InputStreamContent mediaContent = new InputStreamContent(
                multipartFile.getContentType(),
                multipartFile.getInputStream()
        );

        File file = service.files()
                .create(fileMetadata, mediaContent)
                .setFields("id") // Only return the ID to save bandwidth
                .setSupportsAllDrives(true)
                .execute();

        log.info("✅ END: Upload Successful. New File ID: [{}]", file.getId());
        return file.getId();
    }



    /**
     * Downloads a file stream from Google Drive.
     *
     * @param fileId The Google Drive File ID.
     * @return InputStream of the file content.
     * @throws IOException If the file cannot be found or read.
     */
    public InputStream downloadFile(String fileId) throws IOException {
        Drive service = getDriveService();

        // 1. Check the file type first
        File fileMetadata = service.files().get(fileId)
                .setFields("mimeType, name")
                .setSupportsAllDrives(true)
                .execute();

        String mimeType = fileMetadata.getMimeType();
        log.info("📥 Fetching file [{}] (Type: {})", fileMetadata.getName(), mimeType);

        // 2. Handle Google Docs (Export instead of Download)
        if ("application/vnd.google-apps.document".equals(mimeType)) {
            log.info("🔄 Auto-exporting Google Doc to .docx for processing...");
            return service.files()
                    .export(fileId, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .executeMediaAsInputStream();
        }
        // 3. Handle Google Sheets (Export instead of Download)
        else if ("application/vnd.google-apps.spreadsheet".equals(mimeType)) {
            log.info("🔄 Auto-exporting Google Sheet to .xlsx for processing...");
            return service.files()
                    .export(fileId, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .executeMediaAsInputStream();
        }
        // 4. Handle Regular Files (PDFs, Uploaded .docx, Images)
        else {
            return service.files().get(fileId)
                    .setSupportsAllDrives(true)
                    .executeMediaAsInputStream();
        }
    }

    /**
     * Retrieves metadata (name, mimeType, size) for a specific file.
     *
     * @param fileId The Google Drive File ID.
     * @return The Drive File object containing metadata.
     * @throws IOException If the file cannot be found.
     */
    public File getFileMetadata(String fileId) throws IOException {
        log.debug("🔍 START: Fetching metadata for File ID [{}]", fileId);
        Drive service = getDriveService();

        File file = service.files()
                .get(fileId)
                // ✅ IMPORTANT: 'webViewLink' MUST be in this string
                .setFields("id, name, mimeType, size, webViewLink")
                .setSupportsAllDrives(true)
                .execute();

        log.debug("✅ END: Metadata retrieved for file: [{}]", file.getName());
        return file;
    }
}