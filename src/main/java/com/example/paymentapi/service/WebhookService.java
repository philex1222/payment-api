package com.example.paymentapi.service;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;

import java.util.List;

public interface WebhookService {
    WebhookSubscriptionResponse createSubscription(WebhookSubscriptionRequest request, String username);
    List<WebhookSubscriptionResponse> listSubscriptions(String username, boolean isAdmin);
    WebhookSubscriptionResponse getSubscription(String id, String username, boolean isAdmin);
    WebhookSubscriptionResponse updateSubscription(String id, WebhookSubscriptionRequest request, String username, boolean isAdmin);
    void deleteSubscription(String id, String username, boolean isAdmin);
    List<WebhookDeliveryResponse> getDeliveries(String subscriptionId);
}
