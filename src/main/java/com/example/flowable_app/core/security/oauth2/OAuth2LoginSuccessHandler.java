package com.example.flowable_app.core.security.oauth2;

import com.example.flowable_app.core.security.jwt.JwtUtils;
import com.example.flowable_app.entity.Tenant;
import com.example.flowable_app.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtils jwtUtils;

    // 👇 1. Inject the URL from application.properties
    @Value("${app.frontend.url}")
    private String frontendUrl;
    private final TenantRepository tenantRepository;
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws
            IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        // This getName() should return 'internalId' because of CustomOAuth2UserService
        String internalId = oAuth2User.getName();
        log.info("🎟️ Token Minting: Generating JWT for Subject: [{}]", internalId); // 👈 Added
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");

        String tenantId = "";
        String tenantSlug = "";
        String schemaName = "";
        HttpSession session = request.getSession(false);
        if (session != null) {
            // The frontend URL parameter (e.g., /saar-biotech/login) is technically the slug
            String sessionTenantSlug = (String) session.getAttribute("WORKFLOW_TENANT");
            if (sessionTenantSlug != null) {
                log.info("💾 Found Tenant Slug in Session: {}", sessionTenantSlug);

                // 🟢 Query the database exactly ONCE during login to get the physical mapping
                Tenant tenant = tenantRepository.findBySlug(sessionTenantSlug)
                        .orElseThrow(() -> new RuntimeException("Authentication Failed: Invalid Tenant Slug provided."));

                tenantId = tenant.getId();
                tenantSlug = tenant.getSlug();
                schemaName = tenant.getSchemaName();

                session.removeAttribute("WORKFLOW_TENANT"); // Clean up
            }
        }
        String token = jwtUtils.generateToken(internalId, email, name, tenantId, tenantSlug, schemaName);
        log.info("🚀 Redirecting to frontend with newly minted token for user [{}]", internalId); // 👈 Added

        String targetUrl = UriComponentsBuilder.fromUriString(frontendUrl + "/oauth2/redirect")
                .queryParam("token", token)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}