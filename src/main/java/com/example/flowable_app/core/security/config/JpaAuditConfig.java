package com.example.flowable_app.core.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditConfig {

    /**
     * This tells Spring JPA exactly who the current logged-in user is.
     * Whenever you call repository.save(entity), Spring calls this method automatically
     * and injects the username into the @CreatedBy and @UpdatedBy fields!
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
                return Optional.of("system"); // Fallback for background jobs
            }

            // Assuming your username/userId is stored as the principal name
            return Optional.of(authentication.getName());
        };
    }
}