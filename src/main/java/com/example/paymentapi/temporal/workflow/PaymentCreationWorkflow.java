package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.workflow.QueryMethod;
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
}
