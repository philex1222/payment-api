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

    static final String STATUS_CANCELLING = "CANCELLING";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String FAILURE_CANCELLED_BY_USER = "cancelled-by-user";

    private String currentStatus = "PENDING";
    private String paymentId;
    private boolean cancelRequested;
    private String cancelReason;

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
        if (abortIfCancelled(saga)) {
            return cancelledResult(workflowId);
        }

        currentStatus = "PERSISTING";
        paymentId = persistenceActivities.persistPending(request, initiatedBy);
        // Placeholder compensation — updated with the real cause the instant we know it.
        final String[] failureReason = {"Payment workflow failed"};
        saga.addCompensation(() ->
                persistenceActivities.failPayment(paymentId, failureReason[0]));
        if (abortIfCancelled(saga)) {
            failureReason[0] = FAILURE_CANCELLED_BY_USER + ": " + safeCancelReason();
            saga.compensate();
            return cancelledResult(workflowId);
        }

        currentStatus = "TRANSFERRING";
        try {
            transferActivities.transferFunds(
                    request.getSourceAccount(), request.getDestinationAccount(), request.getAmount());
        } catch (ActivityFailure e) {
            failureReason[0] = describeFailure(e);
            saga.compensate();
            throw e;
        }
        if (abortIfCancelled(saga)) {
            failureReason[0] = FAILURE_CANCELLED_BY_USER + ": " + safeCancelReason();
            saga.compensate();
            return cancelledResult(workflowId);
        }

        currentStatus = "COMPLETING";
        // If completePayment fails after funds have already moved, run saga compensation
        // (failPayment) so the payment row does not stay PENDING with money out the door.
        // Without this, a DB crash mid-complete leaves a funds-moved-but-PENDING record.
        try {
            persistenceActivities.completePayment(paymentId);
        } catch (ActivityFailure e) {
            failureReason[0] = describeFailure(e);
            saga.compensate();
            throw e;
        }

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

    @Override
    public void requestCancel(String reason) {
        if (!cancelRequested) {
            cancelRequested = true;
            cancelReason = reason;
            Workflow.getLogger(PaymentCreationWorkflowImpl.class)
                    .info("Cancellation requested for workflow {}: {}",
                            Workflow.getInfo().getWorkflowId(), reason);
        }
    }

    /** @return {@code true} if the workflow should stop after compensation. */
    private boolean abortIfCancelled(Saga saga) {
        if (!cancelRequested) return false;
        currentStatus = STATUS_CANCELLING;
        Workflow.getLogger(PaymentCreationWorkflowImpl.class)
                .info("Honoring cancellation signal for workflow {}: {}",
                        Workflow.getInfo().getWorkflowId(), safeCancelReason());
        return true;
    }

    private PaymentCreationResult cancelledResult(String workflowId) {
        currentStatus = STATUS_CANCELLED;
        return new PaymentCreationResult(workflowId, paymentId, STATUS_CANCELLED);
    }

    private String safeCancelReason() {
        return (cancelReason == null || cancelReason.isBlank()) ? "user-initiated" : cancelReason;
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
