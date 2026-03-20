package com.example.flowable_app.service;

import com.example.flowable_app.entity.ToolJetWorkspace;
import com.example.flowable_app.repository.ToolJetWorkspaceRepository;
import lombok.RequiredArgsConstructor;
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

/**
 * Service responsible for handling secure, multi-tenant authentication with ToolJet.
 * <p>
 * ARCHITECTURE NOTES:
 * 1. This service acts as the "BFF" (Backend for Frontend) Auth Manager.
 * 2. It holds the "Master Keys" (Admin Credentials) in the Database but never exposes them.
 * 3. It issues temporary "Tickets" to the frontend, which are exchanged for "Sessions".
 * 4. It maintains isolation: Tenant A's session is never used for Tenant B.
 */
@Service
@Slf4j
@RequiredArgsConstructor // Auto-injects final fields (Repository)
public class ToolJetAuthService {

    private final ToolJetWorkspaceRepository workspaceRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    /**
     * Cache for ToolJet Session Cookies, keyed by Tenant ID.
     * <p>
     * WHY: ToolJet login takes ~300ms. We cannot afford to login on every single image/API request.
     * We login ONCE per tenant, cache the cookie here, and reuse it for subsequent requests.
     * ConcurrentHashMap is used because multiple users from different tenants hit this simultaneously.
     */
    private final Map<String, String> tenantSessionStore = new ConcurrentHashMap<>();

    // ==================================================================================
    // 🧠 IN-MEMORY STATE MANAGEMENT (Why we use Maps instead of Redis/DB here)
    // ==================================================================================
    /**
     * Stores temporary "One-Time Use" tickets.
     * <p>
     * FLOW: Frontend asks for a ticket -> We generate UUID -> Frontend loads Iframe with UUID ->
     * Backend validates UUID -> We delete UUID immediately.
     */
    private final Map<String, TicketInfo> ticketStore = new ConcurrentHashMap<>();
    /**
     * "Wristband" Storage: Maps a Browser Session UUID to a User & Tenant.
     * <p>
     * WHY: After the initial Iframe load, the generic "Ticket" is gone.
     * The browser needs a way to prove "I am User X from Tenant Y" on every subsequent AJAX request
     * without sending the actual ToolJet Admin Cookie to the browser.
     */
    private final Map<String, SessionData> activeBffSessions = new ConcurrentHashMap<>();
    // The internal URL is usually static (e.g., k8s service DNS), but credentials change per tenant.
    @Value("${tooljet.internal.url:https://infinity-plus-tooljet-493376716946.asia-south2.run.app}")
    private String tooljetUrl;


    // ==================================================================================
    // 🎫 TICKET GENERATION (Step 1)
    // ==================================================================================

    /**
     * Generates a secure, random ticket that binds a specific User to a specific Tenant Context.
     * This ticket allows the frontend to request an embed URL without knowing the App ID or Tenant Secrets.
     *
     * @param userId   The ID of the user in Flowable
     * @param email    The email of the user (used for audit/logging)
     * @param appId    The *REAL* ToolJet App UUID (lookup happened before this call)
     * @param tenantId The Tenant this user belongs to (Crucial for isolation)
     * @return A random UUID string
     */
    public String generateTicket(String userId, String email, String appId, String tenantId) {
        String ticket = UUID.randomUUID().toString();
        // Ticket expires in 60 seconds. It is only meant for the initial handshake.
        ticketStore.put(ticket, new TicketInfo(userId, email, appId, tenantId, System.currentTimeMillis() + 60000));
        return ticket;
    }

    /**
     * Validates and *consumes* a ticket.
     * One-time use policy: The ticket is removed from the map immediately to prevent replay attacks.
     */
    public TicketInfo validateTicket(String ticket) {
        TicketInfo info = ticketStore.remove(ticket); // Remove immediately (Atomic operation)
        if (info != null && info.expiresAt > System.currentTimeMillis()) {
            return info;
        }
        return null;
    }

    // ==================================================================================
    // 🔐 MULTI-TENANT AUTHENTICATION (The Core Logic)
    // ==================================================================================

