package com.example.paymentapi.temporal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentWorkflowResponse(
        String workflowId,
        String status,
        String statusUrl   // null until persistPending completes; reserved for future status endpoint
) {}
