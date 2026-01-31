package com.example.flowable_app.config;

import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // 👇 2. Inject the Dynamic URL from application.properties
    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)

                // 🛑 1. ENABLE STATELESS SESSIONS (No more server-side memory)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                                // Public Assets & Auth Endpoints
                                .requestMatchers(AntPathRequestMatcher.antMatcher("/"))
                                .permitAll()
                                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/auth/me"))
                                .permitAll()
                                .requestMatchers(AntPathRequestMatcher.antMatcher("/oauth2/**"))
                                .permitAll()
                                .requestMatchers(AntPathRequestMatcher.antMatcher("/login/**"))
                                .permitAll()
                                .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/api/storage/**"))
                                .permitAll()
//                        .requestMatchers(AntPathRequestMatcher.antMatcher("/api/storage/**")).permitAll()
                                // 1. Allow Swagger UI & API Docs (Add these lines)
                                .requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**"))
                                .permitAll()
                                .requestMatchers(new AntPathRequestMatcher("/swagger-ui/**"))
                                .permitAll()
                                .requestMatchers(new AntPathRequestMatcher("/swagger-ui.html"))
                                .permitAll()


//                                1.
                                .requestMatchers(AntPathRequestMatcher.antMatcher("/api/tooljet/embed-ticket")).authenticated()

                                // 2. The "Wristband" Rule (Pure Spring Boot logic)
                                // If a request has the TJ_BFF_SESSION cookie, permit it so the Proxy can handle it.
                                .requestMatchers(request -> {
                                    if (request.getCookies() != null) {
                                        for (Cookie c : request.getCookies()) {
                                            if ("TJ_BFF_SESSION".equals(c.getName())) return true;
                                        }
                                    }
                                    return false;
                                }).permitAll()

                                // 3. Explicitly allow the ticketed entry
                                .requestMatchers(AntPathRequestMatcher.antMatcher("/tooljet/ticket/**")).permitAll()
                                // All other API calls require the JWT badge
                                .anyRequest()
                                .authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        // 🛑 2. SUCCESS HANDLER (This redirects to React with the Token)
                        .successHandler(oAuth2LoginSuccessHandler)
                )

                // 🛑 3. SCAN JWT ON EVERY REQUEST
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 👇 3. Use the variable here instead of "http://localhost:5173"
        configuration.setAllowedOrigins(List.of(frontendUrl));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}