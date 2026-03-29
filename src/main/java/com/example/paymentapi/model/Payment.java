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
