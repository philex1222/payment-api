package com.example.paymentapi.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Enumeration representing the possible statuses of a payment.
 * Defines valid status transitions to enforce payment state machine.
 */
public enum PaymentStatus {
    PENDING("PENDING", "Payment is pending processing"),
    PROCESSING("PROCESSING", "Payment is being processed"),
    COMPLETED("COMPLETED", "Payment completed successfully"),
    FAILED("FAILED", "Payment failed"),
    CANCELLED("CANCELLED", "Payment was cancelled"),
    REVERSED("REVERSED", "Payment was reversed"),
    REFUNDED("REFUNDED", "Payment was refunded");

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

    /**
     * Returns the set of valid next statuses from the current status.
     */
    public Set<PaymentStatus> getValidTransitions() {
        switch (this) {
            case PENDING:
                return new HashSet<>(Arrays.asList(PROCESSING, CANCELLED, FAILED));
            case PROCESSING:
                return new HashSet<>(Arrays.asList(COMPLETED, FAILED));
            case COMPLETED:
                return new HashSet<>(Arrays.asList(REVERSED, REFUNDED));
            case FAILED:
                return new HashSet<>(Collections.singletonList(PENDING)); // Allow retry
            case CANCELLED:
                return Collections.emptySet(); // Terminal state
            case REVERSED:
                return Collections.emptySet(); // Terminal state
            case REFUNDED:
                return Collections.emptySet(); // Terminal state
            default:
                return Collections.emptySet();
        }
    }

    /**
     * Checks if transitioning to the target status is valid.
     */
    public boolean canTransitionTo(PaymentStatus target) {
        return getValidTransitions().contains(target);
    }

    /**
     * Returns true if this is a terminal (final) status.
     */
    public boolean isTerminal() {
        return getValidTransitions().isEmpty();
    }

    /**
     * Parses a string to PaymentStatus, case-insensitive.
     */
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

    /**
     * Checks if the given string is a valid payment status.
     */
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
