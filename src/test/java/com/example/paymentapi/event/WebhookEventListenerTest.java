package com.example.paymentapi.event;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class WebhookEventListenerTest {

    @Autowired private WebhookEventListener webhookEventListener;
    @Autowired private WebhookService webhookService;
    @Autowired private WebhookDeliveryRepository deliveryRepository;

    private PaymentResponse samplePayment;

    @BeforeEach
    void setUp() {
        samplePayment = PaymentResponse.builder()
                .id("pay-001")
                .amount(BigDecimal.valueOf(100))
                .currency("USD")
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void handlePaymentEvent_createsDeliveryForMatchingSubscription() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        var deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(deliveries.get(0).getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(deliveries.get(0).getPaymentId()).isEqualTo("pay-001");
    }

    @Test
    void handlePaymentEvent_inactiveSubscription_noDeliveryCreated() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .active(false)
                .build();
        webhookService.createSubscription(req, "user");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void handlePaymentEvent_differentEventType_noDeliveryCreated() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_FAILED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void handlePaymentEvent_statusChangedCatchAll_matchesAnyStatusEvent() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_STATUS_CHANGED"))
                .build();
        webhookService.createSubscription(req, "user");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_FAILED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).hasSize(1);
    }

    @Test
    void handlePaymentEvent_adminScope_receivesOtherUsersEvents() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .adminScope(true)
                .build();
        webhookService.createSubscription(req, "admin");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).hasSize(1);
    }

    @Test
    void handlePaymentEvent_wrongOwner_userScopedSubscriptionSkipped() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "admin");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).isEmpty();
    }
}
