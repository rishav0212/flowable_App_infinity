package com.example.flowable_app.service;

import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

@Service public class GoogleDriveService {

    // ⚠️ Make sure this file exists in src/main/resources
    private static final String CREDENTIALS_FILE_PATH = "/google-drive-key.json";
    private static final String APPLICATION_NAME = "FlowableApp";

    private Drive getDriveService() throws IOException {
        GoogleCredentials credentials;
        try {
            // 1. Try local file
            InputStream keyFile = getClass().getResourceAsStream(CREDENTIALS_FILE_PATH);
            if (keyFile != null) {
                credentials = GoogleCredentials.fromStream(keyFile);
            } else {
                // 2. Fallback to Cloud Run Identity
                credentials = GoogleCredentials.getApplicationDefault();
            }
        } catch (IOException e) {
            credentials = GoogleCredentials.getApplicationDefault();
        }

        credentials = credentials.createScoped(Collections.singleton(DriveScopes.DRIVE_FILE));

        return new Drive.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
    /**
     * Uploads a file to a specific Google Drive Folder.
     * * @param multipartFile The file content to upload.
     *
     * @param customFileName The name to save the file as in Drive.
     * @param targetFolderId REQUIRED. The ID of the destination folder in Google Drive.
     * @return The ID of the uploaded file.
     * @throws IOException              If Google API fails.
     * @throws IllegalArgumentException If targetFolderId is missing.
     */
    public String uploadFile(MultipartFile multipartFile, String customFileName, String targetFolderId) throws
            IOException {
        // 🟢 VALIDATION: Fail if no folder ID is provided (Strict Mode)
        if (targetFolderId == null || targetFolderId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Target Folder ID is required. Please check your Form.io URL configuration.");
        }

        Drive service = getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(customFileName);

        // 🟢 USE PASSED FOLDER ID
        fileMetadata.setParents(Collections.singletonList(targetFolderId));

        InputStreamContent
                mediaContent =
                new InputStreamContent(multipartFile.getContentType(), multipartFile.getInputStream());

        File
                file =
                service.files()
                        .create(fileMetadata, mediaContent)
                        .setFields("id")
                        .setSupportsAllDrives(true) // Important for Shared Drives
                        .execute();

        return file.getId();
    }

    /**
     * Downloads a file stream from Google Drive
     */
    public InputStream downloadFile(String fileId) throws IOException {
        Drive service = getDriveService();
        return service.files().get(fileId).executeMediaAsInputStream();
    }

    /**
     * Retrieves metadata (name, mimeType, size) for a file.
     */
    public File getFileMetadata(String fileId) throws IOException {
        Drive service = getDriveService();
        return service.files()
                .get(fileId)
                .setFields("id, name, mimeType, size") // Ask Google for these specific details
                .setSupportsAllDrives(true)            // Important for Shared Drives
                .execute();
    }
}