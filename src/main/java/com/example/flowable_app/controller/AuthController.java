package com.example.flowable_app.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Controller to manage authentication-related metadata.
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j // Enables log.info, log.error, etc.
public class AuthController {

    /**
     * Retrieves the current authenticated user's profile based on the JWT Principal.
     * * @param principal The security principal injected by Spring Security (JwtAuthenticationFilter).
     * @return User metadata or 401 Unauthorized.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Principal principal) {
        try {
            // 1. Check if the security context has a valid principal
            if (principal == null) {
                log.warn("⚠️ [Auth] Access attempt to /me without valid authentication context (Principal is null)");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of(
                                "error", "Not authenticated",
                                "message", "No valid session or token found"
                        ));
            }

            // 2. Extract the Subject/ID (e.g., "Rishab_J")
            String userId = principal.getName();

            log.info("👤 [Auth] Profile retrieved successfully for User ID: [{}]", userId);

            // 3. Return structured response
            return ResponseEntity.ok(Map.of(
                    "username", userId,
                    "id", userId,
                    "authorities", List.of(Map.of("authority", "ROLE_USER")),
                    "authenticated", true
            ));

        } catch (Exception e) {
            // 4. Global safety net for unexpected errors
            log.error("🔥 [Auth] Unexpected error during /me endpoint execution: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Internal Server Error",
                            "message", "An unexpected error occurred while fetching your profile"
                    ));
        }
    }
}