package com.example.paymentapi.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Stricter per-client rate limiter applied only to POST /api/v1/auth/login.
 *
 * <p>The general {@link RateLimitInterceptor} allows 100 req/60s — sufficient for normal
 * API traffic but too lenient to prevent password-guessing attacks on the login endpoint.
 * This interceptor enforces a configurable tighter limit (default: 10 attempts/60s) per
 * client identity (X-Api-Key header, then RemoteAddr).
 *
 * <p>If an attacker exceeds the limit they receive a 429 with a {@code Retry-After} header.
 * The bucket resets after the configured window, allowing legitimate users who accidentally
 * trigger it to recover quickly.
 *
 * <p>Configuration:
 * <pre>
 *   rate-limit.login.limit=10
 *   rate-limit.login.refreshPeriod=60000
 * </pre>
 */
@Component
public class LoginRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LoginRateLimitInterceptor.class);

    private static final int MAX_CLIENTS = 10_000;
    private static final long EXPIRE_AFTER_ACCESS_MINUTES = 10;

    private final int loginLimit;
    private final long refreshPeriodMs;
    private final LoadingCache<String, RateLimiter> buckets;

    public LoginRateLimitInterceptor(
            @org.springframework.beans.factory.annotation.Value("${rate-limit.login.limit:10}") int loginLimit,
            @org.springframework.beans.factory.annotation.Value("${rate-limit.login.refreshPeriod:60000}") long refreshPeriodMs) {
        this.loginLimit = loginLimit;
        this.refreshPeriodMs = refreshPeriodMs;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_CLIENTS)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .build(this::newBucket);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String clientId = clientIdentifier(request);
        RateLimiter limiter = buckets.get(clientId);

        if (!limiter.acquirePermission()) {
            logger.warn("Login rate limit exceeded for client: {} (limit={}/{}ms)", mask(clientId), loginLimit, refreshPeriodMs);
            long retryAfter = limiter.getRateLimiterConfig().getLimitRefreshPeriod().toSeconds();
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.addHeader("Retry-After", String.valueOf(retryAfter));
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Too many login attempts. Please try again in "
                    + retryAfter + " seconds.\"}");
            return false;
        }
        return true;
    }

    private RateLimiter newBucket(String clientId) {
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(loginLimit)
                .limitRefreshPeriod(Duration.ofMillis(refreshPeriodMs))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiterRegistry.of(cfg).rateLimiter("login:" + clientId);
    }

    private String clientIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "apikey:" + apiKey;
        }
        return "ip:" + request.getRemoteAddr();
    }

    private String mask(String clientId) {
        if (clientId == null || clientId.length() < 8) return "****";
        if (clientId.startsWith("apikey:")) {
            return "apikey:****" + clientId.substring(clientId.length() - 4);
        }
        return clientId;
    }

    /** Clears all buckets — for test teardown only. */
    public void clearBuckets() {
        buckets.invalidateAll();
    }
}
