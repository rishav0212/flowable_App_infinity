package com.example.flowable_app.service;

import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

@Service
public class GoogleDriveService {

    private static final String APPLICATION_NAME = "InfinityServices";

    @Value("${google.drive.credentials.path:}")
    private String credentialsPath;

    private Drive getDriveService() throws IOException {
        GoogleCredentials credentials;

        // 🟢 FIX: Logic to handle External File vs Internal Resource vs Default
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            // Case 1: Path provided (Production / Mounted Secret)
            try (InputStream keyStream = new FileInputStream(credentialsPath)) {
                credentials = GoogleCredentials.fromStream(keyStream);
                System.out.println("✅ Loaded Drive Credentials from file system: " + credentialsPath);
            } catch (IOException e) {
                System.err.println("⚠️ Failed to load credentials from path: " +
                        credentialsPath +
                        ". Falling back to default.");
                credentials = GoogleCredentials.getApplicationDefault();
            }
        } else {
            // Case 2: No path provided (Dev / Fallback)
            // Try to find "google-drive-key.json" inside the JAR (Classpath)
            InputStream keyFile = getClass().getResourceAsStream("/google-drive-key.json");
            if (keyFile != null) {
                credentials = GoogleCredentials.fromStream(keyFile);
                System.out.println("✅ Loaded Drive Credentials from classpath.");
            } else {
                // Case 3: Cloud Run Default Identity
                credentials = GoogleCredentials.getApplicationDefault();
                System.out.println("ℹ️ Using Application Default Credentials.");
            }
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
     */
    public String uploadFile(MultipartFile multipartFile, String customFileName, String targetFolderId) throws
            IOException {
        if (targetFolderId == null || targetFolderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target Folder ID is required.");
        }

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
                .setFields("id")
                .setSupportsAllDrives(true)
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
                .setFields("id, name, mimeType, size")
                .setSupportsAllDrives(true)
                .execute();
    }
}