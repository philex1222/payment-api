package com.example.paymentapi.service;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.model.User;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class WebhookServiceImpl implements WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookServiceImpl.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;

    public WebhookServiceImpl(WebhookSubscriptionRepository subscriptionRepository,
                               WebhookDeliveryRepository deliveryRepository,
                               UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
    }

    @Override
    public WebhookSubscriptionResponse createSubscription(WebhookSubscriptionRequest request, String username) {
        User user = findUser(username);
        if (request.isAdminScope() && !user.getRole().equals("ROLE_ADMIN")) {
            throw new AccessDeniedException("adminScope requires ROLE_ADMIN");
        }
        validateEventTypes(request.getEventTypes());

        WebhookSubscription sub = new WebhookSubscription();
        sub.setUserId(user.getId());
        sub.setTargetUrl(request.getTargetUrl());
        sub.setBearerToken(request.getBearerToken());
        sub.setEventTypes(String.join(",", request.getEventTypes()));
        sub.setAdminScope(request.isAdminScope());
        sub.setActive(request.isActive());

        WebhookSubscription saved = subscriptionRepository.save(sub);
        logger.info("Webhook subscription {} created for user {}", saved.getId(), username);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookSubscriptionResponse> listSubscriptions(String username, boolean isAdmin) {
        if (isAdmin) {
            return subscriptionRepository.findAll().stream().map(this::toResponse).toList();
        }
        User user = findUser(username);
        return subscriptionRepository.findByUserId(user.getId()).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscriptionResponse getSubscription(String id, String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        return toResponse(sub);
    }

    @Override
    public WebhookSubscriptionResponse updateSubscription(String id, WebhookSubscriptionRequest request,
                                                           String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        if (request.isAdminScope()) {
            User user = findUser(username);
            if (!user.getRole().equals("ROLE_ADMIN")) {
                throw new AccessDeniedException("adminScope requires ROLE_ADMIN");
            }
        }
        validateEventTypes(request.getEventTypes());
        sub.setTargetUrl(request.getTargetUrl());
        sub.setBearerToken(request.getBearerToken());
        sub.setEventTypes(String.join(",", request.getEventTypes()));
        sub.setAdminScope(request.isAdminScope());
        sub.setActive(request.isActive());
        return toResponse(subscriptionRepository.save(sub));
    }

    @Override
    public void deleteSubscription(String id, String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        subscriptionRepository.delete(sub);
        logger.info("Webhook subscription {} deleted by {}", id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> getDeliveries(String subscriptionId) {
        return deliveryRepository.findBySubscriptionId(subscriptionId).stream()
                .map(this::toDeliveryResponse)
                .toList();
    }

    private WebhookSubscription findSubscription(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Webhook subscription not found: " + id));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    private void checkOwnership(WebhookSubscription sub, String username, boolean isAdmin) {
        if (isAdmin) return;
        User user = findUser(username);
        if (!sub.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("Access denied: webhook subscription belongs to another user");
        }
    }

    private void validateEventTypes(List<String> eventTypes) {
        List<String> invalid = eventTypes.stream()
                .filter(t -> !WebhookEventType.isValid(t))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid event types: " + invalid + ". Valid values: " +
                Arrays.toString(WebhookEventType.values()));
        }
    }

    private WebhookSubscriptionResponse toResponse(WebhookSubscription sub) {
        return WebhookSubscriptionResponse.builder()
                .id(sub.getId())
                .userId(sub.getUserId())
                .targetUrl(sub.getTargetUrl())
                .bearerToken("***")
                .eventTypes(List.of(sub.getEventTypes().split(",")))
                .adminScope(sub.isAdminScope())
                .active(sub.isActive())
                .createdAt(sub.getCreatedAt())
                .build();
    }

    private WebhookDeliveryResponse toDeliveryResponse(WebhookDelivery d) {
        return WebhookDeliveryResponse.builder()
                .id(d.getId())
                .subscriptionId(d.getSubscriptionId())
                .paymentId(d.getPaymentId())
                .eventType(d.getEventType())
                .status(d.getStatus())
                .attemptCount(d.getAttemptCount())
                .lastAttemptAt(d.getLastAttemptAt())
                .nextRetryAt(d.getNextRetryAt())
                .responseStatus(d.getResponseStatus())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
