package com.example.paymentapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for payment operations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {
    private String id;

    private String sourceAccount;

    private String destinationAccount;

    private BigDecimal amount;

    private String currency;

    private String status;

    private String statusDescription;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    private String transactionId;

    private String message;

    /**
     * Creates a simple response with just id, status, and createdAt.
     */
    public static PaymentResponse simple(String id, String status, LocalDateTime createdAt) {
        return PaymentResponse.builder()
                .id(id)
                .status(status)
                .createdAt(createdAt)
                .build();
    }
}
