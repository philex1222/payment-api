package com.example.paymentapi.temporal.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain-facing Micrometer counters for payment workflow outcomes.
 *
 * <p>These complement the Temporal SDK's built-in client/worker metrics
 * ({@code temporal_*}) by exposing business-level counts that ops dashboards
 * and SLO alerts can query directly — without joining on the SDK's lower-level
 * execution metrics.</p>
 *
 * Metrics published:
 * <ul>
 *   <li>{@code payment.workflow.started} — tag {@code idempotent=true|false}.</li>
 *   <li>{@code payment.workflow.completed} — payment reached {@code COMPLETED}.</li>
 *   <li>{@code payment.workflow.failed} — tag {@code reason=<code>}.</li>
 *   <li>{@code payment.workflow.cancelled} — cancel signal accepted.</li>
 * </ul>
 *
 * Reason codes are derived from the raw failure string via
 * {@link #reasonCodeFor(String)} so cardinality stays bounded — never emit the
 * full failure message as a tag (infinite cardinality = unusable dashboards).
 */
@Component
public class PaymentWorkflowMetrics {

    public static final String METRIC_STARTED   = "payment.workflow.started";
    public static final String METRIC_COMPLETED = "payment.workflow.completed";
    public static final String METRIC_FAILED    = "payment.workflow.failed";
    public static final String METRIC_CANCELLED = "payment.workflow.cancelled";

    public static final String REASON_CANCELLED_BY_USER = "cancelled_by_user";
    public static final String REASON_INSUFFICIENT_FUNDS = "insufficient_funds";
    public static final String REASON_INVALID_ACCOUNT = "invalid_account";
    public static final String REASON_VALIDATION = "validation";
    public static final String REASON_TRANSFER_FAILED = "transfer_failed";
    public static final String REASON_OTHER = "other";

    private final MeterRegistry registry;
    private final Counter completed;
    private final Counter cancelled;

    public PaymentWorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.completed = Counter.builder(METRIC_COMPLETED)
                .description("Payment workflows that reached COMPLETED")
                .register(registry);
        this.cancelled = Counter.builder(METRIC_CANCELLED)
                .description("Payment workflows cancelled via signal")
                .register(registry);
    }

    /** Called when the controller starts a new payment workflow. */
    public void recordStarted(boolean withIdempotencyKey) {
        Counter.builder(METRIC_STARTED)
                .description("Payment workflows started")
                .tag("idempotent", Boolean.toString(withIdempotencyKey))
                .register(registry)
                .increment();
    }

    /** Called by the persistence activity when a payment is marked COMPLETED. */
    public void recordCompleted() {
        completed.increment();
    }

    /**
     * Called by the persistence activity when a payment is marked FAILED.
     * The raw failure message is bucketed into a low-cardinality reason code.
     */
    public void recordFailed(String rawReason) {
        Counter.builder(METRIC_FAILED)
                .description("Payment workflows that reached FAILED")
                .tag("reason", reasonCodeFor(rawReason))
                .register(registry)
                .increment();
    }

    /** Called by the controller when the cancel signal is accepted. */
    public void recordCancelled() {
        cancelled.increment();
    }

    /**
     * Bucket raw failure messages into a fixed set of reason codes.
     * Order matters: cancellation is checked first so that user-cancelled
     * workflows (which also route through {@code failPayment}) count as
     * cancellations in both the failed and cancelled metrics.
     *
     * @return one of the {@code REASON_*} constants; never the raw string.
     */
    public static String reasonCodeFor(String raw) {
        if (raw == null || raw.isBlank()) return REASON_OTHER;
        String lower = raw.toLowerCase();
        if (lower.contains("cancelled-by-user") || lower.contains("cancelled_by_user")) {
            return REASON_CANCELLED_BY_USER;
        }
        if (lower.contains("insufficientfunds") || lower.contains("insufficient funds")
                || lower.contains("insufficient_funds")) {
            return REASON_INSUFFICIENT_FUNDS;
        }
        if (lower.contains("invalidaccount") || lower.contains("invalid account")
                || lower.contains("invalid_account")) {
            return REASON_INVALID_ACCOUNT;
        }
        if (lower.contains("validation")) return REASON_VALIDATION;
        if (lower.contains("transfer")) return REASON_TRANSFER_FAILED;
        return REASON_OTHER;
    }
}
