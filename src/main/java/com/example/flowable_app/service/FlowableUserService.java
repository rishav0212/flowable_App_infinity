package com.example.flowable_app.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Resolves "${user}" in BPMN expressions.
 */
@Service("user") // 🟢 This name must match your BPMN symbol
public class FlowableUserService {

    public String getId() {
        return getClaim("id");
    }

    public String getName() {
        return getClaim("name");
    }

    public String getEmail() {
        return getClaim("email");
    }

    @SuppressWarnings("unchecked")
    private String getClaim(String key) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) auth.getPrincipal();
            Object value = details.get(key);
            return value != null ? value.toString() : null;
        }
        return "system";
    }
}