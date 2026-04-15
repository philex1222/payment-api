package com.example.paymentapi.service.shared;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment payment) {
        PaymentStatus status = PaymentStatus.fromString(payment.getStatus());
        return PaymentResponse.builder()
                .id(payment.getId())
                .sourceAccount(maskAccount(payment.getSourceAccount()))
                .destinationAccount(maskAccount(payment.getDestinationAccount()))
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .description(payment.getDescription())
                .status(payment.getStatus())
                .statusDescription(status.getDescription())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    public String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) return "****";
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }
}
