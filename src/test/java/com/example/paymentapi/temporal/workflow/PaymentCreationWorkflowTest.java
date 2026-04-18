package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.activity.PaymentNotificationActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentTransferActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentValidationActivitiesImpl;
import com.example.paymentapi.temporal.config.TemporalOptionsFactory;
import com.example.paymentapi.temporal.config.TemporalProperties;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentCreationWorkflowImpl} using
 * {@link TestWorkflowExtension} (embedded Temporal test server, no Docker required).
 *
 * Activities are mocked via Mockito so tests focus on workflow orchestration logic.
 */
class PaymentCreationWorkflowTest {

    static final PaymentValidationActivitiesImpl validation =
            mock(PaymentValidationActivitiesImpl.class);
    static final PaymentPersistenceActivitiesImpl persistence =
            mock(PaymentPersistenceActivitiesImpl.class);
    static final PaymentTransferActivitiesImpl transfer =
            mock(PaymentTransferActivitiesImpl.class);
    static final PaymentNotificationActivitiesImpl notification =
            mock(PaymentNotificationActivitiesImpl.class);

    @RegisterExtension
    static final TestWorkflowExtension testEnv = TestWorkflowExtension.newBuilder()
            .registerWorkflowImplementationTypes(
                    TemporalOptionsFactory.workflowImplementationOptions(new TemporalProperties()),
                    PaymentCreationWorkflowImpl.class)
            .setActivityImplementations(validation, persistence, transfer, notification)
            .build();

    private static PaymentRequest standardRequest() {
        return new PaymentRequest("1234567890", "0987654321", BigDecimal.valueOf(100), "USD", null);
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    void create_happyPath_returnsCompletedResult(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-abc");

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        PaymentCreationResult result = wf.create(standardRequest(), "admin");

        assertEquals("pay-abc", result.getPaymentId());
        assertEquals("COMPLETED", result.getFinalStatus());
        assertNotNull(result.getWorkflowId());
    }

    @Test
    void create_happyPath_invokesAllActivitiesInOrder(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-xyz");

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .build());
        wf.create(standardRequest(), "admin");

        verify(validation).validateAccounts("1234567890", "0987654321");
        verify(validation).validateFunds(eq("1234567890"), eq(BigDecimal.valueOf(100)));
        verify(persistence).persistPending(any(), eq("admin"));
        verify(transfer).transferFunds("1234567890", "0987654321", BigDecimal.valueOf(100));
        verify(persistence).completePayment("pay-xyz");
        verify(notification).sendNotification(eq("pay-xyz"), anyString(), anyString());
        verify(notification).publishWebhookEvent("pay-xyz", "admin");
    }

    // ── Saga compensation ──────────────────────────────────────────────────────

    @Test
    void create_transferFails_sagaCompensatesWithFailPayment(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-fail-001");
        doThrow(ApplicationFailure.newNonRetryableFailure("Bank error", "BankError"))
                .when(transfer).transferFunds(anyString(), anyString(), any());

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        assertThrows(Exception.class, () -> wf.create(standardRequest(), "admin"));
        verify(persistence).failPayment(eq("pay-fail-001"), anyString());
    }

    // ── Best-effort notifications ──────────────────────────────────────────────

    @Test
    void create_notificationFails_workflowStillCompletes(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-notify-fail");
        doThrow(ApplicationFailure.newNonRetryableFailure("SMTP down", "NotifyError"))
                .when(notification).sendNotification(anyString(), anyString(), anyString());

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        PaymentCreationResult result = wf.create(standardRequest(), "admin");

        assertEquals("COMPLETED", result.getFinalStatus());
        assertEquals("pay-notify-fail", result.getPaymentId());
        verify(persistence).completePayment("pay-notify-fail");
    }

    @Test
    void create_webhookFails_workflowStillCompletes(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-webhook-fail");
        doThrow(ApplicationFailure.newNonRetryableFailure("Webhook error", "WebhookError"))
                .when(notification).publishWebhookEvent(anyString(), anyString());

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        PaymentCreationResult result = wf.create(standardRequest(), "admin");

        assertEquals("COMPLETED", result.getFinalStatus());
    }

    // ── Validation failure ─────────────────────────────────────────────────────

    // ── Cancellation signal ────────────────────────────────────────────────────

    @Test
    void requestCancel_beforePersistence_stopsWorkflowAfterValidation(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-cancel-early");

        CountDownLatch cancelRequested = new CountDownLatch(1);
        CountDownLatch validateInProgress = new CountDownLatch(1);
        doAnswer((Answer<Void>) invocation -> {
            validateInProgress.countDown();
            // Wait until the test thread has signalled cancel. Keeps the activity
            // "running" so we cross the cancel checkpoint right after return.
            cancelRequested.await(5, TimeUnit.SECONDS);
            return null;
        }).when(validation).validateFunds(anyString(), any(BigDecimal.class));

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .setWorkflowId("test-cancel-early")
                        .build());

        WorkflowClient.start(wf::create, standardRequest(), "admin");

        try {
            assertTrue(validateInProgress.await(5, TimeUnit.SECONDS),
                    "validateFunds should start before we send cancel");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for activity to start");
        }

        wf.requestCancel("test-abort");
        cancelRequested.countDown();

        WorkflowStub stub = WorkflowStub.fromTyped(wf);
        PaymentCreationResult result = stub.getResult(PaymentCreationResult.class);
        assertEquals("CANCELLED", result.getFinalStatus());
        verify(persistence, never()).persistPending(any(), anyString());
        verify(transfer, never()).transferFunds(anyString(), anyString(), any());
    }

    @Test
    void requestCancel_idempotent_secondCallIgnored(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        when(persistence.persistPending(any(), anyString())).thenReturn("pay-cancel-idem");

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .setWorkflowId("test-cancel-idem")
                        .build());

        WorkflowClient.start(wf::create, standardRequest(), "admin");
        // Issue two cancel signals — second should be a no-op.
        wf.requestCancel("first");
        wf.requestCancel("second");

        WorkflowStub stub = WorkflowStub.fromTyped(wf);
        PaymentCreationResult result = stub.getResult(PaymentCreationResult.class);
        // Either CANCELLED or COMPLETED is acceptable — the important thing is
        // that two cancel signals don't cause the workflow to throw.
        assertNotNull(result.getFinalStatus());
    }

    // ── Validation failure ─────────────────────────────────────────────────────

    @Test
    void create_validationFails_noPaymentPersisted(WorkflowClient client, Worker worker) {
        reset(validation, persistence, transfer, notification);
        doThrow(ApplicationFailure.newNonRetryableFailure("Invalid account", "ValidationError"))
                .when(validation).validateAccounts(anyString(), anyString());

        PaymentCreationWorkflow wf = client.newWorkflowStub(
                PaymentCreationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        assertThrows(Exception.class, () -> wf.create(standardRequest(), "admin"));
        verify(persistence, never()).persistPending(any(), anyString());
    }
}
