package com.example.paymentapi.temporal.config;

import com.example.paymentapi.temporal.activity.PaymentNotificationActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentTransferActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentValidationActivitiesImpl;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties props) {
        return WorkflowServiceStubs.newConnectedServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(props.getHost())
                        .build(),
                Duration.ofSeconds(30));
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties props) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(props.getNamespace())
                        .build());
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean
    public Worker paymentWorker(
            WorkerFactory workerFactory,
            TemporalProperties props,
            PaymentValidationActivitiesImpl validationActivities,
            PaymentPersistenceActivitiesImpl persistenceActivities,
            PaymentTransferActivitiesImpl transferActivities,
            PaymentNotificationActivitiesImpl notificationActivities) {
        Worker worker = workerFactory.newWorker(props.getTaskQueue());
        worker.registerWorkflowImplementationTypes(PaymentCreationWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                validationActivities, persistenceActivities,
                transferActivities, notificationActivities);
        workerFactory.start();
        return worker;
    }
}
