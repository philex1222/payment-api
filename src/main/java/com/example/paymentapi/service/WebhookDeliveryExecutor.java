package com.example.paymentapi.service;

import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookDeliveryStatus;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Executes a single webhook delivery in its own transaction.
 *
 * <p>Extracted from {@link WebhookDispatcherService} into a separate Spring-managed bean
 * so that the {@code @Transactional} annotation is honoured by the proxy. The dispatcher
 * calls this bean via the Spring proxy, which opens a transaction for each delivery row.</p>
 */
@Service
public class WebhookDeliveryExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDeliveryExecutor.class);
    static final int MAX_ATTEMPTS = 5;
    static final long BASE_BACKOFF_SECONDS = 30L;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final RestClient webhookRestClient;

    public WebhookDeliveryExecutor(WebhookDeliveryRepository deliveryRepository,
                                    WebhookSubscriptionRepository subscriptionRepository,
                                    RestClient webhookRestClient) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.webhookRestClient = webhookRestClient;
    }

    @Transactional
    public void dispatchSingle(WebhookDelivery delivery) {
        WebhookSubscription sub = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub == null) {
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            return;
        }

        int httpStatus = executePost(sub.getTargetUrl(), sub.getBearerToken(), delivery.getPayload());

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(LocalDateTime.now());
        delivery.setResponseStatus(httpStatus == 0 ? null : httpStatus);

        if (httpStatus >= 200 && httpStatus < 300) {
            delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
            logger.info("Webhook delivery {} DELIVERED to {}", delivery.getId(), sub.getTargetUrl());
        } else {
            if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
                delivery.setStatus(WebhookDeliveryStatus.FAILED);
                logger.warn("Webhook delivery {} permanently FAILED after {} attempts", delivery.getId(), MAX_ATTEMPTS);
            } else {
                long backoffSeconds = BASE_BACKOFF_SECONDS * (1L << delivery.getAttemptCount());
                delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
                logger.debug("Webhook delivery {} retrying in {}s (attempt {})",
                        delivery.getId(), backoffSeconds, delivery.getAttemptCount());
            }
        }

        deliveryRepository.save(delivery);
    }

    /** Sends a POST and returns the HTTP status code, or 0 on connection failure. */
    int executePost(String targetUrl, String bearerToken, String payloadJson) {
        try {
            byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
            ResponseEntity<Void> response = webhookRestClient.post()
                    .uri(targetUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode().value();
        } catch (Exception ex) {
            // Strip query string from URL before logging to avoid leaking embedded credentials
            String safeUrl = targetUrl.contains("?") ? targetUrl.substring(0, targetUrl.indexOf('?')) : targetUrl;
            logger.warn("Webhook POST to {} failed: {}", safeUrl, ex.getMessage());
            return 0;
        }
    }
}
