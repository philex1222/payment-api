package com.example.paymentapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration — interceptors only.
 * CORS is handled by SecurityConfig's CorsConfigurationSource bean so that
 * Spring Security's CorsFilter runs before the JWT filter and pre-flight
 * OPTIONS requests are never rejected with 401/403.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final LoginRateLimitInterceptor loginRateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor,
                        LoginRateLimitInterceptor loginRateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.loginRateLimitInterceptor = loginRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // General rate limiter — 100 req/60s per client for all API routes
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/payments/**",
                        "/api/v1/auth/**",
                        "/api/v1/admin/**",
                        "/api/v1/webhooks/**"
                )
                .excludePathPatterns(
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                );

        // Stricter login limiter — 10 attempts/60s per client, brute-force protection.
        // Applied to /login and /register to prevent username enumeration and bulk account creation.
        registry.addInterceptor(loginRateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/login", "/api/v1/auth/register");
    }
}
