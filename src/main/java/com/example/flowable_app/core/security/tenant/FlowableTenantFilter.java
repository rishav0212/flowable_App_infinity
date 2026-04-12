package com.example.flowable_app.core.security.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/**
 * Intercepts calls to native Flowable APIs and injects the tenantId
 * extracted from the JWT token into the query parameters.
 */
@Component
public class FlowableTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Target only the Flowable REST endpoints
        if (path.startsWith("/process-api/") || path.startsWith("/dmn-api/")) {

            // 🟢 Fetching directly from Security Context (populated via JWT)
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth != null && auth.getPrincipal() instanceof Map) {
                Map<String, Object> details = (Map<String, Object>) auth.getPrincipal();
                String tenantId = (String) details.get("tenantId");

                if (tenantId != null) {
                    // Wrap request to force the tenantId parameter
                    HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                        @Override
                        public String getParameter(String name) {
                            if ("tenantId".equals(name)) return tenantId;
                            return super.getParameter(name);
                        }

                        @Override
                        public Map<String, String[]> getParameterMap() {
                            Map<String, String[]> params = new HashMap<>(super.getParameterMap());
                            params.put("tenantId", new String[]{tenantId});
                            return Collections.unmodifiableMap(params);
                        }
                    };
                    filterChain.doFilter(wrappedRequest, response);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}