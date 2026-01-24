package com.example.paymentapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * Request DTO for initiating a payment reversal.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReversalRequest {

    @NotBlank(message = "Reason for reversal is required")
    @Size(min = 10, max = 500, message = "Reason must be between 10 and 500 characters")
    private String reason;

    @Size(max = 100, message = "Reference ID must not exceed 100 characters")
    private String referenceId;

    private boolean partialReversal = false;

    private java.math.BigDecimal reversalAmount;

    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    private String notes;
}
