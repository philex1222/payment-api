package com.example.paymentapi.temporal.config;

import io.temporal.activity.ActivityOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalOptionsFactoryTest {

    private TemporalProperties newProps() {
        // All defaults are seeded in TemporalProperties' field initializers
        return new TemporalProperties();
    }

    @Test
    void activityOptionsByType_mapsAllKnownActivities() {
        Map<String, ActivityOptions> map = TemporalOptionsFactory.activityOptionsByType(newProps());

        assertThat(map).containsKeys(
                "ValidateAccounts", "ValidateFunds",
                "PersistPending", "CompletePayment", "FailPayment",
                "TransferFunds",
                "SendNotification", "PublishWebhookEvent");

        // Validation timeout = 10s (default)
        assertThat(map.get("ValidateAccounts").getStartToCloseTimeout())
                .isEqualTo(Duration.ofSeconds(10));
        // Transfer timeout = 2m, with heartbeat
        assertThat(map.get("TransferFunds").getStartToCloseTimeout())
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(map.get("TransferFunds").getHeartbeatTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void defaultActivityOptions_mirrorsNotificationTier() {
        ActivityOptions opts = TemporalOptionsFactory.defaultActivityOptions(newProps());
        assertThat(opts.getStartToCloseTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void workflowImplementationOptions_exposesBothActivityMaps() {
        WorkflowImplementationOptions opts =
                TemporalOptionsFactory.workflowImplementationOptions(newProps());
        assertThat(opts.getActivityOptions()).isNotEmpty();
        assertThat(opts.getDefaultActivityOptions()).isNotNull();
    }

    @Test
    void workerOptions_honoursWorkerProperties() {
        TemporalProperties props = newProps();
        props.getWorker().setMaxConcurrentActivityExecutions(77);
        props.getWorker().setMaxConcurrentWorkflowTaskExecutions(22);
        props.getWorker().setWorkflowPollThreadCount(3);
        props.getWorker().setActivityPollThreadCount(5);

        WorkerOptions opts = TemporalOptionsFactory.workerOptions(props);
        assertThat(opts.getMaxConcurrentActivityExecutionSize()).isEqualTo(77);
        assertThat(opts.getMaxConcurrentWorkflowTaskExecutionSize()).isEqualTo(22);
        assertThat(opts.getWorkflowPollThreadCount()).isEqualTo(3);
        assertThat(opts.getActivityPollThreadCount()).isEqualTo(5);
    }

    @Test
    void retryOptions_propagateFromProperties() {
        TemporalProperties props = newProps();
        // Use transfer tier: 5 attempts, 500ms initial, coef 2.0, max 30s
        ActivityOptions transfer = TemporalOptionsFactory.activityOptionsByType(props).get("TransferFunds");
        assertThat(transfer.getRetryOptions().getMaximumAttempts()).isEqualTo(5);
        assertThat(transfer.getRetryOptions().getInitialInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(transfer.getRetryOptions().getBackoffCoefficient()).isEqualTo(2.0);
        assertThat(transfer.getRetryOptions().getMaximumInterval()).isEqualTo(Duration.ofSeconds(30));
    }
}
