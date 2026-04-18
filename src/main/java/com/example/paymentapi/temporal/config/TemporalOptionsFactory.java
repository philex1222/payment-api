package com.example.paymentapi.temporal.config;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure helper that translates {@link TemporalProperties} into Temporal SDK option objects.
 * Shared by the production {@link TemporalConfig} and the test {@code TemporalTestConfig}
 * so both paths use an identical activity/retry/worker tuning surface.
 */
public final class TemporalOptionsFactory {

    private TemporalOptionsFactory() {}

    /**
     * Activity type names mapped to per-activity {@link ActivityOptions}. Activity type = the
     * Java method name with a capitalised first letter (default Temporal behaviour).
     */
    public static Map<String, ActivityOptions> activityOptionsByType(TemporalProperties props) {
        Map<String, ActivityOptions> map = new HashMap<>();
        ActivityOptions validation = toOptions(props.getValidation(), null);
        ActivityOptions persistence = toOptions(props.getPersistence(), null);
        ActivityOptions transfer = toOptions(props.getTransfer(), props.getTransferHeartbeatTimeout());
        ActivityOptions notification = toOptions(props.getNotification(), null);

        // PaymentValidationActivities
        map.put("ValidateAccounts", validation);
        map.put("ValidateFunds", validation);

        // PaymentPersistenceActivities
        map.put("PersistPending", persistence);
        map.put("CompletePayment", persistence);
        map.put("FailPayment", persistence);

        // PaymentTransferActivities
        map.put("TransferFunds", transfer);

        // PaymentNotificationActivities
        map.put("SendNotification", notification);
        map.put("PublishWebhookEvent", notification);

        return map;
    }

    /**
     * Catch-all default used by Temporal when an activity type has no explicit entry in
     * {@link #activityOptionsByType(TemporalProperties)}. Mirrors the notification tier
     * (short, bounded retry) since unknown activities are almost certainly best-effort.
     */
    public static ActivityOptions defaultActivityOptions(TemporalProperties props) {
        return toOptions(props.getNotification(), null);
    }

    public static WorkflowImplementationOptions workflowImplementationOptions(TemporalProperties props) {
        return WorkflowImplementationOptions.newBuilder()
                .setActivityOptions(activityOptionsByType(props))
                .setDefaultActivityOptions(defaultActivityOptions(props))
                .build();
    }

    public static WorkerOptions workerOptions(TemporalProperties props) {
        TemporalProperties.Worker w = props.getWorker();
        return WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(w.getMaxConcurrentActivityExecutions())
                .setMaxConcurrentWorkflowTaskExecutionSize(w.getMaxConcurrentWorkflowTaskExecutions())
                .setWorkflowPollThreadCount(w.getWorkflowPollThreadCount())
                .setActivityPollThreadCount(w.getActivityPollThreadCount())
                .build();
    }

    private static ActivityOptions toOptions(TemporalProperties.Activity activity,
                                             java.time.Duration heartbeatTimeout) {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(activity.getStartToCloseTimeout())
                .setRetryOptions(toRetryOptions(activity.getRetry()));
        if (heartbeatTimeout != null) {
            builder.setHeartbeatTimeout(heartbeatTimeout);
        }
        return builder.build();
    }

    private static RetryOptions toRetryOptions(TemporalProperties.Retry retry) {
        RetryOptions.Builder builder = RetryOptions.newBuilder()
                .setMaximumAttempts(retry.getMaximumAttempts())
                .setInitialInterval(retry.getInitialInterval())
                .setBackoffCoefficient(retry.getBackoffCoefficient())
                .setMaximumInterval(retry.getMaximumInterval());
        if (retry.getDoNotRetry() != null && !retry.getDoNotRetry().isEmpty()) {
            builder.setDoNotRetry(retry.getDoNotRetry().toArray(new String[0]));
        }
        return builder.build();
    }
}
