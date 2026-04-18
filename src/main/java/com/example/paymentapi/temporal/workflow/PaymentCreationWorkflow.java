package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PaymentCreationWorkflow {

    @WorkflowMethod
    PaymentCreationResult create(PaymentRequest request, String initiatedBy);

    @QueryMethod
    String getCurrentStatus();

    @QueryMethod
    String getPaymentId();

    /**
     * Graceful-cancel signal. The workflow finishes the activity it is currently running
     * (to avoid a partial transfer), then routes through saga compensation on the next
     * checkpoint. Prefer this over the hard {@code WorkflowStub.cancel()} for user-initiated
     * cancellations — it guarantees the payment is marked FAILED with reason
     * {@code "cancelled-by-user"} instead of leaving in-flight state behind.
     */
    @SignalMethod
    void requestCancel(String reason);
}
