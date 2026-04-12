package com.example.flowable_app.core.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects a unique Trace ID and the Tenant ID into the logging context (MDC) for every request.
 * This ensures that if an error happens, you can easily filter your logs
 * to see the exact journey of that specific request.
 */
@Component
public class LoggingContextFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TENANT_HEADER = "X-Tenant-ID"; // Or extract from JWT

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 1. Generate a unique ID for this specific API call
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }

            // 2. Extract Tenant info (Modify this based on how you get your tenant ID)
            String tenantId = request.getHeader(TENANT_HEADER) != null ? request.getHeader(TENANT_HEADER) : "system";

            // 3. Put them in the MDC. Now every log.info() or log.error() will contain these!
            MDC.put("traceId", traceId);
            MDC.put("tenantId", tenantId);

            // 4. Return the trace ID to the frontend so they can report it if they see an error screen
            response.setHeader(TRACE_ID_HEADER, traceId);

            filterChain.doFilter(request, response);
        } finally {
            // ALWAYS clear the MDC to prevent memory leaks in the thread pool
            MDC.clear();
        }
    }
}