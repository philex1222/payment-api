package com.example.paymentapi.event;

import com.example.paymentapi.dto.WebhookDeliveryPayload;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class WebhookEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebhookEventListener.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventListener(WebhookSubscriptionRepository subscriptionRepository,
                                 WebhookDeliveryRepository deliveryRepository,
                                 UserRepository userRepository,
                                 ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePaymentEvent(PaymentEvent event) {
        logger.debug("Handling PaymentEvent: type={}, owner={}", event.getEventType(), event.getPaymentOwner());

        String payloadJson;
        try {
            WebhookDeliveryPayload payload = WebhookDeliveryPayload.builder()
                    .eventType(event.getEventType().name())
                    .paymentId(event.getPaymentSnapshot().getId())
                    .timestamp(LocalDateTime.now())
                    .payment(event.getPaymentSnapshot())
                    .build();
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize webhook payload for event {}: {}", event.getEventType(), e.getMessage());
            return;
        }

        List<WebhookSubscription> matches = findMatchingSubscriptions(event);
        if (matches.isEmpty()) {
            logger.debug("No active subscriptions matched for event {}", event.getEventType());
            return;
        }

        String paymentId = event.getPaymentSnapshot().getId();
        String eventTypeName = event.getEventType().name();

        for (WebhookSubscription sub : matches) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setSubscriptionId(sub.getId());
            delivery.setPaymentId(paymentId);
            delivery.setEventType(eventTypeName);
            delivery.setPayload(payloadJson);
            delivery.setStatus("PENDING");
            delivery.setAttemptCount(0);
            delivery.setNextRetryAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
            logger.debug("Queued delivery for subscription {} on payment {}", sub.getId(), paymentId);
        }
    }

    private List<WebhookSubscription> findMatchingSubscriptions(PaymentEvent event) {
        List<WebhookSubscription> result = new ArrayList<>();

        // Admin-scoped: fire for all payments regardless of owner
        for (WebhookSubscription sub : subscriptionRepository.findByAdminScopeTrueAndActiveTrue()) {
            if (subscribedTo(sub, event.getEventType())) {
                result.add(sub);
            }
        }

        // User-scoped: fire only for the payment owner's own subscriptions
        if (event.getPaymentOwner() != null) {
            userRepository.findByUsername(event.getPaymentOwner()).ifPresent(user -> {
                for (WebhookSubscription sub : subscriptionRepository.findByUserIdAndActiveTrue(user.getId())) {
                    if (!sub.isAdminScope() && subscribedTo(sub, event.getEventType())) {
                        result.add(sub);
                    }
                }
            });
        }

        return result;
    }

    /** Returns true if the subscription includes the given event type, or the PAYMENT_STATUS_CHANGED catch-all. */
    private boolean subscribedTo(WebhookSubscription sub, WebhookEventType eventType) {
        for (String t : sub.getEventTypes().split(",")) {
            String trimmed = t.trim();
            if (trimmed.equals(eventType.name()) || trimmed.equals(WebhookEventType.PAYMENT_STATUS_CHANGED.name())) {
                return true;
            }
        }
        return false;
    }
}
