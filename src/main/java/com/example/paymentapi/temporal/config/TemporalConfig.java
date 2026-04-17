package com.example.paymentapi.temporal.config;

import com.example.paymentapi.temporal.activity.PaymentNotificationActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentTransferActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentValidationActivitiesImpl;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflowImpl;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties props,
                                                     ObjectProvider<MeterRegistry> meterRegistry) {
        WorkflowServiceStubsOptions.Builder stubs = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(props.getHost());

        if (props.getMetrics().isEnabled()) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                Scope scope = new RootScopeBuilder()
                        .reporter(new MicrometerClientStatsReporter(registry))
                        .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
                stubs.setMetricsScope(scope);
            }
        }

        return WorkflowServiceStubs.newConnectedServiceStubs(stubs.build(), Duration.ofSeconds(30));
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
        WorkerOptions workerOptions = TemporalOptionsFactory.workerOptions(props);
        WorkflowImplementationOptions implOptions = TemporalOptionsFactory.workflowImplementationOptions(props);

        Worker worker = workerFactory.newWorker(props.getTaskQueue(), workerOptions);
        worker.registerWorkflowImplementationTypes(implOptions, PaymentCreationWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                validationActivities, persistenceActivities,
                transferActivities, notificationActivities);
        workerFactory.start();
        return worker;
    }
}
