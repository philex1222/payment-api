package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.util.PaymentConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentPersistenceActivitiesImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private PaymentPersistenceActivitiesImpl activities;

    // ── persistPending ─────────────────────────────────────────────────────────

    @Test
    void persistPending_savesPaymentAndReturnsId() {
        PaymentRequest req = new PaymentRequest(
                "src", "dst", BigDecimal.valueOf(100), "USD", "test");

        Payment savedPayment = new Payment();
        savedPayment.setId("pay-uuid-001");
        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);

        String result = activities.persistPending(req, "admin");

        assertEquals("pay-uuid-001", result);
    }

    @Test
    void persistPending_setsStatusToPending() {
        PaymentRequest req = new PaymentRequest(
                "src", "dst", BigDecimal.valueOf(50), "EUR", null);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        Payment saved = new Payment();
        saved.setId("pay-id");
        when(paymentRepository.save(captor.capture())).thenReturn(saved);

        activities.persistPending(req, "user");

        assertEquals(PaymentStatus.PENDING.getCode(), captor.getValue().getStatus());
        assertEquals("src",  captor.getValue().getSourceAccount());
        assertEquals("dst",  captor.getValue().getDestinationAccount());
        assertEquals(BigDecimal.valueOf(50), captor.getValue().getAmount());
        assertEquals("EUR",  captor.getValue().getCurrency());
        assertEquals("user", captor.getValue().getCreatedBy());
    }

    @Test
    void persistPending_createsTransactionAndAuditLog() {
        PaymentRequest req = new PaymentRequest("s", "d", BigDecimal.ONE, "USD", null);
        Payment saved = new Payment();
        saved.setId("pay-123");
        when(paymentRepository.save(any())).thenReturn(saved);

        activities.persistPending(req, "admin");

        verify(transactionService).createTransaction("pay-123");
        verify(auditService).logPaymentEvent("pay-123", PaymentConstants.AUDIT_PAYMENT_CREATED);
    }

    // ── completePayment ────────────────────────────────────────────────────────

    @Test
    void completePayment_setsStatusToCompleted() {
        Payment payment = new Payment();
        payment.setId("pay-456");
        payment.setStatus(PaymentStatus.PENDING.getCode());
        when(paymentRepository.findById("pay-456")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);

        activities.completePayment("pay-456");

        assertEquals(PaymentStatus.COMPLETED.getCode(), payment.getStatus());
        verify(auditService).logPaymentEvent("pay-456", PaymentConstants.AUDIT_PAYMENT_COMPLETED);
    }

    @Test
    void completePayment_throwsWhenPaymentNotFound() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> activities.completePayment("missing"));
    }

    // ── failPayment ────────────────────────────────────────────────────────────

    @Test
    void failPayment_setsStatusToFailed() {
        Payment payment = new Payment();
        payment.setId("pay-789");
        payment.setStatus(PaymentStatus.PENDING.getCode());
        when(paymentRepository.findById("pay-789")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);

        activities.failPayment("pay-789", "Transfer failed");

        assertEquals(PaymentStatus.FAILED.getCode(), payment.getStatus());
        verify(auditService).logPaymentEvent("pay-789", PaymentConstants.AUDIT_PAYMENT_FAILED);
    }

    @Test
    void failPayment_throwsWhenPaymentNotFound() {
        when(paymentRepository.findById("gone")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> activities.failPayment("gone", "reason"));
    }
}
