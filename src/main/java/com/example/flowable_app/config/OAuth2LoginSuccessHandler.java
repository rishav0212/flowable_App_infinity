package com.example.flowable_app.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws
            IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        // This getName() should return 'internalId' because of CustomOAuth2UserService
        String internalId = oAuth2User.getName();
        log.info("🎟️ Token Minting: Generating JWT for Subject: [{}]", internalId); // 👈 Added
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String token = jwtUtils.generateToken(internalId, email, name);
        log.info("🚀 Redirecting to frontend with newly minted token for user [{}]", internalId); // 👈 Added

        String targetUrl = UriComponentsBuilder.fromUriString(frontendUrl + "/oauth2/redirect")
                .queryParam("token", token)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}