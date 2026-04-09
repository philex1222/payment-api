package com.example.paymentapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "webhook_subscriptions")
@EntityListeners(AuditingEntityListener.class)
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "target_url", nullable = false, length = 512)
    private String targetUrl;

    @Column(name = "bearer_token", nullable = false, length = 512)
    private String bearerToken;

    /** Comma-separated WebhookEventType names, e.g. "PAYMENT_CREATED,PAYMENT_FAILED" */
    @Column(name = "event_types", nullable = false, length = 512)
    private String eventTypes;

    @Column(name = "admin_scope", nullable = false)
    private boolean adminScope = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookSubscription other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "WebhookSubscription{id='" + id + "', userId=" + userId + ", active=" + active + '}';
    }
}
