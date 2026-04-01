package com.example.paymentapi.repository;

import com.example.paymentapi.model.Payment;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentSpecification {

    private PaymentSpecification() {}

    public static Specification<Payment> hasStatus(String status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status.toUpperCase());
    }

    public static Specification<Payment> createdAfter(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Payment> createdBefore(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    public static Specification<Payment> ownedBy(String username) {
        return (root, query, cb) -> cb.equal(root.get("createdBy"), username);
    }

    public static Specification<Payment> amountGreaterThanOrEqual(BigDecimal min) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), min);
    }

    public static Specification<Payment> amountLessThanOrEqual(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("amount"), max);
    }
}
