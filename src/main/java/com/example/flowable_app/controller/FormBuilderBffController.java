package com.example.flowable_app.controller;

import com.example.flowable_app.service.FormIoAuthService;
import com.example.flowable_app.service.UserContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * BFF Controller for the Form.io Builder.
 *
 * The React app embeds /form-builder.html (a static file served by Spring Boot)
 * as an iframe. That HTML page needs:
 *   1. A Form.io JWT token to make authenticated requests.
 *   2. The backend API base URL to proxy Form.io calls.
 *   3. The current tenant's slug to know which forms to list/prefix.
 *
 * This controller provides those values as a JSON bundle that the static
 * HTML page fetches on load (via a simple GET with the user's JWT).
 */
@RestController
@RequestMapping("/api/formio-bff")
@RequiredArgsConstructor
@Slf4j
public class FormBuilderBffController {

    private final FormIoAuthService formIoAuthService;
    private final UserContextService userContextService;

    @Value("${app.backend.url:http://localhost:8080}")
    private String backendUrl;

    /**
     * Returns configuration bundle for the form builder iframe.
     *
     * Called by form-builder.html on page load with the user's JWT in Authorization header.
     * Responds with:
     *  - formioToken: the Form.io admin JWT (for making direct Form.io API calls via proxy)
     *  - apiBaseUrl: where the iframe should point Form.io SDK
     *  - tenantSlug: for display / filtering in the builder UI
     *  - tenantId: for tagging
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getBuilderConfig() {
        try {
            String formioToken = formIoAuthService.getAccessToken();
            String tenantSlug  = userContextService.getCurrentTenantSlug();
            String tenantId    = userContextService.getCurrentTenantId();

            log.info("📦 Builder config requested for tenant: {}", tenantSlug);

            return ResponseEntity.ok(Map.of(
                    "formioToken", formioToken,
                    "apiBaseUrl",  backendUrl + "/api/forms",
                    "tenantSlug",  tenantSlug != null ? tenantSlug : "",
                    "tenantId",    tenantId   != null ? tenantId   : ""
            ));
        } catch (Exception e) {
            log.error("❌ Builder config failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

}