package com.example.paymentapi.temporal.config;

import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static io.grpc.Status.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemporalHealthIndicatorTest {

    @Mock
    private WorkflowServiceStubs stubs;

    @Mock
    private WorkflowServiceBlockingStub blockingStub;

    private TemporalProperties props;
    private TemporalHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        props = new TemporalProperties();
        props.setHost("temporal.example:7233");
        props.setNamespace("test-namespace");
        props.setTaskQueue("test-queue");
        indicator = new TemporalHealthIndicator(stubs, props);
    }

    @Test
    void health_returnsUpWhenGetSystemInfoSucceeds() {
        when(stubs.blockingStub()).thenReturn(blockingStub);
        when(blockingStub.getSystemInfo(any())).thenReturn(
                GetSystemInfoResponse.newBuilder().setServerVersion("1.24.0").build());

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("test-namespace", health.getDetails().get("namespace"));
        assertEquals("test-queue", health.getDetails().get("taskQueue"));
        assertEquals("1.24.0", health.getDetails().get("serverVersion"));
    }

    @Test
    void health_returnsDownWhenServerUnreachable() {
        when(stubs.blockingStub()).thenReturn(blockingStub);
        when(blockingStub.getSystemInfo(any())).thenThrow(
                new StatusRuntimeException(UNAVAILABLE.withDescription("server down")));

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("test-namespace", health.getDetails().get("namespace"));
        assertEquals("temporal.example:7233", health.getDetails().get("host"));
    }
}
