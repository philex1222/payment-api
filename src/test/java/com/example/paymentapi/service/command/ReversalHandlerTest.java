package com.example.paymentapi.service.command;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.exception.PaymentReversalException;
import com.example.paymentapi.metrics.PaymentMetrics;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.BankingAPIService;
import com.example.paymentapi.service.NotificationService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReversalHandlerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BankingAPIService bankingAPIService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private PaymentMetrics paymentMetrics;
    @Mock private PaymentStateMachine stateMachine;
    @Mock private PaymentEventPublisher eventPublisher;
    @Mock private PaymentSecurityHelper security;
    @Mock private PaymentMapper mapper;

    private ReversalHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReversalHandler(paymentRepository, bankingAPIService, auditService,
                notificationService, paymentMetrics, stateMachine, eventPublisher, security, mapper);
    }

    private Payment completedPayment() {
        Payment p = new Payment();
        p.setId("p-1");
        p.setSourceAccount("src");
        p.setDestinationAccount("dst");
        p.setAmount(new BigDecimal("100.00"));
        p.setCurrency("USD");
        p.setStatus("COMPLETED");
        p.setCreatedBy("alice");
        return p;
    }

    @Test
    void handle_fullReversal_marksReversed() {
        Payment payment = completedPayment();
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        PaymentResponse response = new PaymentResponse();
        when(mapper.toResponse(payment)).thenReturn(response);
        ReversalRequest req = new ReversalRequest();
        req.setReason("customer-request");

        handler.handle("p-1", req);

        assertEquals("REVERSED", payment.getStatus());
        verify(bankingAPIService).transferFunds("dst", "src", new BigDecimal("100.00"));
        verify(paymentMetrics).incrementReversed();
        verify(paymentMetrics, never()).incrementRefunded();
        verify(eventPublisher).publish(eq(WebhookEventType.PAYMENT_REVERSED), eq("alice"), same(response));
    }

    @Test
    void handle_partialReversal_marksRefunded() {
        Payment payment = completedPayment();
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        when(mapper.toResponse(payment)).thenReturn(new PaymentResponse());
        ReversalRequest req = new ReversalRequest();
        req.setPartialReversal(true);
        req.setReversalAmount(new BigDecimal("40.00"));
        req.setReason("partial");

        handler.handle("p-1", req);

        assertEquals("REFUNDED", payment.getStatus());
        verify(paymentMetrics).incrementRefunded();
        verify(paymentMetrics, never()).incrementReversed();
        verify(eventPublisher).publish(eq(WebhookEventType.PAYMENT_REFUNDED), anyString(), any());
    }

    @Test
    void handle_partialReversal_withZeroAmount_throwsReversalException() {
        Payment payment = completedPayment();
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        ReversalRequest req = new ReversalRequest();
        req.setPartialReversal(true);
        req.setReversalAmount(BigDecimal.ZERO);

        assertThrows(PaymentReversalException.class, () -> handler.handle("p-1", req));
    }

    @Test
    void handle_partialReversal_exceedingAmount_throwsReversalException() {
        Payment payment = completedPayment();
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        ReversalRequest req = new ReversalRequest();
        req.setPartialReversal(true);
        req.setReversalAmount(new BigDecimal("200.00"));

        assertThrows(PaymentReversalException.class, () -> handler.handle("p-1", req));
    }

    @Test
    void handle_bankFailure_wrapsInReversalException() {
        Payment payment = completedPayment();
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        doThrow(new RuntimeException("bank down"))
                .when(bankingAPIService).transferFunds(any(), any(), any());
        ReversalRequest req = new ReversalRequest();
        req.setReason("r");

        PaymentReversalException ex = assertThrows(PaymentReversalException.class,
                () -> handler.handle("p-1", req));
        assertTrue(ex.getMessage().contains("bank down"));
        verify(auditService).logPaymentEvent(eq("p-1"), contains("PAYMENT_REVERSAL_FAILED"));
    }

    @Test
    void handle_paymentNotFound_throws() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(PaymentNotFoundException.class,
                () -> handler.handle("missing", new ReversalRequest()));
    }
}
