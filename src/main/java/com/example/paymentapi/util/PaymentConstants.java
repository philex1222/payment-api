package com.example.paymentapi.util;

/**
 * Compile-time constants for the payment domain.
 * No instances — all fields are public static final.
 */
public final class PaymentConstants {

    private PaymentConstants() {}

    // Cache names — must match ResilienceConfig definitions
    public static final String CACHE_PAYMENT_DETAIL     = "payment-detail";
    public static final String CACHE_USER_PAYMENT_LIST  = "user-payment-list";
    public static final String CACHE_IDEMPOTENCY        = "idempotency";

    // Error messages
    public static final String ERR_PAYMENT_NOT_FOUND    = "Payment not found with ID: ";
    public static final String ERR_INVALID_SOURCE       = "Invalid source account";
    public static final String ERR_INVALID_DESTINATION  = "Invalid destination account";
    public static final String ERR_SELF_TRANSFER        = "Source and destination accounts cannot be the same";
    public static final String ERR_INSUFFICIENT_FUNDS   = "Insufficient funds in the source account";
    public static final String ERR_REVERSAL_POSITIVE    = "Reversal amount must be positive";
    public static final String ERR_REVERSAL_EXCEEDS     = "Reversal amount cannot exceed original payment amount";
    public static final String ERR_DELETE_COMPLETED     = "Cannot delete a completed payment. Use reversal instead.";
    public static final String ERR_MAX_RETRIES          = "Payment has reached the maximum retry limit of ";

    // Audit event keys
    public static final String AUDIT_PAYMENT_CREATED    = "PAYMENT_CREATED";
    public static final String AUDIT_PAYMENT_COMPLETED  = "PAYMENT_COMPLETED";
    public static final String AUDIT_PAYMENT_FAILED     = "PAYMENT_FAILED";
    public static final String AUDIT_PAYMENT_CANCELLED  = "PAYMENT_CANCELLED";
    public static final String AUDIT_PAYMENT_DELETED    = "PAYMENT_DELETED";
    public static final String AUDIT_PAYMENT_RETRY      = "PAYMENT_RETRY_ATTEMPT:";
    public static final String AUDIT_RETRY_SUCCEEDED    = "PAYMENT_RETRY_SUCCEEDED";
}
