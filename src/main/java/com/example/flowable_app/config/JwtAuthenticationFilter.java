package com.example.flowable_app.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.flowable.common.engine.impl.identity.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws
            ServletException,
            IOException {

        String authHeader = request.getHeader("Authorization");
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            String xToken = request.getHeader("x-jwt-token");
            if (xToken != null && !xToken.isEmpty()) {
                token = xToken;
            }
        }

        if (token != null) {
            Claims claims = jwtUtils.validateToken(token);

            if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // 🟢 2. Extract the simple User ID string
                // Assuming 'id' claim holds "Rishab_J"
                String userId = String.valueOf(claims.get("id"));

                // 🟢 3. Explicitly tell Flowable "This is the user ID"
                Authentication.setAuthenticatedUserId(userId);
                Map<String, Object> userDetails = Map.of(
                        "id", claims.get("id"),
                        "name", claims.get("name"),
                        "email", claims.getSubject(),
                        "tenantId", claims.get("tenantId")
                );

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                );

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            Authentication.setAuthenticatedUserId(null);
        }
    }
}