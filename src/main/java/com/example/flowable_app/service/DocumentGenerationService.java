package com.example.flowable_app.service;

import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import io.reflectoring.docxstamper.DocxStamper;
import io.reflectoring.docxstamper.DocxStamperConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jxls.common.Context;
import org.jxls.util.JxlsHelper;
import org.springframework.context.expression.MapAccessor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * THE "ONE-STOP SHOP" FOR DOCUMENT GENERATION.
 * Handles:
 * 1. Word (.docx) - via DocxStamper (Smart logic)
 * 2. Excel (.xlsx) - via Jxls (Smart logic)
 * 3. PDF (.pdf) - via Google Drive Conversion
 * 4. Email (.html) - via Thymeleaf
 */
@Service("docGen") // 🟢 Accessible in BPMN as ${docGen}
@Slf4j @RequiredArgsConstructor public class DocumentGenerationService {

    private final GoogleDriveService driveService;
    private final TemplateEngine emailTemplateEngine;

    // ==================================================================================
    // 📄 1. WORD DOCUMENTS (.docx)
    // ==================================================================================
    public String generateWord(String templateId, String targetFolderId, String fileName, Map<String, Object> data) {
        try {
            log.info("📄 Generating Word Doc [{}]...", fileName);
            InputStream templateStream = driveService.downloadFile(templateId);

            // 🟢 CONFIGURATION: Enable "MapAccessor"
            // This tells the engine: "If you see a Map, allow access like map.key"
            // This fixes the "Property 'date' not found on LinkedHashMap" error.

            DocxStamperConfiguration
                    config =
                    new DocxStamperConfiguration().setEvaluationContextConfigurer(ctx -> ctx.addPropertyAccessor(new MapAccessor()));

            // Use <Object> so we can pass the raw Map as the root object
            DocxStamper<Object> stamper = new DocxStamper<>(config);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            // 🟢 Pass the Map directly (No wrapper needed)
            stamper.stamp(templateStream, data, outputStream);
            templateStream.close();

            return uploadStream(outputStream,
                    fileName + ".docx",
                    targetFolderId,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } catch (Exception e) {
            log.error("❌ Word Gen Error", e);
            throw new RuntimeException("Word Generation Failed: " + e.getMessage());
        }
    }

    // ==================================================================================
    // 📊 2. EXCEL SPREADSHEETS (.xlsx)
    // ==================================================================================
    public String generateExcel(String templateId, String targetFolderId, String fileName, Map<String, Object> data) {
        try {
            log.info("📊 Generating Excel [{}]...", fileName);
            InputStream templateStream = driveService.downloadFile(templateId);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Context context = new Context();
            if (data != null) data.forEach(context::putVar);

            JxlsHelper.getInstance().processTemplate(templateStream, outputStream, context);
            templateStream.close();

            return uploadStream(outputStream,
                    fileName + ".xlsx",
                    targetFolderId,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (Exception e) {
            throw new RuntimeException("Excel Generation Failed: " + e.getMessage());
        }
    }

    // ==================================================================================
    // 📑 3. PDF GENERATION (Word -> PDF)
    // ==================================================================================
    public String generatePdf(String templateId, String targetFolderId, String fileName, Map<String, Object> data) {
        log.info("📑 Starting PDF Generation for [{}]...", fileName);

        // Step 1: Generate the intermediate Word Document
        String tempWordId = generateWord(templateId, targetFolderId, fileName + "_temp", data);

        // Step 2: Convert that Word Doc to PDF
        String pdfId = convertFileToPdf(tempWordId, targetFolderId, fileName);

        // Step 3: (Optional) Delete temp file (Requires deleteFile method in GoogleDriveService)
        // driveService.deleteFile(tempWordId);

        return pdfId;
    }

    public String convertFileToPdf(String sourceFileId, String targetFolderId, String fileName) {
        try {
            Drive drive = driveService.getDriveService();
            ByteArrayOutputStream pdfStream = new ByteArrayOutputStream();

            // Export logic: Ask Google to give us the file as PDF
            drive.files().export(sourceFileId, "application/pdf").executeMediaAndDownloadTo(pdfStream);

            return uploadStream(pdfStream, fileName + ".pdf", targetFolderId, "application/pdf");
        } catch (Exception e) {
            throw new RuntimeException("PDF Conversion Failed: " + e.getMessage());
        }
    }

    // ==================================================================================
    // 📧 4. EMAIL RENDERING (HTML)
    // ==================================================================================
    public String renderEmail(String templateName, Map<String, Object> data) {
        log.info("📧 Rendering Email Template: [{}]", templateName);
        org.thymeleaf.context.Context context = new org.thymeleaf.context.Context();
        if (data != null) {
            context.setVariables(data);
        }
        return emailTemplateEngine.process(templateName, context);
    }

    // ==================================================================================
    // 🛠 INTERNAL HELPER
    // ==================================================================================
    private String uploadStream(ByteArrayOutputStream outputStream, String name, String folderId, String mimeType) throws
            IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        Drive service = driveService.getDriveService();

        File fileMetadata = new File();
        fileMetadata.setName(name);
        fileMetadata.setParents(Collections.singletonList(folderId));

        InputStreamContent mediaContent = new InputStreamContent(mimeType, inputStream);

        File
                file =
                service.files()
                        .create(fileMetadata, mediaContent)
                        .setFields("id, webViewLink")
                        .setSupportsAllDrives(true)
                        .execute();

        log.info("✅ Created File: {} [ID: {}]", name, file.getId());
        return file.getId();
    }
}