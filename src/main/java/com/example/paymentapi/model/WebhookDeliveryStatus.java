package com.example.paymentapi.model;

/**
 * Type-safe delivery status for {@link WebhookDelivery}.
 * Stored as its name string in the database ({@code PENDING}, {@code DELIVERED}, {@code FAILED}).
 * The DB values match enum names exactly — no Flyway migration needed.
 */
public enum WebhookDeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED
}
