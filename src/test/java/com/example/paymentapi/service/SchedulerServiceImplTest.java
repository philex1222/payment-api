package com.example.paymentapi.service;

import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.AuditLogRepository;
import com.example.paymentapi.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private SchedulerServiceImpl schedulerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(schedulerService, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(schedulerService, "auditLogRetentionDays", 90);
        ReflectionTestUtils.setField(schedulerService, "paymentRetentionDays", 30);
    }

    @Nested
    class RetryFailedPaymentsTests {

        @Test
        void whenNoEligiblePayments_doesNotCallRetry() {
            when(paymentRepository.findByStatusAndRetryCountLessThan(
                    eq(PaymentStatus.FAILED.getCode()), eq(3)))
                    .thenReturn(Collections.emptyList());

            schedulerService.retryFailedPayments();

            verify(paymentService, never()).retryPayment(anyString());
        }

        @Test
        void whenEligiblePaymentsExist_retriesEachOne() {
            Payment p1 = failedPayment("id-1");
            Payment p2 = failedPayment("id-2");

            when(paymentRepository.findByStatusAndRetryCountLessThan(
                    eq(PaymentStatus.FAILED.getCode()), eq(3)))
                    .thenReturn(List.of(p1, p2));

            schedulerService.retryFailedPayments();

            verify(paymentService).retryPayment("id-1");
            verify(paymentService).retryPayment("id-2");
        }

        @Test
        void whenRetryThrows_continuesWithRemainingPayments() {
            Payment p1 = failedPayment("id-1");
            Payment p2 = failedPayment("id-2");

            when(paymentRepository.findByStatusAndRetryCountLessThan(anyString(), anyInt()))
                    .thenReturn(List.of(p1, p2));
            doThrow(new RuntimeException("bank down")).when(paymentService).retryPayment("id-1");

            schedulerService.retryFailedPayments();

            // id-1 failed but id-2 must still be attempted
            verify(paymentService).retryPayment("id-1");
            verify(paymentService).retryPayment("id-2");
        }
    }

    @Nested
    class CleanupOldRecordsTests {

        @Test
        void cleanupDeletesOldAuditLogsAndCancelledPayments() {
            when(auditLogRepository.deleteAuditLogsOlderThan(any())).thenReturn(5);
            when(paymentRepository.deleteTerminalPaymentsOlderThan(any(), any())).thenReturn(3);

            schedulerService.cleanupOldRecords();

            verify(auditLogRepository).deleteAuditLogsOlderThan(any());
            verify(paymentRepository).deleteTerminalPaymentsOlderThan(any(), any());
        }

        @Test
        void cleanupWithNoRecordsToDelete_completesWithoutError() {
            when(auditLogRepository.deleteAuditLogsOlderThan(any())).thenReturn(0);
            when(paymentRepository.deleteTerminalPaymentsOlderThan(any(), any())).thenReturn(0);

            schedulerService.cleanupOldRecords();

            verify(auditLogRepository).deleteAuditLogsOlderThan(any());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Payment failedPayment(String id) {
        Payment p = new Payment();
        p.setId(id);
        p.setStatus(PaymentStatus.FAILED.getCode());
        p.setAmount(BigDecimal.valueOf(100));
        p.setCurrency("USD");
        p.setSourceAccount("1234567890");
        p.setDestinationAccount("0987654321");
        p.setRetryCount(0);
        return p;
    }
}
