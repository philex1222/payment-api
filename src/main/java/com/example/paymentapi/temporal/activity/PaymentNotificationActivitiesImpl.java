package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.UserService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.temporal.config.TemporalProperties;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentNotificationActivitiesImpl implements PaymentNotificationActivities {

    static final String ERR_PAYMENT_NOT_FOUND = "PaymentNotFound";

    private final NotificationService notificationService;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper mapper;
    private final UserService userService;
    private final TemporalProperties temporalProperties;

    public PaymentNotificationActivitiesImpl(NotificationService notificationService,
                                             PaymentEventPublisher eventPublisher,
                                             PaymentRepository paymentRepository,
                                             PaymentMapper mapper,
                                             UserService userService,
                                             TemporalProperties temporalProperties) {
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.paymentRepository = paymentRepository;
        this.mapper = mapper;
        this.userService = userService;
        this.temporalProperties = temporalProperties;
    }

    @Override
    public void sendNotification(String paymentId, String username, String message) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            String recipient = resolveRecipient(username);
            notificationService.sendPaymentNotification(recipient, message);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void publishWebhookEvent(String paymentId, String createdBy) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                            "Payment not found: " + paymentId, ERR_PAYMENT_NOT_FOUND));
            PaymentResponse response = mapper.toResponse(payment);
            eventPublisher.publish(
                    eventPublisher.resolveEventType(PaymentStatus.fromString(payment.getStatus())),
                    createdBy,
                    response);
        }
    }

    /**
     * Resolves a notification recipient address for the given {@code username}.
     * Returns the username as-is when it already looks like an address; otherwise
     * appends the configured internal notification domain. Unknown users still get
     * a synthesised recipient so payment confirmations are never dropped silently.
     */
    private String resolveRecipient(String username) {
        if (username == null || username.isBlank()) {
            return "unknown@" + temporalProperties.getNotificationDomain();
        }
        if (username.contains("@")) {
            return username;
        }
        // Touch the user service so a missing user surfaces as a log warning but does not fail the notification.
        userService.findByUsername(username);
        return username + "@" + temporalProperties.getNotificationDomain();
    }
}
