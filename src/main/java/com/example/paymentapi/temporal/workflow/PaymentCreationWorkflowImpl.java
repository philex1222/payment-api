package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.activity.PaymentNotificationActivities;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivities;
import com.example.paymentapi.temporal.activity.PaymentTransferActivities;
import com.example.paymentapi.temporal.activity.PaymentValidationActivities;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

public class PaymentCreationWorkflowImpl implements PaymentCreationWorkflow {

    private String currentStatus = "PENDING";
    private String paymentId;

    // ActivityOptions are provided by the worker via WorkflowImplementationOptions
    // (see TemporalOptionsFactory). Keeping the stub definitions lean here makes the
    // workflow pure orchestration and keeps tuning knobs in TemporalProperties.
    private final PaymentValidationActivities validationActivities =
            Workflow.newActivityStub(PaymentValidationActivities.class);
    private final PaymentPersistenceActivities persistenceActivities =
            Workflow.newActivityStub(PaymentPersistenceActivities.class);
    private final PaymentTransferActivities transferActivities =
            Workflow.newActivityStub(PaymentTransferActivities.class);
    private final PaymentNotificationActivities notificationActivities =
            Workflow.newActivityStub(PaymentNotificationActivities.class);

    @Override
    public PaymentCreationResult create(PaymentRequest request, String initiatedBy) {
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        String workflowId = Workflow.getInfo().getWorkflowId();

        currentStatus = "VALIDATING";
        validationActivities.validateAccounts(request.getSourceAccount(), request.getDestinationAccount());
        validationActivities.validateFunds(request.getSourceAccount(), request.getAmount());

        currentStatus = "PERSISTING";
        paymentId = persistenceActivities.persistPending(request, initiatedBy);
        // Placeholder compensation — updated with the real cause the instant we know it.
        final String[] failureReason = {"Payment workflow failed"};
        saga.addCompensation(() ->
                persistenceActivities.failPayment(paymentId, failureReason[0]));

        currentStatus = "TRANSFERRING";
        try {
            transferActivities.transferFunds(
                    request.getSourceAccount(), request.getDestinationAccount(), request.getAmount());
        } catch (ActivityFailure e) {
            failureReason[0] = describeFailure(e);
            saga.compensate();
            throw e;
        }

        currentStatus = "COMPLETING";
        persistenceActivities.completePayment(paymentId);

        currentStatus = "NOTIFYING";
        try {
            notificationActivities.sendNotification(paymentId, initiatedBy,
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

    /**
     * Extracts the original failure message from a Temporal {@link ActivityFailure} chain so the
     * saga compensation step can record a meaningful reason instead of a generic placeholder.
     */
    private static String describeFailure(ActivityFailure e) {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationFailure af) {
            String type = af.getType();
            String message = af.getOriginalMessage();
            if (message != null && !message.isBlank()) {
                return (type != null && !type.isBlank()) ? type + ": " + message : message;
            }
        }
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return e.getMessage();
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
