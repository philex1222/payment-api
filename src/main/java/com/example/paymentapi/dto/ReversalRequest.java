package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request DTO for initiating a payment reversal or partial refund.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for initiating a payment reversal or partial refund")
public class ReversalRequest {

    @NotBlank(message = "Reason for reversal is required")
    @Size(min = 10, max = 500, message = "Reason must be between 10 and 500 characters")
    @Schema(description = "Reason for the reversal", example = "Customer requested refund")
    private String reason;

    @Size(max = 100, message = "Reference ID must not exceed 100 characters")
    @Schema(description = "Optional external reference ID for tracking", example = "REF-2026-001")
    private String referenceId;

    @Schema(description = "Set to true to reverse only part of the payment amount", defaultValue = "false")
    private boolean partialReversal = false;

    @DecimalMin(value = "0.01", message = "Reversal amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Reversal amount must have at most 15 integer digits and 4 decimal places")
    @Schema(description = "Amount to reverse (required when partialReversal=true)", example = "50.00")
    private BigDecimal reversalAmount;

    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    @Schema(description = "Optional additional notes about the reversal")
    private String notes;
}
