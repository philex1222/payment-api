package com.example.paymentapi.exception;

import com.example.paymentapi.model.PaymentStatus;

/**
 * Exception thrown when an invalid payment status transition is attempted.
 */
public class InvalidStatusTransitionException extends RuntimeException {
    private final String paymentId;
    private final String currentStatus;
    private final String targetStatus;

    public InvalidStatusTransitionException(String paymentId, String currentStatus, String targetStatus) {
        super(String.format("Invalid status transition for payment %s: cannot transition from %s to %s",
                paymentId, currentStatus, targetStatus));
        this.paymentId = paymentId;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public InvalidStatusTransitionException(String paymentId, PaymentStatus currentStatus, PaymentStatus targetStatus) {
        this(paymentId, currentStatus.getCode(), targetStatus.getCode());
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public String getTargetStatus() {
        return targetStatus;
    }
}
