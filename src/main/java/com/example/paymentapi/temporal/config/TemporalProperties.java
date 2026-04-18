package com.example.paymentapi.temporal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised Temporal configuration. All values have production-sensible defaults,
 * so the minimum required properties are {@code temporal.host} (target server) and
 * {@code temporal.enabled} (set to {@code false} in test contexts that provide their
 * own {@link io.temporal.testing.TestWorkflowEnvironment}).
 *
 * Nested groups:
 * <ul>
 *   <li>{@link Workflow} – workflow-level timeouts (run + task).</li>
 *   <li>{@link Activity} – per-activity start-to-close + retry policies.</li>
 *   <li>{@link Worker} – worker concurrency + poller tuning.</li>
 *   <li>{@link Metrics} – Micrometer stats reporter toggle.</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "temporal")
public class TemporalProperties {
    private String host = "localhost:7233";
    private String namespace = "payment-api";
    private String taskQueue = "payment-creation-queue";
    private boolean enabled = true;

    /** Internal notification recipient domain — used by activities when resolving email for a username. */
    private String notificationDomain = "payments.internal";

    /**
     * Maximum time we block waiting for an initial gRPC connection to the Temporal server
     * before failing application startup. Short enough to surface outages quickly, long
     * enough to tolerate a rolling Temporal cluster restart.
     */
    private Duration connectTimeout = Duration.ofSeconds(30);

    private final Workflow workflow = new Workflow();
    private final Activity validation = new Activity(Duration.ofSeconds(10),
            new Retry(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(5)));
    private final Activity persistence = new Activity(Duration.ofSeconds(15),
            new Retry(3, Duration.ofSeconds(1), 1.5, Duration.ofSeconds(10)));
    private final Activity transfer = new Activity(Duration.ofMinutes(2),
            new Retry(5, Duration.ofMillis(500), 2.0, Duration.ofSeconds(30)));
    private final Activity notification = new Activity(Duration.ofSeconds(10),
            new Retry(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(5)));
    private final Duration transferHeartbeatTimeout = Duration.ofSeconds(30);

    private final Worker worker = new Worker();
    private final Metrics metrics = new Metrics();

    @Data
    public static class Workflow {
        /** Hard ceiling on a single workflow run end-to-end. */
        private Duration runTimeout = Duration.ofMinutes(10);
        /** Ceiling on a single workflow task (decision). Short by design. */
        private Duration taskTimeout = Duration.ofSeconds(10);
    }

    @Data
    public static class Activity {
        private Duration startToCloseTimeout;
        private Retry retry;

        public Activity() {}

        public Activity(Duration startToCloseTimeout, Retry retry) {
            this.startToCloseTimeout = startToCloseTimeout;
            this.retry = retry;
        }
    }

    @Data
    public static class Retry {
        private int maximumAttempts;
        private Duration initialInterval;
        private double backoffCoefficient;
        private Duration maximumInterval;

        public Retry() {}

        public Retry(int maximumAttempts, Duration initialInterval,
                     double backoffCoefficient, Duration maximumInterval) {
            this.maximumAttempts = maximumAttempts;
            this.initialInterval = initialInterval;
            this.backoffCoefficient = backoffCoefficient;
            this.maximumInterval = maximumInterval;
        }
    }

    @Data
    public static class Worker {
        /** Parallel activity executions on this worker. */
        private int maxConcurrentActivityExecutions = 100;
        /** Parallel workflow-task executions on this worker. */
        private int maxConcurrentWorkflowTaskExecutions = 50;
        /** Workflow-task poller thread count. */
        private int workflowPollThreadCount = 4;
        /** Activity poller thread count. */
        private int activityPollThreadCount = 4;
    }

    @Data
    public static class Metrics {
        /** Publish SDK client/worker metrics through Micrometer. */
        private boolean enabled = true;
    }
}
