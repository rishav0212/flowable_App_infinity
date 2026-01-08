package com.example.flowable_app.config;

import com.example.flowable_app.service.AllowedUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final AllowedUserService allowedUserService;


    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User googleUser = super.loadUser(userRequest);
        String email = googleUser.getAttribute("email");
        log.info("🌐 Google Auth: Received email [{}] from Google", email); // 👈 Added

        String internalUserId = allowedUserService.getUserIdByEmail(email)
                .orElseThrow(() -> {
                    log.error("🚫 Access Denied: Email [{}] not found in allowed mapping table", email); // 👈 Added
                    return new OAuth2AuthenticationException(new OAuth2Error("access_denied"),
                            "Email [" + email + "] is not mapped.");
                });

        Map<String, Object> attributes = new HashMap<>(googleUser.getAttributes());
        attributes.put("internalId", internalUserId);
        log.info("🔑 Principal Setup: Setting 'internalId' as the Name attribute: [{}]", internalUserId); // 👈 Added

        return new DefaultOAuth2User(googleUser.getAuthorities(), attributes, "internalId");
    }
}