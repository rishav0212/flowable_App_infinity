package com.example.flowable_app.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Controller to manage authentication-related metadata.
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    /**
     * Retrieves the current authenticated user's profile based on the JWT Principal.
     * * @param authentication The security context injected by Spring Security.
     *
     * @return User metadata or 401 Unauthorized.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        try {
            // 1. Check if the security context has a valid authentication object
            if (authentication == null || !authentication.isAuthenticated()) {
                log.warn("⚠️ [Auth] Access attempt to /me without valid authentication context");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of(
                                "error", "Not authenticated",
                                "message", "No valid session or token found"
                        ));
            }

            // 2. Extract the Map principal created in JwtAuthenticationFilter
            Object principal = authentication.getPrincipal();

            if (!(principal instanceof Map)) {
                log.error("❌ [Auth] Principal is not a Map. Type found: {}", principal.getClass().getName());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Internal Configuration Error"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) principal;

            // 🟢 Extract clean string values (Prevents the {id=...} URL error)
            String userId = (String) details.get("id");
            String email = (String) details.get("email");
            String name = (String) details.get("name");
            String tenantId = (String) details.get("tenantId");

            log.info("👤 [Auth] Profile retrieved successfully for User: [{}] ({})", userId, email);

            // 3. Return structured response including all new fields
            return ResponseEntity.ok(Map.of(
                    "username", userId, // 🟢 Frontend uses this for Task fetching
                    "id", userId,
                    "email", email,
                    "name", name,
                    "tenantId", tenantId,
                    "authorities", List.of(Map.of("authority", "ROLE_USER")),
                    "authenticated", true
            ));

        } catch (Exception e) {
            log.error("🔥 [Auth] Unexpected error during /me endpoint execution: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Internal Server Error",
                            "message", "An unexpected error occurred while fetching your profile"
                    ));
        }
    }
}