package com.example.paymentapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that assigns a correlation ID to every inbound request.
 * The ID is taken from the {@code X-Correlation-ID} request header if present,
 * otherwise a new UUID is generated. The ID is placed in both the MDC (for log
 * pattern interpolation) and the response header so callers can correlate logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else {
            // Strip CR, LF, tab and all other control characters to prevent log injection.
            // Cap at 64 characters; re-generate a UUID if the result is blank or oversized.
            correlationId = correlationId.replaceAll("[\\r\\n\\t\\x00-\\x1f\\x7f]", "").strip();
            if (correlationId.isBlank() || correlationId.length() > 64) {
                correlationId = UUID.randomUUID().toString();
            }
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
