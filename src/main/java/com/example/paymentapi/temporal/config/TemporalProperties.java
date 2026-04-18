package com.example.paymentapi.temporal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
            new Retry(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(5),
                    defaultDoNotRetryForValidation()));
    private final Activity persistence = new Activity(Duration.ofSeconds(15),
            new Retry(3, Duration.ofSeconds(1), 1.5, Duration.ofSeconds(10),
                    defaultDoNotRetryForPersistence()));
    private final Activity transfer = new Activity(Duration.ofMinutes(2),
            new Retry(5, Duration.ofMillis(500), 2.0, Duration.ofSeconds(30),
                    defaultDoNotRetryForTransfer()));
    private final Activity notification = new Activity(Duration.ofSeconds(10),
            new Retry(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(5), new ArrayList<>()));
    private final Duration transferHeartbeatTimeout = Duration.ofSeconds(30);

    /**
     * Defense-in-depth: belt-and-braces list of exception class names that must never be retried,
     * regardless of whether the activity correctly wraps them in {@code ApplicationFailure}.
     * Validation failures are deterministic — retrying wastes the retry budget on a known "no".
     */
    private static List<String> defaultDoNotRetryForValidation() {
        List<String> list = new ArrayList<>();
        list.add("com.example.paymentapi.exception.InvalidAccountException");
        list.add("com.example.paymentapi.exception.InsufficientFundsException");
        list.add("java.lang.IllegalArgumentException");
        list.add("java.lang.IllegalStateException");
        return list;
    }

    /** Persistence layer: referential/lookup failures are deterministic. */
    private static List<String> defaultDoNotRetryForPersistence() {
        List<String> list = new ArrayList<>();
        list.add("java.lang.IllegalArgumentException");
        return list;
    }

    /** Transfer layer: only deterministic pre-conditions are non-retryable; network errors retry. */
    private static List<String> defaultDoNotRetryForTransfer() {
        List<String> list = new ArrayList<>();
        list.add("com.example.paymentapi.exception.InvalidAccountException");
        list.add("com.example.paymentapi.exception.InsufficientFundsException");
        return list;
    }

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
        /**
         * Fully-qualified exception class names that Temporal must never retry.
         * Defense-in-depth against activity code that fails to wrap a deterministic
         * business error in {@link io.temporal.failure.ApplicationFailure#newNonRetryableFailure}.
         */
        private List<String> doNotRetry = new ArrayList<>();

        public Retry() {}

        public Retry(int maximumAttempts, Duration initialInterval,
                     double backoffCoefficient, Duration maximumInterval) {
            this(maximumAttempts, initialInterval, backoffCoefficient, maximumInterval, new ArrayList<>());
        }

        public Retry(int maximumAttempts, Duration initialInterval,
                     double backoffCoefficient, Duration maximumInterval,
                     List<String> doNotRetry) {
            this.maximumAttempts = maximumAttempts;
            this.initialInterval = initialInterval;
            this.backoffCoefficient = backoffCoefficient;
            this.maximumInterval = maximumInterval;
            this.doNotRetry = doNotRetry != null ? doNotRetry : new ArrayList<>();
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
