package com.example.paymentapi.service.command;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.InvalidStatusTransitionException;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.metrics.PaymentMetrics;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.BankingAPIService;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.service.shared.PaymentStateMachine;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentLifecycleHandlerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private TransactionService transactionService;
    @Mock private AuditService auditService;
    @Mock private BankingAPIService bankingAPIService;
    @Mock private NotificationService notificationService;
    @Mock private PaymentMetrics paymentMetrics;
    @Mock private PaymentStateMachine stateMachine;
    @Mock private PaymentEventPublisher eventPublisher;
    @Mock private PaymentSecurityHelper security;
    @Mock private PaymentMapper mapper;

    private PaymentLifecycleHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentLifecycleHandler(paymentRepository, transactionService, auditService,
                bankingAPIService, notificationService, paymentMetrics, stateMachine,
                eventPublisher, security, mapper);
        ReflectionTestUtils.setField(handler, "maxRetryAttempts", 3);
    }

    private Payment samplePayment(String status) {
        Payment p = new Payment();
        p.setId("pay-1");
        p.setSourceAccount("1234567890");
        p.setDestinationAccount("9876543210");
        p.setAmount(new BigDecimal("100.00"));
        p.setCurrency("USD");
        p.setStatus(status);
        p.setCreatedBy("alice");
        p.setRetryCount(0);
        return p;
    }

    // ── updateStatus ───────────────────────────────────────────────────────

    @Test
    void updateStatus_transitionsAndPublishesEvent() {
        Payment payment = samplePayment("PENDING");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        PaymentResponse response = new PaymentResponse();
        response.setId("pay-1");
        when(mapper.toResponse(payment)).thenReturn(response);
        when(eventPublisher.resolveEventType(PaymentStatus.COMPLETED))
                .thenReturn(WebhookEventType.PAYMENT_COMPLETED);

        PaymentResponse result = handler.updateStatus("pay-1", "COMPLETED");

        assertSame(response, result);
        verify(security).checkOwnership(payment);
        verify(stateMachine).transition("pay-1", PaymentStatus.PENDING, PaymentStatus.COMPLETED);
        verify(auditService).logPaymentEvent(eq("pay-1"), contains("PAYMENT_STATUS_UPDATED"));
        verify(eventPublisher).publish(
                WebhookEventType.PAYMENT_COMPLETED, "alice", response);
    }

    @Test
    void updateStatus_throwsWhenPaymentMissing() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(PaymentNotFoundException.class,
                () -> handler.updateStatus("missing", "COMPLETED"));
    }

    @Test
    void updateStatus_propagatesStateMachineException() {
        Payment payment = samplePayment("COMPLETED");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        doThrow(new InvalidStatusTransitionException("pay-1", PaymentStatus.COMPLETED, PaymentStatus.PENDING))
                .when(stateMachine).transition(any(), any(), any());

        assertThrows(InvalidStatusTransitionException.class,
                () -> handler.updateStatus("pay-1", "PENDING"));
    }

    // ── delete ─────────────────────────────────────────────────────────────

    @Test
    void delete_deletesNonCompletedPayment() {
        Payment payment = samplePayment("PENDING");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        handler.delete("pay-1");

        verify(security).checkOwnership(payment);
        verify(paymentRepository).delete(any(Payment.class));
        verify(auditService).logPaymentEvent("pay-1", "PAYMENT_DELETED");
    }

    @Test
    void delete_throwsWhenPaymentMissing() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(PaymentNotFoundException.class, () -> handler.delete("missing"));
    }

    @Test
    void delete_throwsIllegalStateForCompletedPayment() {
        Payment payment = samplePayment("COMPLETED");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> handler.delete("pay-1"));
        assertTrue(ex.getMessage().contains("completed"));
        verify(paymentRepository, never()).delete(any(Payment.class));
    }

    // ── retry ──────────────────────────────────────────────────────────────

    @Test
    void retry_throwsWhenPaymentMissing() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(PaymentNotFoundException.class, () -> handler.retry("missing"));
    }

    @Test
    void retry_throwsWhenMaxRetriesReached() {
        Payment payment = samplePayment("FAILED");
        payment.setRetryCount(3);
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> handler.retry("pay-1"));
        assertTrue(ex.getMessage().contains("3"));
    }

    @Test
    void retry_successPath_transfersAndMarksCompleted() {
        Payment payment = samplePayment("FAILED");
        when(paymentRepository.findById("pay-1"))
                .thenReturn(Optional.of(payment))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(bankingAPIService.validateAccount(anyString())).thenReturn(true);
        when(bankingAPIService.hasSufficientFunds(anyString(), any())).thenReturn(true);
        Transaction tx = new Transaction();
        tx.setId("tx-1");
        when(transactionService.createTransaction("pay-1")).thenReturn(tx);
        PaymentResponse response = new PaymentResponse();
        response.setId("pay-1");
        response.setStatus("COMPLETED");
        when(mapper.toResponse(any(Payment.class))).thenReturn(response);
        when(eventPublisher.resolveEventType(any())).thenReturn(WebhookEventType.PAYMENT_COMPLETED);

        PaymentResponse result = handler.retry("pay-1");

        assertEquals("pay-1", result.getId());
        verify(paymentMetrics).incrementRetried();
        verify(paymentMetrics).incrementRetriedSuccess();
        verify(transactionService).updateTransactionStatus("tx-1", "SUCCESS");
        verify(notificationService).sendPaymentNotification(eq("user@example.com"), contains("Retried payment completed"));
    }

    @Test
    void retry_invalidAccount_marksPaymentFailed() {
        Payment payment = samplePayment("FAILED");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(bankingAPIService.validateAccount(anyString())).thenReturn(false);
        PaymentResponse response = new PaymentResponse();
        response.setStatus("FAILED");
        when(mapper.toResponse(any(Payment.class))).thenReturn(response);
        when(eventPublisher.resolveEventType(any())).thenReturn(WebhookEventType.PAYMENT_FAILED);

        handler.retry("pay-1");

        verify(auditService).logPaymentEvent(eq("pay-1"), contains("PAYMENT_RETRY_FAILED"));
        verify(paymentMetrics, never()).incrementRetriedSuccess();
        assertEquals(1, payment.getRetryCount());
    }

    @Test
    void retry_insufficientFunds_marksPaymentFailed() {
        Payment payment = samplePayment("FAILED");
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(bankingAPIService.validateAccount(anyString())).thenReturn(true);
        when(bankingAPIService.hasSufficientFunds(anyString(), any())).thenReturn(false);
        PaymentResponse response = new PaymentResponse();
        response.setStatus("FAILED");
        when(mapper.toResponse(any(Payment.class))).thenReturn(response);
        when(eventPublisher.resolveEventType(any())).thenReturn(WebhookEventType.PAYMENT_FAILED);

        handler.retry("pay-1");

        verify(auditService).logPaymentEvent(eq("pay-1"), contains("PAYMENT_RETRY_FAILED"));
        verify(paymentMetrics, never()).incrementRetriedSuccess();
    }
}
