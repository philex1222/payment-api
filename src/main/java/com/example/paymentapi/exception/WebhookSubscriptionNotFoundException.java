package com.example.paymentapi.exception;

public class WebhookSubscriptionNotFoundException extends RuntimeException {
    public WebhookSubscriptionNotFoundException(String message) {
        super(message);
    }
}
