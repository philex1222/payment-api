package com.example.paymentapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {
    @NotBlank
    private String sourceAccount;
    @NotBlank
    private String destinationAccount;
    @NotNull
    @Positive
    private BigDecimal amount;
    @NotBlank
    private String currency;
}