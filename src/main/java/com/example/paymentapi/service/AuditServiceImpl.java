package com.example.paymentapi.service;

import com.example.paymentapi.model.AuditLog;
import com.example.paymentapi.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Persists payment audit events.
 *
 * {@code REQUIRES_NEW} propagation ensures that audit writes run in their own
 * independent transaction, separate from the caller's payment transaction.
 * This means:
 * <ul>
 *   <li>A slow audit write does not hold the payment transaction open longer.</li>
 *   <li>An audit write failure does not roll back the payment operation.</li>
 *   <li>Audit records are committed even when the outer transaction rolls back
 *       (e.g. a payment that fails partway through still produces an audit trail).</li>
 * </ul>
 */
@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final AuditLogRepository auditLogRepository;

    public AuditServiceImpl(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logPaymentEvent(String paymentId, String event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setPaymentId(paymentId);
            auditLog.setEvent(event);
            auditLog.setTimestamp(LocalDateTime.now());
            auditLog.setPerformedBy(resolveActor());
            auditLogRepository.save(auditLog);
        } catch (Exception ex) {
            // Audit failure must never propagate and disrupt the payment operation.
            logger.error("Failed to persist audit event for payment {}: {}", paymentId, ex.getMessage(), ex);
        }
    }

    /**
     * Resolves the current actor from the SecurityContext.
     * Returns "system" when no security context is present (scheduled jobs).
     * Returns "anonymous" for unauthenticated requests that somehow reach
     * an audit point (should not happen in practice).
     */
    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        String name = auth.getName();
        return (name == null || name.equals("anonymousUser")) ? "anonymous" : name;
    }
}