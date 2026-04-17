package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.UserService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.temporal.config.TemporalProperties;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentNotificationActivitiesImplTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private PaymentEventPublisher eventPublisher;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMapper mapper;

    @Mock
    private UserService userService;

    private final TemporalProperties temporalProperties = new TemporalProperties();

    private PaymentNotificationActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        activities = new PaymentNotificationActivitiesImpl(
                notificationService, eventPublisher, paymentRepository, mapper,
                userService, temporalProperties);
    }

    // ── sendNotification ───────────────────────────────────────────────────────

    @Test
    void sendNotification_delegatesToNotificationService() {
        activities.sendNotification("pay-001", "user@example.com", "Payment completed");
        verify(notificationService).sendPaymentNotification("user@example.com", "Payment completed");
    }

    @Test
    void sendNotification_propagatesException() {
        doThrow(new RuntimeException("SMTP down")).when(notificationService)
                .sendPaymentNotification(anyString(), anyString());
        assertThrows(RuntimeException.class,
                () -> activities.sendNotification("p", "e@e.com", "msg"));
    }

    // ── publishWebhookEvent ────────────────────────────────────────────────────

    @Test
    void publishWebhookEvent_loadsPaymentAndPublishes() {
        Payment payment = new Payment();
        payment.setId("pay-001");
        PaymentResponse response = new PaymentResponse();
        response.setId("pay-001");

        when(paymentRepository.findById("pay-001")).thenReturn(Optional.of(payment));
        when(mapper.toResponse(payment)).thenReturn(response);

        activities.publishWebhookEvent("pay-001", "admin");

        verify(eventPublisher).publish(
                eq(WebhookEventType.PAYMENT_CREATED), eq("admin"), eq(response));
    }

    @Test
    void publishWebhookEvent_throwsWhenPaymentNotFound() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(ApplicationFailure.class,
                () -> activities.publishWebhookEvent("missing", "admin"));
    }

    @Test
    void publishWebhookEvent_propagatesPublisherException() {
        Payment payment = new Payment();
        payment.setId("pay-002");
        PaymentResponse response = new PaymentResponse();
        when(paymentRepository.findById("pay-002")).thenReturn(Optional.of(payment));
        when(mapper.toResponse(payment)).thenReturn(response);
        doThrow(new RuntimeException("Webhook failed")).when(eventPublisher)
                .publish(any(), anyString(), any());

        assertThrows(RuntimeException.class,
                () -> activities.publishWebhookEvent("pay-002", "admin"));
    }
}
