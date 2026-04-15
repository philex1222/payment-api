package com.example.paymentapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Consolidated persistence configuration — combines JPA auditing setup
 * (formerly JpaAuditingConfig).
 *
 * Keeping @EnableJpaAuditing in a separate class from @SpringBootApplication
 * allows @WebMvcTest slices to load without a JPA context.
 */
@Configuration
@EnableJpaAuditing
public class PersistenceConfig {
}
