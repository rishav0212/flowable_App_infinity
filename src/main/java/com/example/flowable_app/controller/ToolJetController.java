package com.example.flowable_app.controller;

import com.example.flowable_app.service.ToolJetService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/tooljet")
public class ToolJetController {

    @Autowired
    private ToolJetService tooljetService;

    @GetMapping("/embed/{applicationId}")
    public Map<String, String> getEmbedUrl(
            @PathVariable String applicationId,
            @AuthenticationPrincipal Object principal // Works for both OAuth2 and JWT
    ) {
        // Extract email/name based on your specific Auth object (CustomOAuth2User or UserDetails)
        // This is a simplified extraction example:
        String email = "admin@example.com";
        String name = "Admin User";

        // TODO: extraction logic based on your SecurityConfig

        String url = tooljetService.generateEmbedUrl(applicationId, email, name);
        return Collections.singletonMap("url", url);
    }
}