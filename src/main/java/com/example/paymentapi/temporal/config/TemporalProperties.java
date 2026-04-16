package com.example.paymentapi.temporal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "temporal")
public class TemporalProperties {
    private String host = "localhost:7233";
    private String namespace = "payment-api";
    private String taskQueue = "payment-creation-queue";
    private boolean enabled = true;
}
