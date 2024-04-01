package com.example.paymentapi.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey == null) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "Missing API key");
            return false;
        }

        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(apiKey, key -> createRateLimiter());

        boolean allowRequest = rateLimiter.acquirePermission();
        if (!allowRequest) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many requests");
            return false;
        }

        response.addHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getLimit()));
        response.addHeader("X-RateLimit-Remaining", String.valueOf(rateLimiter.getMetrics().getAvailablePermissions()));
        response.addHeader("X-RateLimit-Reset", String.valueOf(rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod().toMillis()));

        return true;
    }

    private RateLimiter createRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMillis(rateLimitProperties.getRefreshPeriod()))
                .limitForPeriod(rateLimitProperties.getLimit())
                .timeoutDuration(Duration.ofMillis(rateLimitProperties.getTimeout()))
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        return registry.rateLimiter("default");
    }
}