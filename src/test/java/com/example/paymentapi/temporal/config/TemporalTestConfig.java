package com.example.paymentapi.temporal.config;

import com.example.paymentapi.temporal.activity.PaymentNotificationActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentTransferActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentValidationActivitiesImpl;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TemporalTestConfig {

    @Bean(destroyMethod = "close")
    public TestWorkflowEnvironment testWorkflowEnvironment(
            TemporalProperties props,
            PaymentValidationActivitiesImpl validationActivities,
            PaymentPersistenceActivitiesImpl persistenceActivities,
            PaymentTransferActivitiesImpl transferActivities,
            PaymentNotificationActivitiesImpl notificationActivities) {
        // Inject the production DataConverter so JavaTimeModule + WRITE_BIGDECIMAL_AS_PLAIN
        // are in effect during tests. Without this, LocalDateTime/BigDecimal round-trip
        // behaviour diverges between the in-memory test env and the real Temporal cluster.
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder()
                        .setWorkflowClientOptions(
                                WorkflowClientOptions.newBuilder()
                                        .setDataConverter(TemporalDataConverter.get())
                                        .build())
                        .build());
        Worker worker = env.newWorker(props.getTaskQueue());
        worker.registerWorkflowImplementationTypes(
                TemporalOptionsFactory.workflowImplementationOptions(props),
                PaymentCreationWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                validationActivities, persistenceActivities,
                transferActivities, notificationActivities);
        env.start();
        return env;
    }

    @Bean
    @Primary
    public WorkflowClient testWorkflowClient(TestWorkflowEnvironment env) {
        return env.getWorkflowClient();
    }
}
