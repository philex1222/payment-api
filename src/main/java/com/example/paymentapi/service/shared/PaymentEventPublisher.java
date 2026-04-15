package com.example.paymentapi.service.shared;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.event.PaymentEvent;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.model.PaymentStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {

    private final ApplicationEventPublisher publisher;

    public PaymentEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(WebhookEventType type, String createdBy, PaymentResponse response) {
        publisher.publishEvent(new PaymentEvent(type, createdBy, response));
    }

    public WebhookEventType resolveEventType(PaymentStatus status) {
        return switch (status) {
            case COMPLETED  -> WebhookEventType.PAYMENT_COMPLETED;
            case FAILED     -> WebhookEventType.PAYMENT_FAILED;
            case CANCELLED  -> WebhookEventType.PAYMENT_CANCELLED;
            case REVERSED   -> WebhookEventType.PAYMENT_REVERSED;
            case REFUNDED   -> WebhookEventType.PAYMENT_REFUNDED;
            default         -> WebhookEventType.PAYMENT_STATUS_CHANGED;
        };
    }
}
