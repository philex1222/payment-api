package com.example.paymentapi.service.command;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.metrics.PaymentMetrics;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.service.shared.PaymentStateMachine;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancellationHandlerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private PaymentMetrics paymentMetrics;
    @Mock private PaymentStateMachine stateMachine;
    @Mock private PaymentEventPublisher eventPublisher;
    @Mock private PaymentSecurityHelper security;
    @Mock private PaymentMapper mapper;

    private CancellationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CancellationHandler(paymentRepository, auditService, paymentMetrics,
                stateMachine, eventPublisher, security, mapper);
    }

    @Test
    void handle_cancelsPendingPayment() {
        Payment payment = new Payment();
        payment.setId("p-1");
        payment.setStatus("PENDING");
        payment.setCreatedBy("alice");
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);
        PaymentResponse response = new PaymentResponse();
        response.setId("p-1");
        when(mapper.toResponse(payment)).thenReturn(response);

        PaymentResponse result = handler.handle("p-1");

        assertSame(response, result);
        assertEquals("CANCELLED", payment.getStatus());
        verify(security).checkOwnership(payment);
        verify(stateMachine).assertCanTransitionTo("p-1", PaymentStatus.PENDING, PaymentStatus.CANCELLED);
        verify(paymentMetrics).incrementCancelled();
        verify(auditService).logPaymentEvent("p-1", "PAYMENT_CANCELLED");
        verify(eventPublisher).publish(WebhookEventType.PAYMENT_CANCELLED, "alice", response);
    }

    @Test
    void handle_throwsWhenNotFound() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(PaymentNotFoundException.class, () -> handler.handle("missing"));
        verify(paymentMetrics, never()).incrementCancelled();
    }

    @Test
    void handle_propagatesStateMachineFailure() {
        Payment payment = new Payment();
        payment.setId("p-1");
        payment.setStatus("COMPLETED");
        when(paymentRepository.findById("p-1")).thenReturn(Optional.of(payment));
        doThrow(new RuntimeException("cannot"))
                .when(stateMachine).assertCanTransitionTo(any(), any(), any());

        assertThrows(RuntimeException.class, () -> handler.handle("p-1"));
        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
