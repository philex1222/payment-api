package com.example.paymentapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Separate configuration class for JPA Auditing.
 * Keeping @EnableJpaAuditing out of @SpringBootApplication allows
 * @WebMvcTest slices to load without a JPA context.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
