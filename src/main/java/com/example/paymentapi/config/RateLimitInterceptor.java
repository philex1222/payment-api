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
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Unified rate-limiting interceptor for both general API traffic and login attempts.
 *
 * <p>Strategy is injected at construction time — WebMvcConfig creates one general-purpose
 * bean and one login-specific bean using different RateLimitProperties instances.
 *
 * <p>Client identity is derived from:
 * <ol>
 *   <li>X-Api-Key header (preferred)</li>
 *   <li>RemoteAddr (fallback — NOT proxy headers to prevent IP-spoofing)</li>
 * </ol>
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    public enum Strategy { GENERAL, LOGIN }

    private static final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final int MAX_CLIENTS = 10_000;

    private final LoadingCache<String, RateLimiter> buckets;
    private final RateLimitProperties props;
    private final Strategy strategy;

    public RateLimitInterceptor(RateLimitProperties props, Strategy strategy) {
        this.props = props;
        this.strategy = strategy;
        long expireMinutes = strategy == Strategy.LOGIN ? 10 : 5;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_CLIENTS)
                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES)
                .build(this::newBucket);
    }

    /** Default Spring constructor — creates a GENERAL interceptor using injected properties. */
    public RateLimitInterceptor(RateLimitProperties rateLimitProperties) {
        this(rateLimitProperties, Strategy.GENERAL);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String clientId = clientIdentifier(request);
        RateLimiter limiter = buckets.get(clientId);

        response.addHeader("X-RateLimit-Limit", String.valueOf(props.getLimit()));
        response.addHeader("X-RateLimit-Remaining",
                String.valueOf(limiter.getMetrics().getAvailablePermissions()));
        response.addHeader("X-RateLimit-Reset",
                String.valueOf(System.currentTimeMillis()
                        + limiter.getRateLimiterConfig().getLimitRefreshPeriod().toMillis()));

        if (!limiter.acquirePermission()) {
            long retryAfter = limiter.getRateLimiterConfig().getLimitRefreshPeriod().toSeconds();
            logger.warn("[{}] Rate limit exceeded for client: {}", strategy, maskClientId(clientId));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.addHeader("Retry-After", String.valueOf(retryAfter));
            String message = strategy == Strategy.LOGIN
                    ? "Too many login attempts. Please try again in " + retryAfter + " seconds."
                    : "Rate limit exceeded. Please try again later.";
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"" + message + "\"}");
            return false;
        }
        return true;
    }

    private RateLimiter newBucket(String clientId) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(props.getLimit())
                .limitRefreshPeriod(Duration.ofMillis(props.getRefreshPeriod()))
                .timeoutDuration(Duration.ofMillis(props.getTimeout()))
                .build();
        return RateLimiterRegistry.of(config).rateLimiter(strategy.name() + ":" + clientId);
    }

    private String clientIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        return (apiKey != null && !apiKey.isBlank()) ? "apikey:" + apiKey : "ip:" + request.getRemoteAddr();
    }

    private String maskClientId(String clientId) {
        if (clientId == null || clientId.length() < 8) return "****";
        if (clientId.startsWith("apikey:")) return "apikey:****" + clientId.substring(clientId.length() - 4);
        return clientId;
    }

    /** Clears all per-client rate limiters — intended for test teardown only. */
    public void clearRateLimiters() {
        buckets.invalidateAll();
    }
}
