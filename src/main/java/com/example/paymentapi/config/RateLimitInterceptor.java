package com.example.paymentapi.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting interceptor using Resilience4j.
 * Limits requests per client based on API key or IP address.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Get client identifier (API key or IP address as fallback)
        String clientId = getClientIdentifier(request);

        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(clientId, key -> createRateLimiter(key));

        boolean allowRequest = rateLimiter.acquirePermission();

        // Add rate limit headers
        response.addHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getLimit()));
        response.addHeader("X-RateLimit-Remaining",
                String.valueOf(rateLimiter.getMetrics().getAvailablePermissions()));
        response.addHeader("X-RateLimit-Reset",
                String.valueOf(System.currentTimeMillis() +
                        rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod().toMillis()));

        if (!allowRequest) {
            logger.warn("Rate limit exceeded for client: {}", maskClientId(clientId));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\",\"message\":\"Rate limit exceeded. Please try again later.\"}");
            return false;
        }

        return true;
    }

    /**
     * Gets the client identifier for rate limiting.
     * Uses API key if provided, otherwise falls back to IP address.
     */
    private String getClientIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return "apikey:" + apiKey;
        }

        // Fall back to IP address
        String clientIp = getClientIpAddress(request);
        return "ip:" + clientIp;
    }

    /**
     * Gets the client IP address, handling proxied requests.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Take the first IP in the list (original client)
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    private RateLimiter createRateLimiter(String clientId) {
        logger.debug("Creating rate limiter for client: {}", maskClientId(clientId));

        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMillis(rateLimitProperties.getRefreshPeriod()))
                .limitForPeriod(rateLimitProperties.getLimit())
                .timeoutDuration(Duration.ofMillis(rateLimitProperties.getTimeout()))
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        return registry.rateLimiter(clientId);
    }

    /**
     * Masks client ID for logging security.
     */
    private String maskClientId(String clientId) {
        if (clientId == null || clientId.length() < 8) {
            return "****";
        }
        if (clientId.startsWith("apikey:")) {
            return "apikey:****" + clientId.substring(clientId.length() - 4);
        }
        return clientId;
    }

    /**
     * Clears all rate limiters (for testing purposes).
     */
    public void clearRateLimiters() {
        rateLimiters.clear();
    }
}