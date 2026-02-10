package com.example.flowable_app.config; // <--- Note the package is 'config'


import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtils {
    private static final long EXPIRATION_MS = 30L*86000000;     // 10 days
    @Value("${app.jwt.secret}")
    private String secret;
    private Key key;

    // We need to initialize the key AFTER the secret is injected
    @jakarta.annotation.PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }


    public String generateToken(String internalId, String email, String name, String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", internalId);
        claims.put("name", name);
        claims.put("tenantId", tenantId); // 🟢 Store Tenant ID

        return Jwts.builder()
                .setClaims(claims) // Use setClaims to add the map
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 🟢 UPDATED: Returns full Claims instead of just subject
    public Claims validateToken(String token) {
        try {
            return Jwts.parserBuilder().setSigningKey(key).build()
                    .parseClaimsJws(token).getBody();
        } catch (Exception e) {
            return null;
        }
    }
}