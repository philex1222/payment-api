package com.example.paymentapi.temporal.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import org.slf4j.MDC;

/**
 * Copies workflow/activity identifiers from the Temporal {@link ActivityInfo} into SLF4J MDC for
 * the duration of an activity invocation. Entries are scoped via {@link AutoCloseable} so they
 * are always cleared, even on exception.
 */
final class ActivityMdcSupport {

    static final String MDC_WORKFLOW_ID = "workflowId";
    static final String MDC_RUN_ID = "runId";
    static final String MDC_ACTIVITY_ID = "activityId";
    static final String MDC_ACTIVITY_TYPE = "activityType";
    static final String MDC_ATTEMPT = "attempt";

    private ActivityMdcSupport() {}

    static Scope open() {
        ActivityInfo info;
        try {
            info = Activity.getExecutionContext().getInfo();
        } catch (Exception e) {
            // Called outside a Temporal worker (e.g. plain unit test) — skip MDC.
            return new Scope();
        }
        Scope scope = new Scope();
        scope.put(MDC_WORKFLOW_ID, info.getWorkflowId());
        scope.put(MDC_RUN_ID, info.getRunId());
        scope.put(MDC_ACTIVITY_ID, info.getActivityId());
        scope.put(MDC_ACTIVITY_TYPE, info.getActivityType());
        scope.put(MDC_ATTEMPT, Integer.toString(info.getAttempt()));
        return scope;
    }

    static final class Scope implements AutoCloseable {
        private final java.util.List<String> keys = new java.util.ArrayList<>(5);

        void put(String key, String value) {
            if (value == null) return;
            MDC.put(key, value);
            keys.add(key);
        }

        @Override
        public void close() {
            for (String key : keys) {
                MDC.remove(key);
            }
        }
    }
}
