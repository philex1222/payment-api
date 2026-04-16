package com.example.paymentapi.util;

public final class WebhookConstants {

    private WebhookConstants() {}

    public static final String ERR_WEBHOOK_NOT_FOUND    = "Webhook subscription not found with ID: ";
    public static final String ERR_DUPLICATE_ENDPOINT   = "A subscription with this endpoint already exists";
    public static final String AUDIT_WEBHOOK_REGISTERED = "WEBHOOK_REGISTERED";
    public static final String AUDIT_WEBHOOK_DELETED    = "WEBHOOK_DELETED";
    public static final int    MAX_DELIVERY_ATTEMPTS    = 3;
    public static final long   RETRY_BACKOFF_MS         = 1000L;
}
