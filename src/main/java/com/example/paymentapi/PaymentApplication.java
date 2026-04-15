package com.example.paymentapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * Payment API — Spring Boot application entry point.
 *
 * <p>{@code @EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)} opts into
 * Spring Data's stable {@code Page} JSON serialisation format introduced in
 * Spring Data Commons 3.3. Without this annotation Spring warns at runtime:
 * "Serializing PageImpl instances as-is is not supported". VIA_DTO serialises
 * page content via an internal {@code SpringDataPageImpl} proxy DTO whose JSON
 * structure is guaranteed to be stable across Spring Data versions.
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
