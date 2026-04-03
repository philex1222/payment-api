package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for updating a payment's status")
public class PaymentStatusRequest {

    @NotBlank(message = "Status is required")
    @Pattern(
        regexp = "PENDING|PROCESSING|COMPLETED|FAILED|CANCELLED|REVERSED|REFUNDED",
        flags = Pattern.Flag.CASE_INSENSITIVE,
        message = "Status must be one of: PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED, REVERSED, REFUNDED"
    )
    @Schema(
        description = "Target payment status",
        allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED", "REVERSED", "REFUNDED"},
        example = "COMPLETED"
    )
    private String status;
}
