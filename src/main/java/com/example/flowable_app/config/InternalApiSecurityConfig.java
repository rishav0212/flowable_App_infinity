package com.example.flowable_app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class InternalApiSecurityConfig {

    /**
     * This filter chain runs AFTER the Auth Server, but BEFORE your main SecurityConfig.
     * We use `securityMatcher` to ensure this logic ONLY applies to the ToolJet internal API.
     * Because this runs first, it completely bypasses your `JwtAuthenticationFilter` for these paths,
     * preventing any conflicts between your custom JWTs and the new OAuth2 standard JWTs.
     */
    @Bean
    @Order(1) // Runs early in the filter chain
    public SecurityFilterChain internalApiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // 🟢 FIX: Explicitly use AntPathRequestMatcher
                .securityMatcher(new AntPathRequestMatcher("/api/internal/tooljet/**"))

                .authorizeHttpRequests(auth -> auth
                        // 🟢 FIX: Explicitly use AntPathRequestMatcher here as well
                        .requestMatchers(new AntPathRequestMatcher("/api/internal/tooljet/**"))
                        .hasAuthority("SCOPE_internal:read")
                        .anyRequest().authenticated()
                )

                // 🟢 Enables standard Spring OAuth2 JWT Validation (uses the JwtDecoder we defined earlier)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}