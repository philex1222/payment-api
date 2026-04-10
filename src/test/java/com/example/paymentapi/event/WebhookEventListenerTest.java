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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WebhookEventListenerTest {

    @Autowired private WebhookEventListener webhookEventListener;
    @Autowired private WebhookService webhookService;
    @Autowired private WebhookDeliveryRepository deliveryRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private PaymentResponse samplePayment;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        samplePayment = PaymentResponse.builder()
                .id("pay-001")
                .amount(BigDecimal.valueOf(100))
                .currency("USD")
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();
        transactionTemplate = new TransactionTemplate(transactionManager);
        // Clean up from previous test
        deliveryRepository.deleteAll();
    }

    @Test
    void handlePaymentEvent_createsDeliveryForMatchingSubscription() throws InterruptedException {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                    .targetUrl("http://example.com/hook")
                    .bearerToken("tok")
                    .eventTypes(List.of("PAYMENT_COMPLETED"))
                    .build();
            webhookService.createSubscription(req, "user");
        });

        // After the transaction above commits, the event listener should fire
        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);

        // This triggers the async listener
        transactionTemplate.executeWithoutResult(status -> {
            webhookEventListener.handlePaymentEvent(event);
        });

        // Wait for async operation to complete
        Thread.sleep(500);

        var deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(deliveries.get(0).getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(deliveries.get(0).getPaymentId()).isEqualTo("pay-001");
    }

    @Test
    void handlePaymentEvent_inactiveSubscription_noDeliveryCreated() {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                    .targetUrl("http://example.com/hook")
                    .bearerToken("tok")
                    .eventTypes(List.of("PAYMENT_COMPLETED"))
                    .active(false)
                    .build();
            webhookService.createSubscription(req, "user");
        });

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        transactionTemplate.executeWithoutResult(status -> {
            webhookEventListener.handlePaymentEvent(event);
        });

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void handlePaymentEvent_differentEventType_noDeliveryCreated() {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                    .targetUrl("http://example.com/hook")
                    .bearerToken("tok")
                    .eventTypes(List.of("PAYMENT_COMPLETED"))
                    .build();
            webhookService.createSubscription(req, "user");
        });

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_FAILED, "user", samplePayment);
        transactionTemplate.executeWithoutResult(status -> {
            webhookEventListener.handlePaymentEvent(event);
        });

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void handlePaymentEvent_statusChangedCatchAll_matchesAnyStatusEvent() throws InterruptedException {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                    .targetUrl("http://example.com/hook")
                    .bearerToken("tok")
                    .eventTypes(List.of("PAYMENT_STATUS_CHANGED"))
                    .build();
            webhookService.createSubscription(req, "user");
        });

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_FAILED, "user", samplePayment);
        transactionTemplate.executeWithoutResult(status -> {
            webhookEventListener.handlePaymentEvent(event);
        });

        // Wait for async operation to complete
        Thread.sleep(500);

        assertThat(deliveryRepository.findAll()).hasSize(1);
    }

    @Test
    void handlePaymentEvent_adminScope_receivesOtherUsersEvents() throws InterruptedException {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                    .targetUrl("http://example.com/hook")
                    .bearerToken("tok")
                    .eventTypes(List.of("PAYMENT_COMPLETED"))
                    .adminScope(true)
                    .build();
            webhookService.createSubscription(req, "admin");
        });

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        transactionTemplate.executeWithoutResult(status -> {
            webhookEventListener.handlePaymentEvent(event);
        });

        // Wait for async operation to complete
        Thread.sleep(500);

        assertThat(deliveryRepository.findAll()).hasSize(1);
    }

    @Test
    void handlePaymentEvent_wrongOwner_userScopedSubscriptionSkipped() {
        transactionTemplate.executeWithoutResult(status -> {
            WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                    .targetUrl("http://example.com/hook")
                    .bearerToken("tok")
                    .eventTypes(List.of("PAYMENT_COMPLETED"))
                    .build();
            webhookService.createSubscription(req, "admin");
        });

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        transactionTemplate.executeWithoutResult(status -> {
            webhookEventListener.handlePaymentEvent(event);
        });

        assertThat(deliveryRepository.findAll()).isEmpty();
    }
}