    /**
     * Retrieves a valid ToolJet Session Cookie for a specific Tenant.
     * <p>
     * LOGIC:
     * 1. Check Memory Cache: Do we already have a session for "Tenant-A"?
     * 2. If Yes: Return it immediately (0ms latency).
     * 3. If No: Perform the expensive DB Lookup + HTTP Login operation.
     *
     * @param tenantId The tenant we need to impersonate/access.
     * @return The raw "Cookie: ..." string header.
     */
    public String getToolJetSession(String tenantId) {
        // Fast path: Check cache
        if (tenantSessionStore.containsKey(tenantId)) {
            return tenantSessionStore.get(tenantId);
        }
        // Slow path: Login
        return loginAndCache(tenantId);
    }

    /**
     * Forces a re-login for a specific tenant.
     * Useful if ToolJet returns a 401 (Unauthorized) because the cookie expired.
     */
    public void clearSession(String tenantId) {
        tenantSessionStore.remove(tenantId);
    }

    /**
     * The heavy lifting: Looks up DB credentials and calls ToolJet's Login API.
     * <p>
     * SYNCHRONIZED: Prevents "Thundering Herd" problem. If 10 users from "Tenant-A"
     * hit the server at once, we only want to perform ONE login request, not 10.
     */
    private synchronized String loginAndCache(String tenantId) {
        // Double-checked locking to ensure we didn't just login in another thread
        if (tenantSessionStore.containsKey(tenantId)) return tenantSessionStore.get(tenantId);

        try {
            // 1. Fetch Dynamic Credentials from the Database
            // We moved away from application.properties to allow unlimited tenants.
            ToolJetWorkspace config = workspaceRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new RuntimeException("No ToolJet Config found for tenant: " + tenantId));

            // 2. Construct the Login URL using the Tenant's specific Workspace UUID
            String loginUrl = String.format("%s/api/authenticate", tooljetUrl);
            log.info("🔐 Authenticating Tenant [{}] to Workspace [{}]...", tenantId, config.getSlug());

            // 3. Prepare Login Payload
            Map<String, String> payload = new HashMap<>();
            payload.put("email", config.getViewerEmail());     // Specific Admin for this workspace
            payload.put("password", config.getViewerPassword()); // Encrypted password
            payload.put("redirectTo", "/");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.USER_AGENT, "Flowable-BFF-Agent");

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(payload, headers);

            // 4. Execute HTTP POST
            ResponseEntity<String> response = restTemplate.postForEntity(loginUrl, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
                if (cookies != null && !cookies.isEmpty()) {
                    // Extract the raw cookie string (e.g., "connect.sid=s%3A...")
                    String cookieValue = cookies.stream()
                            .map(c -> c.split(";")[0])
                            .collect(java.util.stream.Collectors.joining("; "));

                    // 5. Cache success!
                    tenantSessionStore.put(tenantId, cookieValue);
                    log.info("✅ Session established for Tenant [{}]", tenantId);
                    return cookieValue;
                }
            }
        } catch (HttpStatusCodeException e) {
            log.error("❌ Auth Failed for Tenant [{}] ({}): {}",
                    tenantId,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ Connection Error for Tenant [{}]: {}", tenantId, e.getMessage());
        }
        return null;
    }

    // ==================================================================================
    // 🔗 SESSION PROMOTION (Step 2 - The Wristband)
    // ==================================================================================

    /**
     * Converts a validated Ticket into a long-lived "BFF Session".
     * <p>
     * ANALOGY: The "Ticket" is your printed concert ticket (scanned once at the door).
     * The "Session ID" is the Wristband you get after scanning. You show the wristband
     * to buy drinks (proxy requests) without needing the printed ticket again.
     *
     * @param email    User's email
     * @param tenantId The verified Tenant ID this session belongs to.
     * @return A new UUID (The "Wristband")
     */
    public String promoteTicketToSession(String email, String tenantId) {
        String sessionId = UUID.randomUUID().toString();
        // We store BOTH email and tenantId. This ensures that when a request comes in
        // with this session ID, we know exactly which Tenant's cache to use.
        activeBffSessions.put(sessionId, new SessionData(email, tenantId));
        log.info("🎫 Issued Wristband UUID: {} for user: {} (Tenant: {})", sessionId, email, tenantId);
        return sessionId;
    }

    public SessionData getSessionData(String sessionId) {
        return activeBffSessions.get(sessionId);
    }

    // ==================================================================================
    // 📦 DATA STRUCTURES
    // ==================================================================================

    /**
     * Holds context during the Ticket phase (initial handshake).
     */
    public record TicketInfo(String userId, String email, String appId, String tenantId, long expiresAt) {
    }

    /**
     * Holds context during the Proxy phase (post-handshake).
     */
    public record SessionData(String email, String tenantId) {
    }
}