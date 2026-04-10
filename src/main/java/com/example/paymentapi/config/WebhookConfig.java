package com.example.paymentapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WebhookConfig {

    /**
     * Dedicated RestClient for webhook deliveries.
     * Uses Spring Boot's auto-configured RestClient.Builder (prototype scope)
     * to get a fresh builder with default message converters.
     * The bean name "webhookRestClient" allows @MockitoBean replacement in tests.
     */
    @Bean
    public RestClient webhookRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
