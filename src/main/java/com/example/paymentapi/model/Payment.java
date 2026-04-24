package com.example.paymentapi.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a payment.
 *
 * Intentionally does NOT use Lombok @Data, @EqualsAndHashCode, or @ToString
 * on JPA entities — @Data generates equals/hashCode using all fields which
 * breaks Hibernate proxy equality and can trigger lazy-loading in toString().
 * Equals/hashCode are implemented on the business key (id) only.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payments")
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "source_account", nullable = false)
    private String sourceAccount;

    @Column(name = "destination_account", nullable = false)
    private String destinationAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /**
     * Optional free-text description or payment reference supplied by the initiator
     * (e.g. invoice number, memo). Never contains PII — treat as an audit note.
     */
    @Column(name = "description", length = 255)
    private String description;

    /**
     * Username of the authenticated user who created this payment.
     * Used for ownership checks (BOLA prevention).
     * Nullable for backwards compatibility with rows created before this column was added.
     */
    @Column(name = "created_by", length = 50)
    private String createdBy;

    /**
     * Temporal workflow id that created this payment.
     * Nullable for rows created before Temporal orchestration or by legacy paths.
     */
    @Column(name = "temporal_workflow_id", length = 255, unique = true)
    private String temporalWorkflowId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Payment{id='" + id + "', status='" + status + "', currency='" + currency + "'}";
    }
}
