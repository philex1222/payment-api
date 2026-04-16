package com.example.paymentapi.service;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.exception.UserNotFoundException;
import com.example.paymentapi.exception.WebhookSubscriptionNotFoundException;
import com.example.paymentapi.util.WebhookConstants;
import com.example.paymentapi.model.User;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class WebhookServiceImpl implements WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookServiceImpl.class);
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * When {@code true}, the SSRF guard permits loopback and private-network target URLs.
     * Should only be {@code true} in the test profile (for echo-endpoint BDD scenarios).
     */
    @Value("${webhook.ssrf.allow-localhost:false}")
    private boolean allowLocalhost;

    public WebhookServiceImpl(WebhookSubscriptionRepository subscriptionRepository,
                               WebhookDeliveryRepository deliveryRepository,
                               UserRepository userRepository,
                               AuditService auditService) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Override
    public WebhookSubscriptionResponse createSubscription(WebhookSubscriptionRequest request, String username) {
        User user = findUser(username);
        if (request.isAdminScope() && !user.getRole().equals(ROLE_ADMIN)) {
            throw new AccessDeniedException("adminScope requires ROLE_ADMIN");
        }
        validateEventTypes(request.getEventTypes());
        validateTargetUrl(request.getTargetUrl());

        WebhookSubscription sub = new WebhookSubscription();
        sub.setUserId(user.getId());
        sub.setTargetUrl(request.getTargetUrl());
        sub.setBearerToken(request.getBearerToken());
        sub.setEventTypes(String.join(",", request.getEventTypes()));
        sub.setAdminScope(request.isAdminScope());
        sub.setActive(request.isActive());

        WebhookSubscription saved = subscriptionRepository.save(sub);
        auditService.logPaymentEvent(saved.getId(),
                "WEBHOOK_CREATED:user=" + username + ",eventTypes=" + saved.getEventTypes()
                        + ",adminScope=" + saved.isAdminScope());
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
            if (!user.getRole().equals(ROLE_ADMIN)) {
                throw new AccessDeniedException("adminScope requires ROLE_ADMIN");
            }
        }
        validateEventTypes(request.getEventTypes());
        validateTargetUrl(request.getTargetUrl());
        sub.setTargetUrl(request.getTargetUrl());
        sub.setBearerToken(request.getBearerToken());
        sub.setEventTypes(String.join(",", request.getEventTypes()));
        sub.setAdminScope(request.isAdminScope());
        sub.setActive(request.isActive());
        WebhookSubscription updated = subscriptionRepository.save(sub);
        auditService.logPaymentEvent(id,
                "WEBHOOK_UPDATED:user=" + username + ",eventTypes=" + updated.getEventTypes()
                        + ",adminScope=" + updated.isAdminScope() + ",active=" + updated.isActive());
        return toResponse(updated);
    }

    @Override
    public void deleteSubscription(String id, String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        subscriptionRepository.delete(sub);
        auditService.logPaymentEvent(id, WebhookConstants.AUDIT_WEBHOOK_DELETED + ":user=" + username);
        logger.info("Webhook subscription {} deleted by {}", id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> getDeliveries(String subscriptionId) {
        findSubscription(subscriptionId); // ensures 404 if subscription does not exist
        return deliveryRepository.findBySubscriptionId(subscriptionId).stream()
                .map(this::toDeliveryResponse)
                .toList();
    }

    private WebhookSubscription findSubscription(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new WebhookSubscriptionNotFoundException(WebhookConstants.ERR_WEBHOOK_NOT_FOUND + id));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + username));
    }

    private void checkOwnership(WebhookSubscription sub, String username, boolean isAdmin) {
        if (isAdmin) return;
        User user = findUser(username);
        if (!sub.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("Access denied: webhook subscription belongs to another user");
        }
    }

    /**
     * Guards against SSRF: rejects non-HTTP(S) schemes and URLs that resolve to
     * loopback, link-local, or RFC-1918 private addresses.
     * Note: this check runs at registration/update time. The DNS-rebinding window
     * is eliminated at dispatch time by {@link com.example.paymentapi.config.SsrfSafeDnsResolver}.
     */
    private void validateTargetUrl(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("targetUrl is not a valid URL: " + rawUrl);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("targetUrl scheme must be http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("targetUrl must have a valid host");
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("targetUrl host could not be resolved: " + host);
        }

        if (!allowLocalhost && (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress())) {
            throw new IllegalArgumentException(
                "targetUrl must not point to a private or internal network address");
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
                .status(d.getStatus() != null ? d.getStatus().name() : null)
                .attemptCount(d.getAttemptCount())
                .lastAttemptAt(d.getLastAttemptAt())
                .nextRetryAt(d.getNextRetryAt())
                .responseStatus(d.getResponseStatus())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
