package com.example.flowable_app.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service("email") // 🟢 Accessible in BPMN as ${email}
@Slf4j
public class FlowableEmailService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.email.service-url}")
    private String serviceUrl;

    @Value("${app.email.default-from}")
    private String defaultFrom;

    @Value("${app.email.default-name}")
    private String defaultName;

    /**
     * Sends a simple email.
     * Usage in BPMN: ${email.send('user@example.com', 'Subject', 'Body Content')}
     */
    public void send(String to, String subject, String body) {
        sendExtended(to, subject, body, null, null);
    }

    /**
     * Sends an email with attachments and BCC.
     * Usage in BPMN: ${email.sendExtended(to, sub, body, bccList, attachments)}
     */
    public void sendExtended(String to, String subject, String body, String bcc, List<Map<String, String>> attachments) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("from_email", defaultFrom);
            payload.put("from_name", defaultName);
            payload.put("to_email", to);
            payload.put("subject", subject);
            payload.put("body", body);

            if (bcc != null) payload.put("bcc", bcc);
            if (attachments != null) payload.put("attachments", attachments);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(serviceUrl, entity, String.class);

            log.info("📧 Email sent successfully to: [{}] with subject: [{}]", to, subject);
        } catch (Exception e) {
            log.error("❌ Failed to send email to [{}]: {}", to, e.getMessage());
            // We don't throw here to avoid breaking the process, or you can throw to trigger a retry
        }
    }
}