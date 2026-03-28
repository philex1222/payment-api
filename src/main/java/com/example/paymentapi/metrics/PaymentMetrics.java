package com.example.paymentapi.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Business-level Micrometer metrics for the payment domain.
 *
 * All counters are exposed as {@code payment_*_total} in Prometheus format
 * and can be visualised in the bundled Grafana dashboard.
 */
@Component
public class PaymentMetrics {

    private final Counter paymentsCreated;
    private final Counter paymentsCompleted;
    private final Counter paymentsFailed;
    private final Counter paymentsCancelled;
    private final Counter paymentsRetried;
    private final Counter paymentsRetriedSuccess;
    private final Timer paymentDuration;
    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;

        paymentsCreated = Counter.builder("payment.created")
                .description("Total number of payment requests initiated")
                .register(registry);

        paymentsCompleted = Counter.builder("payment.completed")
                .description("Total number of payments completed successfully")
                .register(registry);

        paymentsFailed = Counter.builder("payment.failed")
                .description("Total number of payment failures")
                .register(registry);

        paymentsCancelled = Counter.builder("payment.cancelled")
                .description("Total number of payments cancelled by the client")
                .register(registry);

        paymentsRetried = Counter.builder("payment.retried")
                .description("Total number of payment retry attempts")
                .register(registry);

        paymentsRetriedSuccess = Counter.builder("payment.retried.success")
                .description("Total number of payment retries that succeeded")
                .register(registry);

        paymentDuration = Timer.builder("payment.processing.duration")
                .description("End-to-end payment processing time from creation to terminal state")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void incrementCreated() {
        paymentsCreated.increment();
    }

    public void incrementCompleted() {
        paymentsCompleted.increment();
    }

    public void incrementFailed() {
        paymentsFailed.increment();
    }

    public void incrementCancelled() {
        paymentsCancelled.increment();
    }

    public void incrementRetried() {
        paymentsRetried.increment();
    }

    public void incrementRetriedSuccess() {
        paymentsRetriedSuccess.increment();
    }

    /** Start a timing sample — call {@link #stopTimer(Timer.Sample)} when the operation ends. */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /** Record elapsed time from the given sample into the payment duration histogram. */
    public void stopTimer(Timer.Sample sample) {
        sample.stop(paymentDuration);
    }
}
