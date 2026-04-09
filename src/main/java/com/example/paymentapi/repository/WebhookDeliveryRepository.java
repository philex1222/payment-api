package com.example.paymentapi.repository;

import com.example.paymentapi.model.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    @Query("SELECT d FROM WebhookDelivery d " +
           "WHERE d.status = 'PENDING' " +
           "AND d.nextRetryAt <= :now " +
           "AND d.attemptCount < :maxAttempts " +
           "ORDER BY d.nextRetryAt ASC")
    List<WebhookDelivery> findPendingDeliveries(
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts);

    List<WebhookDelivery> findBySubscriptionId(String subscriptionId);
}
