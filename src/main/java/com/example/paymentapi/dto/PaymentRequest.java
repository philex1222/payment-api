package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for creating a new payment")
public class PaymentRequest {

    @NotBlank(message = "Source account is required")
    @Pattern(regexp = "\\d{10}", message = "Account number must be exactly 10 digits")
    @Schema(description = "10-digit numeric source account number", example = "1234567890")
    private String sourceAccount;

    @NotBlank(message = "Destination account is required")
    @Pattern(regexp = "\\d{10}", message = "Account number must be exactly 10 digits")
    @Schema(description = "10-digit numeric destination account number", example = "0987654321")
    private String destinationAccount;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @DecimalMax(value = "1000000.00", message = "Amount cannot exceed 1,000,000")
    @Digits(integer = 15, fraction = 4, message = "Amount must have at most 15 integer digits and 4 decimal places")
    @Schema(description = "Payment amount (positive, up to 4 decimal places, max 1,000,000)", example = "100.00")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO 4217 code (uppercase)")
    @Schema(description = "ISO 4217 currency code", example = "USD")
    private String currency;
}
