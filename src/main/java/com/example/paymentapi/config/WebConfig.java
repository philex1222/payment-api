package com.example.paymentapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Consolidated web configuration — combines MVC interceptor registration (formerly WebMvcConfig)
 * and OpenAPI/Swagger configuration (formerly SwaggerConfig).
 *
 * CORS is handled by SecurityConfig's CorsConfigurationSource bean so that
 * Spring Security's CorsFilter runs before the JWT filter and pre-flight
 * OPTIONS requests are never rejected with 401/403.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    private final RateLimitProperties rateLimitProperties;

    @Value("${rate-limit.login.limit:10}")
    private int loginLimit;

    @Value("${rate-limit.login.refreshPeriod:60000}")
    private long loginRefreshPeriodMs;

    /**
     * Constructor injection — RateLimitProperties is marked required=false so that
     * {@code @WebMvcTest} slices (which don't do full component scanning) can still
     * load this configuration class without the properties bean. Interceptor registration
     * is skipped if properties are null.
     */
    public WebConfig(@org.springframework.beans.factory.annotation.Autowired(required = false)
                     RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    // ── Interceptors (formerly WebMvcConfig) ──────────────────────────────────

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (rateLimitProperties == null) {
            // Skip interceptor registration in @WebMvcTest slices where RateLimitProperties
            // is not available. The interceptors are mocked in those test contexts.
            return;
        }
        // General rate limiter — uses injected properties for all API routes
        RateLimitInterceptor generalInterceptor =
                new RateLimitInterceptor(rateLimitProperties, RateLimitInterceptor.Strategy.GENERAL);
        registry.addInterceptor(generalInterceptor)
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
        RateLimitProperties loginProps = new RateLimitProperties();
        loginProps.setLimit(loginLimit);
        loginProps.setRefreshPeriod(loginRefreshPeriodMs);
        loginProps.setTimeout(0L);
        RateLimitInterceptor loginInterceptor =
                new RateLimitInterceptor(loginProps, RateLimitInterceptor.Strategy.LOGIN);
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/v1/auth/login", "/api/v1/auth/register");
    }

    // ── OpenAPI / Swagger (formerly SwaggerConfig) ────────────────────────────

    @Bean
    public OpenAPI paymentApiOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment API")
                        .description("RESTful API for secure payment processing")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Payment Team")
                                .email("payment@example.com")))
                .addServersItem(new Server()
                        .url("http://localhost:8080")
                        .description("Local development"))
                .addServersItem(new Server()
                        .url("https://api.payment.example.com")
                        .description("Production"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
