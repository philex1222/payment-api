package com.example.paymentapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger configuration using SpringDoc (Spring Boot 3 compatible).
 * Accessible at /swagger-ui.html and /v3/api-docs.
 */
@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

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
