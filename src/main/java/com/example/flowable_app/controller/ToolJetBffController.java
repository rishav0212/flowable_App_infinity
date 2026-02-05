package com.example.flowable_app.controller;

import com.example.flowable_app.service.AllowedUserService;
import com.example.flowable_app.service.ToolJetAuthService;
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
    private final RestTemplate restTemplate = new RestTemplate();
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    @Value("${tooljet.internal.url:http://localhost:8082}")
    private String tooljetUrl; // ✅ Use the property, not hardcoded string
    @Value("${tooljet.organization.id}")
    private String organizationId;

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

        String userEmail;
        String fullPath = request.getRequestURI();
        String targetPath = fullPath;

        // 1. Initial Entry via Ticket
        if (ticket != null) {
            ToolJetAuthService.TicketInfo info = authService.validateTicket(ticket);
            if (info == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

            String secureSessionId = authService.promoteTicketToSession(info.email());

            // --- ADD THIS LOGIC ---
            // Fetch the real ID to sync it with the frontend URL
            String verifiedId = userService.getUserIdByEmail(info.email());

            ResponseCookie cookie = ResponseCookie.from("TJ_BFF_SESSION", secureSessionId)
                    .httpOnly(true)
                    .secure(false)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(3600)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            userEmail = info.email();

            // Construct the path that includes the userId for the browser
            // Result: /applications/<app-id>?userId= ?
            String cleanPathForBrowser = fullPath.substring(fullPath.indexOf("/applications/"))
                    + "?userId=" + verifiedId;

            // Use the modified path for the script injection
            return injectUrlFixerScript(executeProxyWithRetry(targetPath, request, userEmail, body),
                    cleanPathForBrowser);
        }

        // 2. Subsequent requests (Assets/APIs)
        if (bffSession != null) {
            // 🛑 CHANGE: Look up the real email from the Map using the UUID from the cookie
            userEmail = authService.getEmailFromSession(bffSession);

            if (userEmail == null) {
                log.warn("🚨 Invalid or expired TJ_BFF_SESSION: {}", bffSession);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            targetPath = fullPath;
        } else {
            // 🛑 DENY: No ticket and no session cookie
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

    @PostMapping("/api/data-queries/{queryId}/run")
    public ResponseEntity<?> secureRunQuery(
            @PathVariable String queryId,
            @RequestBody(required = false) Map<String, Object> body,
            @CookieValue(name = "TJ_BFF_SESSION", required = false) String bffSession,
            HttpServletRequest request) {

        // 📝 LOG: Start of Request
        log.info("🔹 [SECURE RUN] Request received for Query ID: {}", queryId);

        String userEmail = authService.getEmailFromSession(bffSession);
        if (userEmail == null) {
            log.warn("❌ [SECURE RUN] Unauthorized: No valid session found.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String trustedUserId = userService.getUserIdByEmail(userEmail);
        log.info("👤 [SECURE RUN] Authenticated User: {} (ID: {})", userEmail, trustedUserId);

        try {
            // 1. Extract Slug
            String appSlug = extractAppSlug(request);
            if (appSlug.isEmpty()) {
                log.warn("⚠️ [SECURE RUN] Could not determine App Slug. Falling back to standard proxy.");
                byte[] jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
                return executeProxyWithRetry(request.getRequestURI(), request, userEmail, jsonBody);
            }
            log.info("🐌 [SECURE RUN] App Slug: {}", appSlug);

            // 2. Fetch Metadata
            Map<String, Object> queryMeta;
            try {
                queryMeta = fetchQueryMetadata(queryId, appSlug);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    log.warn("🔄 [SECURE RUN] Session expired. Refreshing...");
                    authService.clearSession();
                    queryMeta = fetchQueryMetadata(queryId, appSlug);
                } else {
                    throw e;
                }
            }
            log.info("📋 [SECURE RUN] Metadata Fetched. Query Name: {}",
                    queryMeta.get("name")); // Assuming 'name' exists in options or root

            String dataSourceId = (String) queryMeta.get("data_source_id");
            DatasourceConfig dsConfig = fetchDatasourceConfig(dataSourceId);
            log.info("🔌 [SECURE RUN] Datasource Config - Kind: {}, Schema: {}", dsConfig.kind, dsConfig.schema);

            String dsKind = (String) queryMeta.get("kind");
            log.info("🔌 [SECURE RUN] Datasource Kind from Definition: {}", dsKind);

            // 3. Proxy Non-Postgres
            // Check for 'postgres' OR 'mysql' if you are still migrating
            if (dsKind == null || !dsKind.toLowerCase().contains("postgres")) {
                log.info("🔀 [SECURE RUN] Kind is '{}'. Proxying to ToolJet.", dsKind);
                byte[] jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
                return executeProxyWithRetry(request.getRequestURI(), request, userEmail, jsonBody);
            }

            // 3. Extract SQL
            Map<String, Object> options = (Map<String, Object>) queryMeta.get("options");
            String originalSql = (String) options.get("query");

            // 🛑 Check for Null SQL (e.g. Google Sheets query masquerading as SQL)
            if (originalSql == null) {
                log.warn("⚠️ [SECURE RUN] SQL is null! This is likely not a DB query. Proxying.");
                byte[] jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
                return executeProxyWithRetry(request.getRequestURI(), request, userEmail, jsonBody);
            }

            log.debug("📜 [SECURE RUN] Raw SQL: {}", originalSql);


            Map<String, Object> params = (body != null && body.containsKey("params"))
                    ? (Map<String, Object>) body.get("params")
                    : new HashMap<>();

            SqlAndBindings prepared = convertToPreparedSql(originalSql, params);
            log.info("🔧 [SECURE RUN] Executing SQL: {}", prepared.sql);

            // 4. Execute with RLS
            List<Map<String, Object>> results = transactionTemplate.execute(status -> {
                if (dsConfig.schema != null && !dsConfig.schema.equalsIgnoreCase("public")) {
                    log.debug("⚙️ [DB] Setting search_path to: {}", dsConfig.schema);
                    jdbcTemplate.execute("SET LOCAL search_path TO " + dsConfig.schema);
                }

                log.debug("⚙️ [DB] Setting RLS user to: {}", trustedUserId);

                // 🟢 FIXED: Use queryForObject instead of update because set_config returns a value
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

    private Map<String, Object> fetchQueryMetadata(String queryId, String appSlug) {
        String url = tooljetUrl + "/api/apps/slugs/" + appSlug;
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, authService.getToolJetSession());
        headers.add("tj-workspace-id", organizationId);
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

        // 🟢 FIX: Extract 'kind' directly from the nested dataSource object
        // The structure is: query -> dataSource -> kind
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
        result.put("kind", dsKind); // <--- Added this

        return result;
    }

    private DatasourceConfig fetchDatasourceConfig(String datasourceId) {
        try {
            String url = tooljetUrl + "/api/data_sources/" + datasourceId;
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, authService.getToolJetSession());
            headers.add("tj-workspace-id", organizationId);
            headers.add("x-tooljet-workspace-id", organizationId);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map>
                    response =
                    restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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
    }

    private record SqlAndBindings(String sql, Object[] args) {
    }

    private record DatasourceConfig(String schema, String kind) {
    }
}