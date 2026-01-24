package com.example.paymentapi.exception;

/**
 * Exception thrown when a payment reversal fails.
 */
public class PaymentReversalException extends RuntimeException {
    private final String paymentId;
    private final String reason;

    public PaymentReversalException(String paymentId, String reason) {
        super(String.format("Failed to reverse payment %s: %s", paymentId, reason));
        this.paymentId = paymentId;
        this.reason = reason;
    }

    public PaymentReversalException(String paymentId, String reason, Throwable cause) {
        super(String.format("Failed to reverse payment %s: %s", paymentId, reason), cause);
        this.paymentId = paymentId;
        this.reason = reason;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getReason() {
        return reason;
    }
}
