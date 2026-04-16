package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.activity.PaymentNotificationActivities;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivities;
import com.example.paymentapi.temporal.activity.PaymentTransferActivities;
import com.example.paymentapi.temporal.activity.PaymentValidationActivities;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class PaymentCreationWorkflowImpl implements PaymentCreationWorkflow {

    private String currentStatus = "PENDING";
    private String paymentId;

    private final PaymentValidationActivities validationActivities =
            Workflow.newActivityStub(PaymentValidationActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(10))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());

    private final PaymentPersistenceActivities persistenceActivities =
            Workflow.newActivityStub(PaymentPersistenceActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(15))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());

    private final PaymentTransferActivities transferActivities =
            Workflow.newActivityStub(PaymentTransferActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(5)
                                    .setInitialInterval(Duration.ofMillis(500))
                                    .setBackoffCoefficient(2.0)
                                    .build())
                            .build());

    private final PaymentNotificationActivities notificationActivities =
            Workflow.newActivityStub(PaymentNotificationActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(10))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());

    @Override
    public PaymentCreationResult create(PaymentRequest request, String initiatedBy) {
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        String workflowId = Workflow.getInfo().getWorkflowId();

        currentStatus = "VALIDATING";
        validationActivities.validateAccounts(request.getSourceAccount(), request.getDestinationAccount());
        validationActivities.validateFunds(request.getSourceAccount(), request.getAmount());

        currentStatus = "PERSISTING";
        paymentId = persistenceActivities.persistPending(request, initiatedBy);
        saga.addCompensation(() ->
                persistenceActivities.failPayment(paymentId, "Payment failed during processing"));

        currentStatus = "TRANSFERRING";
        try {
            transferActivities.transferFunds(
                    request.getSourceAccount(), request.getDestinationAccount(), request.getAmount());
        } catch (ActivityFailure e) {
            saga.compensate();
            throw e;
        }

        currentStatus = "COMPLETING";
        persistenceActivities.completePayment(paymentId);

        currentStatus = "NOTIFYING";
        try {
            notificationActivities.sendNotification(paymentId, "user@example.com",
                    "Payment completed. Amount: " + request.getAmount() + " " + request.getCurrency());
        } catch (ActivityFailure e) {
            Workflow.getLogger(PaymentCreationWorkflowImpl.class)
                    .warn("Notification failed for payment {}: {}", paymentId, e.getMessage());
        }
        try {
            notificationActivities.publishWebhookEvent(paymentId, initiatedBy);
        } catch (ActivityFailure e) {
            Workflow.getLogger(PaymentCreationWorkflowImpl.class)
                    .warn("Webhook event failed for payment {}: {}", paymentId, e.getMessage());
        }

        currentStatus = "COMPLETED";
        return new PaymentCreationResult(workflowId, paymentId, "COMPLETED");
    }

    @Override
    public String getCurrentStatus() {
        return currentStatus;
    }

    @Override
    public String getPaymentId() {
        return paymentId;
    }
}
