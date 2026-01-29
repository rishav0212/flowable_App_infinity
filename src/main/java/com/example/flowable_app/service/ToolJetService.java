package com.example.flowable_app.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ToolJetService {

    @Value("${tooljet.secret}")
    private String tooljetSecret;

    @Value("${tooljet.host}")
    private String tooljetHost;

    public String generateEmbedUrl(String applicationId, String userEmail, String userName) {
        // 1. Define the User for ToolJet
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", userEmail);
        claims.put("firstName", userName);
        claims.put("externalId", userEmail); // Unique ID linking InfinityPlus to ToolJet

        // 2. Sign the Token (ToolJet requires specific structure)
        String token = Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                // ToolJet tokens shouldn't live long (e.g., 5 mins), just enough to load the iframe
                .setExpiration(new Date(System.currentTimeMillis() + 300000))
                .signWith(SignatureAlgorithm.HS256, tooljetSecret.getBytes())
                .compact();

        // 3. Construct the Full URL
        // Format: /applications/{appId}/embed?ssoToken={token}
        return String.format("%s/applications/%s/embed?ssoToken=%s",
                tooljetHost, applicationId, token);
    }
}