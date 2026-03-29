package com.example.paymentapi.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Enumeration representing the possible statuses of a payment.
 * Defines valid status transitions to enforce payment state machine.
 *
 * Valid transitions are stored as static EnumSet constants — allocated once
 * at class-loading time, not on every call to getValidTransitions().
 */
public enum PaymentStatus {
    PENDING("PENDING", "Payment is pending processing"),
    PROCESSING("PROCESSING", "Payment is being processed"),
    COMPLETED("COMPLETED", "Payment completed successfully"),
    FAILED("FAILED", "Payment failed"),
    CANCELLED("CANCELLED", "Payment was cancelled"),
    REVERSED("REVERSED", "Payment was reversed"),
    REFUNDED("REFUNDED", "Payment was refunded");

    // Pre-computed, unmodifiable transition sets — zero heap allocation at runtime
    private static final Set<PaymentStatus> PENDING_TRANSITIONS =
            Collections.unmodifiableSet(EnumSet.of(PROCESSING, CANCELLED, FAILED));
    private static final Set<PaymentStatus> PROCESSING_TRANSITIONS =
            Collections.unmodifiableSet(EnumSet.of(COMPLETED, FAILED));
    private static final Set<PaymentStatus> COMPLETED_TRANSITIONS =
            Collections.unmodifiableSet(EnumSet.of(REVERSED, REFUNDED));
    private static final Set<PaymentStatus> FAILED_TRANSITIONS =
            Collections.unmodifiableSet(EnumSet.of(PENDING));
    private static final Set<PaymentStatus> EMPTY_TRANSITIONS =
            Collections.emptySet();

    private final String code;
    private final String description;

    PaymentStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /** Returns the immutable set of valid next statuses from this status. */
    public Set<PaymentStatus> getValidTransitions() {
        return switch (this) {
            case PENDING    -> PENDING_TRANSITIONS;
            case PROCESSING -> PROCESSING_TRANSITIONS;
            case COMPLETED  -> COMPLETED_TRANSITIONS;
            case FAILED     -> FAILED_TRANSITIONS;
            default         -> EMPTY_TRANSITIONS; // CANCELLED, REVERSED, REFUNDED — terminal
        };
    }

    /** Returns true if transitioning to {@code target} is a valid operation. */
    public boolean canTransitionTo(PaymentStatus target) {
        return getValidTransitions().contains(target);
    }

    /** Returns true if this is a terminal (final) status with no outgoing transitions. */
    public boolean isTerminal() {
        return getValidTransitions().isEmpty();
    }

    /** Parses a string to PaymentStatus, case-insensitive. */
    public static PaymentStatus fromString(String status) {
        if (status == null || status.trim().isEmpty()) {
            throw new IllegalArgumentException("Status cannot be null or empty");
        }
        try {
            return PaymentStatus.valueOf(status.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid payment status: " + status +
                ". Valid values are: " + Arrays.toString(PaymentStatus.values()));
        }
    }

    /** Returns true if {@code status} is a valid PaymentStatus string. */
    public static boolean isValid(String status) {
        if (status == null || status.trim().isEmpty()) {
            return false;
        }
        try {
            PaymentStatus.valueOf(status.toUpperCase().trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
