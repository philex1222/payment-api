package com.example.paymentapi.dto;

import com.example.paymentapi.model.Transaction;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Read-only DTO for exposing a Transaction record via the REST API.
 * Sensitive fields (e.g. raw failure reasons) are mapped to safe strings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A banking transaction associated with a payment")
public class TransactionResponse {

    @Schema(description = "Transaction UUID")
    private String id;

    @Schema(description = "ID of the payment this transaction belongs to")
    private String paymentId;

    @Schema(description = "Transaction status (PENDING, SUCCESS, FAILED)")
    private String status;

    @Schema(description = "Failure reason if the transaction failed")
    private String failureReason;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Timestamp when the transaction was created")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Timestamp when the transaction was last updated")
    private LocalDateTime updatedAt;

    /** Maps a Transaction entity to a TransactionResponse DTO. */
    public static TransactionResponse from(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .paymentId(t.getPaymentId())
                .status(t.getStatus())
                .failureReason(t.getFailureReason())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
