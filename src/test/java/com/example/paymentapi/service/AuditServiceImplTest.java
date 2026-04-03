package com.example.paymentapi.service;

import com.example.paymentapi.model.AuditLog;
import com.example.paymentapi.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditServiceImpl Tests")
class AuditServiceImplTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditServiceImpl auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditServiceImpl(auditLogRepository);
    }

    @Test
    @DisplayName("Should persist an audit log entry with correct fields")
    void logPaymentEvent_persistsRecord() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        auditService.logPaymentEvent("payment-001", "PAYMENT_CREATED");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals("payment-001", saved.getPaymentId());
        assertEquals("PAYMENT_CREATED", saved.getEvent());
        assertNotNull(saved.getTimestamp());
        assertTrue(saved.getTimestamp().isBefore(LocalDateTime.now().plusSeconds(1)));
        // No security context in unit tests → actor falls back to "system"
        assertEquals("system", saved.getPerformedBy());
    }

    @Test
    @DisplayName("Should record 'system' as actor when no SecurityContext is present")
    void logPaymentEvent_noSecurityContext_usesSystemActor() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        auditService.logPaymentEvent("payment-005", "PAYMENT_CREATED");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals("system", captor.getValue().getPerformedBy());
    }

    @Test
    @DisplayName("Should persist a PAYMENT_COMPLETED event")
    void logPaymentEvent_completedEvent() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        auditService.logPaymentEvent("payment-002", "PAYMENT_COMPLETED");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        assertEquals("PAYMENT_COMPLETED", captor.getValue().getEvent());
    }

    @Test
    @DisplayName("Should persist arbitrary event strings (e.g. status update details)")
    void logPaymentEvent_statusUpdateEvent() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        String detailedEvent = "PAYMENT_STATUS_UPDATED:PENDING->PROCESSING";
        auditService.logPaymentEvent("payment-003", detailedEvent);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertEquals(detailedEvent, captor.getValue().getEvent());
    }

    @Test
    @DisplayName("Should swallow exceptions from the repository and not propagate them")
    void logPaymentEvent_repositoryThrows_doesNotPropagate() {
        // A repository failure (e.g. DB temporarily unavailable) must never
        // propagate out of logPaymentEvent and disrupt the calling payment transaction.
        when(auditLogRepository.save(any(AuditLog.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertThatCode(() -> auditService.logPaymentEvent("payment-004", "PAYMENT_CREATED"))
                .doesNotThrowAnyException();

        verify(auditLogRepository).save(any(AuditLog.class));
    }
}
