package com.example.paymentapi.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a banking transaction linked to a payment.
 * Uses @Getter/@Setter instead of @Data to avoid broken equals/hashCode
 * on managed entities and accidental lazy-load in toString().
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "transactions")
@EntityListeners(AuditingEntityListener.class)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Transaction{id='" + id + "', paymentId='" + paymentId + "', status='" + status
                + "', createdAt=" + createdAt + '}';
    }
}
