package com.example.paymentapi.temporal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCreationResult {
    private String workflowId;
    private String paymentId;
    private String finalStatus;
}
