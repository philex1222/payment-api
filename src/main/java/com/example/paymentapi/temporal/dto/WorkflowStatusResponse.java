package com.example.paymentapi.temporal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Snapshot of a running payment workflow. Returned by
 * {@code GET /api/v1/payments/workflows/{workflowId}/status}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowStatusResponse(
        String workflowId,
        String status,
        String paymentId
) {}
