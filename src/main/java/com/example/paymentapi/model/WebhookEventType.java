package com.example.paymentapi.model;

public enum WebhookEventType {
    PAYMENT_CREATED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    PAYMENT_CANCELLED,
    PAYMENT_REVERSED,
    PAYMENT_REFUNDED,
    PAYMENT_STATUS_CHANGED;

    public static boolean isValid(String value) {
        for (WebhookEventType type : values()) {
            if (type.name().equals(value)) return true;
        }
        return false;
    }
}
