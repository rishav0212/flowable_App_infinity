package com.example.flowable_app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service public class FormIoAuthService {

    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${formio.url}") private String formIoUrl;
    @Value("${formio.admin-email}") private String email;
    @Value("${formio.admin-password}") private String password;
    private String cachedToken;

    // This is the method you will call from your Controller
    public String getAccessToken() {
        if (cachedToken == null) {
            login();
        }
        return cachedToken;
    }

    private void login() {
        String loginUrl = formIoUrl + "/admin/login";

        // 1. Prepare the JSON body
        Map<String, Object> credentials = Map.of("data", Map.of("email", email, "password", password));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(credentials, headers);

        try {
            // 2. Send POST request
            ResponseEntity<String> response = restTemplate.postForEntity(loginUrl, request, String.class);

            // 3. Extract the token from the Header
            cachedToken = response.getHeaders().getFirst("x-jwt-token");
            System.out.println("✅ Successfully logged into Form.io. New Token acquired.");

        } catch (Exception e) {
            System.err.println("❌ Failed to log into Form.io: " + e.getMessage());
            throw new RuntimeException("Could not authenticate with Form.io");
        }
    }

    // Call this if you get a 401 error to force a re-login
    public void invalidateToken() {
        this.cachedToken = null;
    }
}