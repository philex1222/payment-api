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

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/payments/**",
                        "/api/v1/auth/**",
                        "/api/v1/admin/**"
                )
                .excludePathPatterns(
                        "/actuator/**",
                        "/swagger-ui/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                );
    }
}
