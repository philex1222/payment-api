package com.example.paymentapi.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing an immutable audit log entry.
 * Uses @Getter/@Setter to avoid @Data's broken equals/hashCode on JPA entities.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(nullable = false, length = 500)
    private String event;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditLog other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AuditLog{id=" + id + ", paymentId='" + paymentId + "', event='" + event + "'}";
    }
}
