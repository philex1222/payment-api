package com.example.paymentapi.temporal.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.paymentapi.temporal.metrics.PaymentWorkflowMetrics.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PaymentWorkflowMetrics} using an in-memory
 * {@link SimpleMeterRegistry} — no Prometheus dependency required.
 */
class PaymentWorkflowMetricsTest {

    private SimpleMeterRegistry registry;
    private PaymentWorkflowMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PaymentWorkflowMetrics(registry);
    }

    @Test
    void recordStarted_withIdempotencyKey_incrementsTaggedCounter() {
        metrics.recordStarted(true);
        metrics.recordStarted(true);

        Counter c = registry.find(METRIC_STARTED).tag("idempotent", "true").counter();
        assertNotNull(c);
        assertEquals(2.0, c.count());
    }

    @Test
    void recordStarted_withoutIdempotencyKey_partitionsSeparately() {
        metrics.recordStarted(true);
        metrics.recordStarted(false);

        assertEquals(1.0, registry.find(METRIC_STARTED).tag("idempotent", "true").counter().count());
        assertEquals(1.0, registry.find(METRIC_STARTED).tag("idempotent", "false").counter().count());
    }

    @Test
    void recordCompleted_incrementsCounter() {
        metrics.recordCompleted();
        metrics.recordCompleted();
        metrics.recordCompleted();

        assertEquals(3.0, registry.find(METRIC_COMPLETED).counter().count());
    }

    @Test
    void recordCancelled_incrementsCounter() {
        metrics.recordCancelled();
        assertEquals(1.0, registry.find(METRIC_CANCELLED).counter().count());
    }

    @Test
    void recordFailed_bucketsReasonToCancelledByUser() {
        metrics.recordFailed("cancelled-by-user: user-initiated");
        assertEquals(1.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_CANCELLED_BY_USER).counter().count());
    }

    @Test
    void recordFailed_bucketsReasonToInsufficientFunds() {
        metrics.recordFailed("InsufficientFunds: balance too low");
        assertEquals(1.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_INSUFFICIENT_FUNDS).counter().count());
    }

    @Test
    void recordFailed_bucketsReasonToInvalidAccount() {
        metrics.recordFailed("InvalidAccount: account 9999 not found");
        assertEquals(1.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_INVALID_ACCOUNT).counter().count());
    }

    @Test
    void recordFailed_bucketsReasonToValidation() {
        metrics.recordFailed("ValidationError: bad input");
        assertEquals(1.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_VALIDATION).counter().count());
    }

    @Test
    void recordFailed_bucketsReasonToTransferFailed() {
        metrics.recordFailed("Bank transfer timeout");
        assertEquals(1.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_TRANSFER_FAILED).counter().count());
    }

    @Test
    void recordFailed_bucketsUnknownAsOther() {
        metrics.recordFailed("something weird happened");
        assertEquals(1.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_OTHER).counter().count());
    }

    @Test
    void recordFailed_nullOrBlank_isBucketedAsOther() {
        metrics.recordFailed(null);
        metrics.recordFailed("");
        metrics.recordFailed("   ");
        assertEquals(3.0,
                registry.find(METRIC_FAILED).tag("reason", REASON_OTHER).counter().count());
    }

    @Test
    void reasonCodeFor_isCaseInsensitive() {
        assertEquals(REASON_INSUFFICIENT_FUNDS, reasonCodeFor("insufficientfunds: XYZ"));
        assertEquals(REASON_INVALID_ACCOUNT, reasonCodeFor("INVALIDACCOUNT: bad"));
        assertEquals(REASON_VALIDATION, reasonCodeFor("VALIDATION failed"));
    }
}
