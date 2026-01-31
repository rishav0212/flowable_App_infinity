package com.example.flowable_app.controller;

import com.example.flowable_app.service.ToolJetAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ToolJetBffController {

    private final ToolJetAuthService authService;
    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${tooljet.internal.url:http://localhost:8082}")
    private String tooljetUrl; // ✅ Use the property, not hardcoded string

    @PostMapping("/api/tooljet/embed-ticket")
    public ResponseEntity<?> getTicket(
            @RequestParam String appId, @AuthenticationPrincipal Map<String, Object> details) {
        String userId = (String) details.get("id");
        String email = (String) details.get("email");
        String ticket = authService.generateTicket(userId, email, appId);

        return ResponseEntity.ok(Map.of(
                "ticket", ticket,
                "iframeUrl", "/tooljet/ticket/" + ticket + "/applications/" + appId
        ));
    }


    @RequestMapping(
            value = {
                    "/tooljet/ticket/{ticket}/**",
                    "/api/**",
                    "/assets/**",
                    "/*.js", "/*.css", "/*.svg", "/*.json", "/*.woff2",
                    "/applications/**",
                    "/run/**"
            },
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE},
            headers = "!Authorization"
    )
    public ResponseEntity<byte[]> proxy(
            @PathVariable(required = false) String ticket,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request,
            HttpServletResponse response,
            @CookieValue(name = "TJ_BFF_SESSION", required = false) String bffSession) {

        String userEmail = bffSession;
        String fullPath = request.getRequestURI();
        String targetPath = fullPath;

        // 1. Initial Entry via Ticket
        if (ticket != null) {
            ToolJetAuthService.TicketInfo info = authService.validateTicket(ticket);
            if (info == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            userEmail = info.email();

            // 🛑 FIX: Use 'Lax' and 'secure(false)' for localhost HTTP
            ResponseCookie cookie = ResponseCookie.from("TJ_BFF_SESSION", userEmail)
                    .httpOnly(true)
                    .secure(false) // Change to true + SameSite=None only for Production HTTPS
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(3600)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            // Extract the clean path: /applications/uuid...
            targetPath = fullPath.substring(fullPath.indexOf("/applications/"));

            // 2. Fetch the ToolJet HTML and inject the "URL Fixer"
            ResponseEntity<byte[]> tooljetRes = executeProxyWithRetry(targetPath, request, userEmail, body);
            return injectUrlFixerScript(tooljetRes, targetPath);
        }

        // 3. Subsequent requests (Assets, APIs, JSON)
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return executeProxyWithRetry(targetPath, request, userEmail, body);
    }

    /**
     * 💉 THE MAGIC FIX: Injects a script into the HTML to tell ToolJet's
     * internal React Router exactly which app it should be displaying.
     */
    private ResponseEntity<byte[]> injectUrlFixerScript(ResponseEntity<byte[]> response, String cleanPath) {
        if (response.getBody() == null) return response;

        String html = new String(response.getBody());

        // This script changes the URL inside the iframe from:
        // /tooljet/ticket/T123/applications/ABC -> /applications/ABC
        // Without this, the ToolJet frontend stays on a "White Page"
        String script = "<script>" +
                "window.history.replaceState({}, '', '" + cleanPath + "');" +
                "console.log('✅ ToolJet Path Synchronized to: " + cleanPath + "');" +
                "</script>";

        String modifiedHtml = html.replace("<head>", "<head>" + script);

        return new ResponseEntity<>(modifiedHtml.getBytes(), response.getHeaders(), response.getStatusCode());
    }
    private ResponseEntity<byte[]> executeProxyWithRetry(String path, HttpServletRequest request, String userEmail, byte[] body) {
        try {
            return forwardRequest(path, request, userEmail, body);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("🔄 ToolJet session expired. Refreshing and retrying...");
                authService.clearSession();
                return forwardRequest(path, request, userEmail, body);
            }
            throw e;
        }
    }


    private ResponseEntity<byte[]> forwardRequest(String path, HttpServletRequest request, String userEmail, byte[] body) {
        String correctOrgId = "d776c8cf-3808-4a91-9525-6b0982f8b4d3";

        // 1. Build the target URI
        URI targetUri = UriComponentsBuilder.fromHttpUrl(tooljetUrl)
                .path(path)
                .query(request.getQueryString())
                .replaceQueryParam("workspaceSlug", correctOrgId)
                .queryParam("current_user_email", userEmail)
                .build(true).toUri();

        // 2. Prepare Request Headers
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, authService.getToolJetSession());
        headers.add("tj-workspace-id", correctOrgId);
        headers.add("x-tooljet-workspace-id", correctOrgId);
        headers.add("X-Forwarded-Host", "localhost:8080");
        headers.add("X-Forwarded-Proto", "http");

        // ✅ ADDED: Pass through 'If-None-Match' to ToolJet for ETag validation
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null) {
            headers.add(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }

        if (request.getContentType() != null) {
            headers.setContentType(MediaType.parseMediaType(request.getContentType()));
        }

        // 3. Execute Exchange
        ResponseEntity<byte[]> tooljetRes = restTemplate.exchange(
                targetUri,
                HttpMethod.valueOf(request.getMethod()),
                new HttpEntity<>(body, headers),
                byte[].class
        );

        // ✅ PERFORMANCE FIX: Handle 304 Not Modified from ToolJet
        if (tooljetRes.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            return new ResponseEntity<>(null, tooljetRes.getHeaders(), HttpStatus.NOT_MODIFIED);
        }

        byte[] responseBody = tooljetRes.getBody();

        // 4. THE MIRROR: Rewrite any internal URLs in JSON responses
        if (responseBody != null && tooljetRes.getHeaders().getContentType() != null
                && tooljetRes.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON)) {
            String json = new String(responseBody);
            if (json.contains("localhost:8082")) {
                json = json.replace("localhost:8082", "localhost:8080");
                responseBody = json.getBytes();
            }
        }

        // 5. Cleanup Security Headers & Preserve Performance Headers
        HttpHeaders resHeaders = new HttpHeaders();

        // ✅ FIX: Copy essential performance headers back to the browser
        if (tooljetRes.getHeaders().getContentType() != null) {
            resHeaders.setContentType(tooljetRes.getHeaders().getContentType());
        }
        if (tooljetRes.getHeaders().getETag() != null) {
            resHeaders.setETag(tooljetRes.getHeaders().getETag());
        }
        if (tooljetRes.getHeaders().getCacheControl() != null) {
            resHeaders.setCacheControl(tooljetRes.getHeaders().getCacheControl());
        }
        // Required so browser knows how much data to expect
        if (responseBody != null) {
            resHeaders.setContentLength(responseBody.length);
        }

        // Remove restrictive policies
        resHeaders.remove("X-Frame-Options");
        resHeaders.remove("Content-Security-Policy");
        resHeaders.remove("Cross-Origin-Opener-Policy");
        resHeaders.remove("Cross-Origin-Resource-Policy");

        // Add Permissive policies for embedding
        resHeaders.add("X-Frame-Options", "ALLOWALL");
        resHeaders.add("Content-Security-Policy", "frame-ancestors *");
        resHeaders.setAccessControlAllowOrigin("http://localhost:5173");
        resHeaders.setAccessControlAllowCredentials(true);

        return new ResponseEntity<>(responseBody, resHeaders, tooljetRes.getStatusCode());
    }}