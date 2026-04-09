package com.example.paymentapi.repository;

import com.example.paymentapi.model.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, String> {
    List<WebhookSubscription> findByUserId(Long userId);
    List<WebhookSubscription> findByUserIdAndActiveTrue(Long userId);
    List<WebhookSubscription> findByAdminScopeTrueAndActiveTrue();
}
