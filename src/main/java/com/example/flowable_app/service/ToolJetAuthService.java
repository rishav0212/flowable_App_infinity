package com.example.flowable_app.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ToolJetAuthService {

    private final Map<String, TicketInfo> ticketStore = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${tooljet.internal.url:http://localhost:8082}")
    private String tooljetUrl;
    @Value("${tooljet.admin.email}")
    private String systemEmail;
    @Value("${tooljet.admin.password}")
    private String systemPassword;
    @Value("${tooljet.organization.id}")
    private String organizationId;
    private String cachedSessionCookie;

    public String generateTicket(String userId, String email, String appId) {
        String ticket = UUID.randomUUID().toString();
        ticketStore.put(ticket, new TicketInfo(userId, email, appId, System.currentTimeMillis() + 60000));
        return ticket;
    }

    public TicketInfo validateTicket(String ticket) {
        TicketInfo info = ticketStore.remove(ticket);
        if (info != null && info.expiresAt > System.currentTimeMillis()) {
            return info;
        }
        return null;
    }

    public void clearSession() {
        this.cachedSessionCookie = null;
    }

    public String getToolJetSession() {
        if (cachedSessionCookie != null) return cachedSessionCookie;

        try {
            String loginUrl = String.format("%s/api/authenticate/%s", tooljetUrl, organizationId);
            log.info("🔐 Attempting ToolJet Auth for: {}", systemEmail);

            // Use a Map to ensure clean JSON structure
            Map<String, String> payload = new HashMap<>();
            payload.put("email", systemEmail);
            payload.put("password", systemPassword);
            payload.put("redirectTo", "/home"); // Match the browser trace exactly

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Add User-Agent to mimic a real browser if ToolJet is being picky
            headers.add(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0");

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(loginUrl, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
                if (cookies != null && !cookies.isEmpty()) {
                    this.cachedSessionCookie = cookies.stream()
                            .map(c -> c.split(";")[0])
                            .collect(java.util.stream.Collectors.joining("; "));
                    log.info("✅ ToolJet Session Established with cookies: {}", cachedSessionCookie);
                    return cachedSessionCookie;
                }

            }
        } catch (HttpStatusCodeException e) {
            // This will print the EXACT reason ToolJet is saying No
            log.error("❌ ToolJet Auth Failed ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ Connection Error: {}", e.getMessage());
        }
        return null;
    }


    // 2. Add this map inside the class
    private final Map<String, String> activeBffSessions = new ConcurrentHashMap<>();

// 3. Add these two methods
    /**
     * Generates a random UUID, links it to the email, and stores it in memory.
     */
    public String promoteTicketToSession(String email) {
        String sessionId = UUID.randomUUID().toString();
        activeBffSessions.put(sessionId, email);
        log.info("🎫 Issued Wristband UUID: {} for user: {}", sessionId, email);
        return sessionId;
    }

    /**
     * Returns the email associated with a UUID, or null if invalid.
     */
    public String getEmailFromSession(String sessionId) {
        return activeBffSessions.get(sessionId);
    }
    public record TicketInfo(String userId, String email, String appId, long expiresAt) {
    }
}