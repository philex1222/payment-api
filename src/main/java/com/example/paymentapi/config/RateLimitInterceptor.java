package com.example.paymentapi.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limiting interceptor backed by Resilience4j per-client RateLimiters.
 *
 * <p>Client identity is derived from:
 * <ol>
 *   <li>X-Api-Key header (preferred — identifies authenticated API consumers)</li>
 *   <li>RemoteAddr (fallback for anonymous callers — NOT proxy headers, to prevent
 *       IP-spoofing via crafted X-Forwarded-For / X-Real-IP values)</li>
 * </ol>
 *
 * NOTE: If this service sits behind a trusted reverse proxy that terminates TLS
 * and injects X-Forwarded-For, configure the proxy to sanitise that header
 * rather than reading it here, to prevent clients from spoofing their IP.
 *
 * Constructor injection is used — never @Autowired field injection.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final RateLimitProperties rateLimitProperties;

    public RateLimitInterceptor(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String clientId = getClientIdentifier(request);
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(clientId, this::createRateLimiter);
        boolean allowRequest = rateLimiter.acquirePermission();

        response.addHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getLimit()));
        response.addHeader("X-RateLimit-Remaining",
                String.valueOf(rateLimiter.getMetrics().getAvailablePermissions()));
        response.addHeader("X-RateLimit-Reset",
                String.valueOf(System.currentTimeMillis()
                        + rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod().toMillis()));

        if (!allowRequest) {
            logger.warn("Rate limit exceeded for client: {}", maskClientId(clientId));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Rate limit exceeded. Please try again later.\"}");
            return false;
        }
        return true;
    }

    /**
     * Derives a rate-limit bucket key from the request.
     * Uses API key if present; falls back to the TCP remote address.
     * Proxy headers (X-Forwarded-For) are intentionally NOT trusted here —
     * they can be spoofed by any client and must be sanitised at the proxy layer.
     */
    private String getClientIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "apikey:" + apiKey;
        }
        return "ip:" + request.getRemoteAddr();
    }

    private RateLimiter createRateLimiter(String clientId) {
        logger.debug("Creating rate limiter for client: {}", maskClientId(clientId));
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMillis(rateLimitProperties.getRefreshPeriod()))
                .limitForPeriod(rateLimitProperties.getLimit())
                .timeoutDuration(Duration.ofMillis(rateLimitProperties.getTimeout()))
                .build();
        return RateLimiterRegistry.of(config).rateLimiter(clientId);
    }

    private String maskClientId(String clientId) {
        if (clientId == null || clientId.length() < 8) {
            return "****";
        }
        if (clientId.startsWith("apikey:")) {
            return "apikey:****" + clientId.substring(clientId.length() - 4);
        }
        return clientId;
    }

    /** Clears all per-client rate limiters — intended for test teardown only. */
    public void clearRateLimiters() {
        rateLimiters.clear();
    }
}