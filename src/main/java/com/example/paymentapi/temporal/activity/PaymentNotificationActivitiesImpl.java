package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import org.springframework.stereotype.Service;

@Service
public class PaymentNotificationActivitiesImpl implements PaymentNotificationActivities {

    private final NotificationService notificationService;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper mapper;

    public PaymentNotificationActivitiesImpl(NotificationService notificationService,
                                             PaymentEventPublisher eventPublisher,
                                             PaymentRepository paymentRepository,
                                             PaymentMapper mapper) {
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.paymentRepository = paymentRepository;
        this.mapper = mapper;
    }

    @Override
    public void sendNotification(String paymentId, String email, String message) {
        notificationService.sendPaymentNotification(email, message);
    }

    @Override
    public void publishWebhookEvent(String paymentId, String createdBy) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        PaymentResponse response = mapper.toResponse(payment);
        eventPublisher.publish(WebhookEventType.PAYMENT_CREATED, createdBy, response);
    }
}
