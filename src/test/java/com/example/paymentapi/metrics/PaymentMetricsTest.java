package com.example.paymentapi.metrics;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentMetricsTest {

    private MeterRegistry registry;
    private PaymentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PaymentMetrics(registry);
    }

    @Test
    void registersAllCounters() {
        assertNotNull(registry.find("payment.created").counter());
        assertNotNull(registry.find("payment.completed").counter());
        assertNotNull(registry.find("payment.failed").counter());
        assertNotNull(registry.find("payment.cancelled").counter());
        assertNotNull(registry.find("payment.retried").counter());
        assertNotNull(registry.find("payment.retried.success").counter());
        assertNotNull(registry.find("payment.reversed").counter());
        assertNotNull(registry.find("payment.refunded").counter());
        assertNotNull(registry.find("payment.processing.duration").timer());
    }

    @Test
    void incrementCreatedBumpsCounter() {
        metrics.incrementCreated();
        metrics.incrementCreated();
        assertEquals(2.0, registry.find("payment.created").counter().count());
    }

    @Test
    void incrementCompletedBumpsCounter() {
        metrics.incrementCompleted();
        assertEquals(1.0, registry.find("payment.completed").counter().count());
    }

    @Test
    void incrementFailedBumpsCounter() {
        metrics.incrementFailed();
        assertEquals(1.0, registry.find("payment.failed").counter().count());
    }

    @Test
    void incrementCancelledBumpsCounter() {
        metrics.incrementCancelled();
        assertEquals(1.0, registry.find("payment.cancelled").counter().count());
    }

    @Test
    void incrementRetriedBumpsCounter() {
        metrics.incrementRetried();
        assertEquals(1.0, registry.find("payment.retried").counter().count());
    }

    @Test
    void incrementRetriedSuccessBumpsCounter() {
        metrics.incrementRetriedSuccess();
        assertEquals(1.0, registry.find("payment.retried.success").counter().count());
    }

    @Test
    void incrementReversedBumpsCounter() {
        metrics.incrementReversed();
        assertEquals(1.0, registry.find("payment.reversed").counter().count());
    }

    @Test
    void incrementRefundedBumpsCounter() {
        metrics.incrementRefunded();
        assertEquals(1.0, registry.find("payment.refunded").counter().count());
    }

    @Test
    void timerRecordsDurationSample() {
        Timer.Sample sample = metrics.startTimer();
        metrics.stopTimer(sample);
        assertEquals(1L, registry.find("payment.processing.duration").timer().count());
    }
}
