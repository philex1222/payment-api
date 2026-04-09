package com.example.paymentapi.event;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.WebhookEventType;

public class PaymentEvent {

    private final WebhookEventType eventType;
    private final String paymentOwner; // payment.getCreatedBy()
    private final PaymentResponse paymentSnapshot;

    public PaymentEvent(WebhookEventType eventType, String paymentOwner, PaymentResponse paymentSnapshot) {
        this.eventType = eventType;
        this.paymentOwner = paymentOwner;
        this.paymentSnapshot = paymentSnapshot;
    }

    public WebhookEventType getEventType() { return eventType; }
    public String getPaymentOwner() { return paymentOwner; }
    public PaymentResponse getPaymentSnapshot() { return paymentSnapshot; }
}
