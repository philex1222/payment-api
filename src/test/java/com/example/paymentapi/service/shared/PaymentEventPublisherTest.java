package com.example.paymentapi.service.shared;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.event.PaymentEvent;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.WebhookEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void publish_emitsPaymentEventWithGivenFields() {
        PaymentEventPublisher publisher = new PaymentEventPublisher(applicationEventPublisher);
        PaymentResponse response = new PaymentResponse();
        response.setId("p-1");

        publisher.publish(WebhookEventType.PAYMENT_COMPLETED, "alice", response);

        ArgumentCaptor<PaymentEvent> captor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        PaymentEvent event = captor.getValue();
        assertEquals(WebhookEventType.PAYMENT_COMPLETED, event.getEventType());
        assertEquals("alice", event.getPaymentOwner());
        assertEquals("p-1", event.getPaymentSnapshot().getId());
    }

    @Test
    void resolveEventType_mapsEachTerminalStatus() {
        PaymentEventPublisher publisher = new PaymentEventPublisher(applicationEventPublisher);
        assertEquals(WebhookEventType.PAYMENT_COMPLETED, publisher.resolveEventType(PaymentStatus.COMPLETED));
        assertEquals(WebhookEventType.PAYMENT_FAILED, publisher.resolveEventType(PaymentStatus.FAILED));
        assertEquals(WebhookEventType.PAYMENT_CANCELLED, publisher.resolveEventType(PaymentStatus.CANCELLED));
        assertEquals(WebhookEventType.PAYMENT_REVERSED, publisher.resolveEventType(PaymentStatus.REVERSED));
        assertEquals(WebhookEventType.PAYMENT_REFUNDED, publisher.resolveEventType(PaymentStatus.REFUNDED));
        assertEquals(WebhookEventType.PAYMENT_STATUS_CHANGED, publisher.resolveEventType(PaymentStatus.PENDING));
        assertEquals(WebhookEventType.PAYMENT_STATUS_CHANGED, publisher.resolveEventType(PaymentStatus.PROCESSING));
    }
}
