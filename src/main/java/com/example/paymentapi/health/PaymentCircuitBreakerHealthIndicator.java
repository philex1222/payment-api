package com.example.paymentapi.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom health indicator that reports the state of all registered Resilience4j
 * circuit breakers under /actuator/health. DOWN is reported only when at least
 * one circuit is in OPEN state (meaning the downstream is actively shedding load).
 */
@Component("paymentCircuitBreaker")
public class PaymentCircuitBreakerHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentCircuitBreakerHealthIndicator(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyOpen = false;

        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            CircuitBreaker.State state = cb.getState();
            Map<String, Object> cbDetails = new LinkedHashMap<>();
            cbDetails.put("state", state.name());
            cbDetails.put("failureRate", formatRate(cb.getMetrics().getFailureRate()));
            cbDetails.put("slowCallRate", formatRate(cb.getMetrics().getSlowCallRate()));
            cbDetails.put("bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            cbDetails.put("failedCalls", cb.getMetrics().getNumberOfFailedCalls());
            cbDetails.put("notPermittedCalls", cb.getMetrics().getNumberOfNotPermittedCalls());
            details.put(cb.getName(), cbDetails);

            if (state == CircuitBreaker.State.OPEN) {
                anyOpen = true;
            }
        }

        if (details.isEmpty()) {
            return Health.up().withDetail("message", "No circuit breakers registered").build();
        }

        return anyOpen
                ? Health.down().withDetails(details).build()
                : Health.up().withDetails(details).build();
    }

    private String formatRate(float rate) {
        if (rate < 0) {
            return "N/A";
        }
        return String.format("%.1f%%", rate);
    }
}
