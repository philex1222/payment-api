package com.example.paymentapi.temporal.config;

import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Surfaces the Temporal server's reachability under Spring Actuator's {@code /health}
 * endpoint. Calls {@code GetSystemInfo} — a lightweight RPC that is safe to invoke on
 * every health probe and returns the server's build info tags.
 */
@Component("temporal")
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalHealthIndicator implements HealthIndicator {

    private final WorkflowServiceStubs stubs;
    private final TemporalProperties props;

    public TemporalHealthIndicator(WorkflowServiceStubs stubs, TemporalProperties props) {
        this.stubs = stubs;
        this.props = props;
    }

    @Override
    public Health health() {
        try {
            var info = stubs.blockingStub().getSystemInfo(GetSystemInfoRequest.newBuilder().build());
            return Health.up()
                    .withDetail("namespace", props.getNamespace())
                    .withDetail("taskQueue", props.getTaskQueue())
                    .withDetail("serverVersion", info.getServerVersion())
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("namespace", props.getNamespace())
                    .withDetail("host", props.getHost())
                    .build();
        }
    }
}
