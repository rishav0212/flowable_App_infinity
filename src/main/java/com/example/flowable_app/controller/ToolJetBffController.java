package com.example.flowable_app.controller;

import com.example.flowable_app.entity.ToolJetWorkspace;
import com.example.flowable_app.repository.ToolJetWorkspaceRepository;
import com.example.flowable_app.repository.TooljetWorkspaceAppRepository;
import com.example.flowable_app.service.AllowedUserService;
import com.example.flowable_app.service.ToolJetAuthService;
import com.example.flowable_app.service.UserContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ToolJetBffController {

    private final ToolJetAuthService authService;
    private final AllowedUserService userService;
    private final UserContextService userContextService; // 🟢 Needed for Tenant Context

    // 🟢 New Repositories for Dynamic Lookup
    private final TooljetWorkspaceAppRepository appRepository;
    private final ToolJetWorkspaceRepository workspaceRepository;

    private final RestTemplate restTemplate = new RestTemplate();
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${tooljet.internal.url}")
    private String tooljetUrl;

    // 🔴 Removed hardcoded organizationId. We now look this up per tenant.

    /**
     * Step 1: Frontend asks for a ticket using a generic "Key" (e.g. "hr-dashboard")
     */
    @PostMapping("/api/tooljet/embed-ticket")
    public ResponseEntity<?> getTicket(
            @RequestParam String appKey, // 🟢 Accepts 'appKey' instead of raw 'appId'
            @AuthenticationPrincipal Map<String, Object> details) {

        // 1. Identify User & Tenant from Context
        String tenantId = userContextService.getCurrentTenantId();
        String userId = userContextService.getCurrentUserId();
        String email = userContextService.getCurrentUserEmail();

        // 2. 🔍 LOOKUP: Convert "hr-dashboard" -> "uuid-555-777" for THIS tenant
        String realAppId = appRepository.findAppIdByTenant(tenantId, appKey)
                .orElseThrow(() -> new RuntimeException("App '" + appKey + "' not authorized for tenant: " + tenantId));

        // 3. Generate Ticket (Now binds tenantId)
        String ticket = authService.generateTicket(userId, email, realAppId, tenantId);

        return ResponseEntity.ok(Map.of(
                "ticket", ticket,
                "iframeUrl", "/tooljet/ticket/" + ticket + "/applications/" + realAppId
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

        String userEmail;
        String tenantId; // 🟢 We need this to forward to the correct workspace
        String fullPath = request.getRequestURI();
        String targetPath = fullPath;

        // 1. Initial Entry via Ticket
        if (ticket != null) {
            ToolJetAuthService.TicketInfo info = authService.validateTicket(ticket);
            if (info == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            // 🟢 Promote ticket using Email AND TenantID
            String secureSessionId = authService.promoteTicketToSession(info.email(), info.tenantId());

            userEmail = info.email();
            tenantId = info.tenantId();

            // Fetch the real ID to sync it with the frontend URL
            String verifiedId = userContextService.getCurrentUserId();

            ResponseCookie cookie = ResponseCookie.from("TJ_BFF_SESSION", secureSessionId)
                    .httpOnly(true)
                    .secure(false)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(3600)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            // Result: /applications/<app-id>?userId= ?
            String cleanPathForBrowser = fullPath.substring(fullPath.indexOf("/applications/"))
                    + "?userId=" + verifiedId;

            // Use the modified path for the script injection
            return injectUrlFixerScript(executeProxyWithRetry(targetPath, request, userEmail, body, tenantId),
                    cleanPathForBrowser);
        }

        // 2. Subsequent requests (Assets/APIs)
        if (bffSession != null) {
            // 🟢 Recover Session Data (Email + Tenant)
            ToolJetAuthService.SessionData sessionData = authService.getSessionData(bffSession);

            if (sessionData == null) {
                log.warn("🚨 Invalid or expired TJ_BFF_SESSION: {}", bffSession);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            userEmail = sessionData.email();
            tenantId = sessionData.tenantId();
            targetPath = fullPath;
        } else {
            // 🛑 DENY: No ticket and no session cookie
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return executeProxyWithRetry(targetPath, request, userEmail, body, tenantId);
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

    @PostMapping("/api/data-queries/{queryId}/run")
    public ResponseEntity<?> secureRunQuery(
            @PathVariable String queryId,
            @RequestBody(required = false) Map<String, Object> body,
            @CookieValue(name = "TJ_BFF_SESSION", required = false) String bffSession,
            HttpServletRequest request) {

        // 📝 LOG: Start of Request
        log.info("🔹 [SECURE RUN] Request received for Query ID: {}", queryId);

        // 🟢 Recover Tenant Context
        ToolJetAuthService.SessionData sessionData = authService.getSessionData(bffSession);
        if (sessionData == null) {
            log.warn("❌ [SECURE RUN] Unauthorized: No valid session found.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String userEmail = sessionData.email();
        String tenantId = sessionData.tenantId();

        String trustedUserId = userContextService.getCurrentUserId();
        log.info("👤 [SECURE RUN] Authenticated User: {} (ID: {}) Tenant: {}", userEmail, trustedUserId, tenantId);

        try {
            // 1. Extract Slug (or UUID in new system)
            String appSlug = extractAppSlug(request);
            if (appSlug.isEmpty()) {
                log.warn("⚠️ [SECURE RUN] Could not determine App Slug. Falling back to standard proxy.");
                byte[] jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
                return executeProxyWithRetry(request.getRequestURI(), request, userEmail, jsonBody, tenantId);
            }
            log.info("🐌 [SECURE RUN] App Slug/ID: {}", appSlug);

            // 2. Fetch Metadata (Now passing TenantID to get correct headers)
            Map<String, Object> queryMeta;
            try {
                queryMeta = fetchQueryMetadata(queryId, appSlug, tenantId);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    log.warn("🔄 [SECURE RUN] Session expired. Refreshing...");
                    authService.clearSession(tenantId); // 🟢 Clear specific tenant session
                    queryMeta = fetchQueryMetadata(queryId, appSlug, tenantId);
                } else {
                    throw e;
                }
            }
            log.info("📋 [SECURE RUN] Metadata Fetched. Query Name: {}", queryMeta.get("name"));

            String dataSourceId = (String) queryMeta.get("data_source_id");
            DatasourceConfig dsConfig = fetchDatasourceConfig(dataSourceId, tenantId);
            log.info("🔌 [SECURE RUN] Datasource Config - Kind: {}, Schema: {}", dsConfig.kind, dsConfig.schema);

            String dsKind = (String) queryMeta.get("kind");
            log.info("🔌 [SECURE RUN] Datasource Kind from Definition: {}", dsKind);

            // 3. Proxy Non-Postgres
            if (dsKind == null || !dsKind.toLowerCase().contains("postgres")) {
                log.info("🔀 [SECURE RUN] Kind is '{}'. Proxying to ToolJet.", dsKind);
                byte[] jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
                return executeProxyWithRetry(request.getRequestURI(), request, userEmail, jsonBody, tenantId);
            }

            // 4. Extract SQL
            Map<String, Object> options = (Map<String, Object>) queryMeta.get("options");
            String originalSql = (String) options.get("query");

            if (originalSql == null) {
                log.warn("⚠️ [SECURE RUN] SQL is null! This is likely not a DB query. Proxying.");
                byte[] jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
                return executeProxyWithRetry(request.getRequestURI(), request, userEmail, jsonBody, tenantId);
            }

            log.debug("📜 [SECURE RUN] Raw SQL: {}", originalSql);

            Map<String, Object> params = (body != null && body.containsKey("params"))
                    ? (Map<String, Object>) body.get("params")
                    : new HashMap<>();

            SqlAndBindings prepared = convertToPreparedSql(originalSql, params);
            log.info("🔧 [SECURE RUN] Executing SQL: {}", prepared.sql);

            // 5. Execute with RLS
            List<Map<String, Object>> results = transactionTemplate.execute(status -> {
                if (dsConfig.schema != null && !dsConfig.schema.equalsIgnoreCase("public")) {
                    log.debug("⚙️ [DB] Setting search_path to: {}", dsConfig.schema);
                    jdbcTemplate.execute("SET LOCAL search_path TO " + dsConfig.schema);
                }

                log.debug("⚙️ [DB] Setting RLS user to: {}", trustedUserId);

                jdbcTemplate.queryForObject("SELECT set_config('app.current_user', ?, true)",
                        String.class,
                        trustedUserId);

                return jdbcTemplate.queryForList(prepared.sql, prepared.args);
            });

            log.info("✅ [SECURE RUN] Success. Fetched {} rows.", results != null ? results.size() : 0);
            Map<String, Object> response = new HashMap<>();
            response.put("data", results);
            response.put("status", "ok");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("🚨 [SECURE RUN] Bad Request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("❌ [SECURE RUN] Execution Failed!", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    private String extractAppSlug(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer != null && referer.contains("/applications/")) {
            String[] parts = referer.split("/applications/");
            if (parts.length > 1) {
                String slug = parts[1];
                if (slug.contains("?")) slug = slug.substring(0, slug.indexOf("?"));
                if (slug.contains("/")) slug = slug.substring(0, slug.indexOf("/"));
                return slug;
            }
        }
        return "";
    }

    // 🟢 Updated: Requires tenantId to get correct session/workspace
    private Map<String, Object> fetchQueryMetadata(String queryId, String appSlug, String tenantId) {
        // NOTE: If appSlug is a UUID, this usually works on ToolJet too.
        String url = tooljetUrl + "/api/apps/slugs/" + appSlug;

        // Lookup Workspace Config
        ToolJetWorkspace config = workspaceRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant config missing for: " + tenantId));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, authService.getToolJetSession(tenantId));
        headers.add("tj-workspace-id", config.getWorkspaceUuid()); // 🟢 Dynamic ID
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> appDef = response.getBody();

        if (appDef == null || !appDef.containsKey("data_queries")) {
            throw new RuntimeException("Could not fetch App Definition.");
        }

        List<Map<String, Object>> queries = (List<Map<String, Object>>) appDef.get("data_queries");

        Map<String, Object> matchedQuery = queries.stream()
                .filter(q -> queryId.equals(q.get("id")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Query ID not found"));

        String dsKind = "unknown";
        if (matchedQuery.containsKey("dataSource")) {
            Map<String, Object> dsObj = (Map<String, Object>) matchedQuery.get("dataSource");
            if (dsObj != null && dsObj.containsKey("kind")) {
                dsKind = (String) dsObj.get("kind");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("data_source_id", matchedQuery.get("dataSourceId"));
        result.put("options", matchedQuery.get("options"));
        result.put("name", matchedQuery.get("name"));
        result.put("kind", dsKind);

        return result;
    }

    // 🟢 Updated: Requires tenantId to get correct session/workspace
    private DatasourceConfig fetchDatasourceConfig(String datasourceId, String tenantId) {
        try {
            String url = tooljetUrl + "/api/data_sources/" + datasourceId;

            ToolJetWorkspace config = workspaceRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> new RuntimeException("Tenant config missing"));

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, authService.getToolJetSession(tenantId));
            headers.add("tj-workspace-id", config.getWorkspaceUuid()); // 🟢 Dynamic
            headers.add("x-tooljet-workspace-id", config.getWorkspaceUuid()); // 🟢 Dynamic
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();

            if (body != null) {
                String kind = (String) body.get("kind");
                String schema = "public";

                if (body.containsKey("options")) {
                    Map<String, Object> options = (Map<String, Object>) body.get("options");
                    if (options.containsKey("schema")) schema = (String) options.get("schema");
                    else if (options.containsKey("search_path")) schema = (String) options.get("search_path");
                    else if (options.containsKey("connection_options")) {
                        Map<String, Object> connOptions = (Map<String, Object>) options.get("connection_options");
                        if (connOptions.containsKey("schema")) schema = (String) connOptions.get("schema");
                    }
                }
                return new DatasourceConfig(schema, kind);
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not fetch config for DS [{}].", datasourceId);
        }
        return new DatasourceConfig("public", "unknown");
    }

    private SqlAndBindings convertToPreparedSql(String tooljetSql, Map<String, Object> params) {
        if (tooljetSql == null) return new SqlAndBindings("", new Object[0]);
        Pattern pattern = Pattern.compile("\\{\\{\\s*params\\.(\\w+)\\s*\\}\\}");
        Matcher matcher = pattern.matcher(tooljetSql);
        List<Object> argsList = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            matcher.appendReplacement(sb, "?");
            argsList.add(params.get(paramName));
        }
        matcher.appendTail(sb);
        return new SqlAndBindings(sb.toString(), argsList.toArray());
    }

    private ResponseEntity<byte[]> executeProxyWithRetry(String path, HttpServletRequest request, String userEmail, byte[] body, String tenantId) {
        try {
            return forwardRequest(path, request, userEmail, body, tenantId);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("🔄 ToolJet session expired. Refreshing and retrying...");
                authService.clearSession(tenantId);
                return forwardRequest(path, request, userEmail, body, tenantId);
            }
            throw e;
        }
    }

    // 🟢 Updated: Now accepts TenantID to look up Dynamic Config
    private ResponseEntity<byte[]> forwardRequest(String path, HttpServletRequest request, String userEmail, byte[] body, String tenantId) {

        // 1. 🔍 Fetch Dynamic Config for this Tenant
        ToolJetWorkspace config = workspaceRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant config missing for: " + tenantId));

        // 2. Build the target URI (Using Dynamic Slug)
        URI targetUri = UriComponentsBuilder.fromHttpUrl(tooljetUrl)
                .path(path)
                .query(request.getQueryString())
                .replaceQueryParam("workspaceSlug", config.getSlug()) // 🟢 Dynamic Slug
                .build(true).toUri();

        // 3. Prepare Request Headers (Using Dynamic IDs)
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, authService.getToolJetSession(tenantId)); // 🟢 Tenant Session
        headers.add("tj-workspace-id", config.getWorkspaceUuid()); // 🟢 Dynamic ID
        headers.add("x-tooljet-workspace-id", config.getWorkspaceUuid()); // 🟢 Dynamic ID
        headers.add("X-Forwarded-Host", "localhost:8080");
        headers.add("X-Forwarded-Proto", "http");

        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null) {
            headers.add(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }

        if (request.getContentType() != null) {
            headers.setContentType(MediaType.parseMediaType(request.getContentType()));
        }

        // 4. Execute Exchange
        ResponseEntity<byte[]> tooljetRes = restTemplate.exchange(
                targetUri,
                HttpMethod.valueOf(request.getMethod()),
                new HttpEntity<>(body, headers),
                byte[].class
        );

        if (tooljetRes.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            return new ResponseEntity<>(null, tooljetRes.getHeaders(), HttpStatus.NOT_MODIFIED);
        }

        byte[] responseBody = tooljetRes.getBody();

        // 5. THE MIRROR (Unchanged logic)
        if (responseBody != null && tooljetRes.getHeaders().getContentType() != null
                && tooljetRes.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON)) {
            String json = new String(responseBody);
            if (json.contains("localhost:8082")) {
                json = json.replace("localhost:8082", "localhost:8080");
                responseBody = json.getBytes();
            }
        }

        // 6. Cleanup & Headers (Unchanged logic)
        HttpHeaders resHeaders = new HttpHeaders();

        if (tooljetRes.getHeaders().getContentType() != null) {
            resHeaders.setContentType(tooljetRes.getHeaders().getContentType());
        }
        if (tooljetRes.getHeaders().getETag() != null) {
            resHeaders.setETag(tooljetRes.getHeaders().getETag());
        }
        if (tooljetRes.getHeaders().getCacheControl() != null) {
            resHeaders.setCacheControl(tooljetRes.getHeaders().getCacheControl());
        }
        if (responseBody != null) {
            resHeaders.setContentLength(responseBody.length);
        }

        resHeaders.remove("X-Frame-Options");
        resHeaders.remove("Content-Security-Policy");
        resHeaders.remove("Cross-Origin-Opener-Policy");
        resHeaders.remove("Cross-Origin-Resource-Policy");

        resHeaders.add("X-Frame-Options", "ALLOWALL");
        resHeaders.add("Content-Security-Policy", "frame-ancestors *");
        resHeaders.setAccessControlAllowOrigin("http://localhost:5173");
        resHeaders.setAccessControlAllowCredentials(true);
        return new ResponseEntity<>(responseBody, resHeaders, tooljetRes.getStatusCode());
    }

    private record SqlAndBindings(String sql, Object[] args) {
    }

    private record DatasourceConfig(String schema, String kind) {
    }
}