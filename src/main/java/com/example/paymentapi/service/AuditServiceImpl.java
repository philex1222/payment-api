package com.example.paymentapi.service;

import com.example.paymentapi.model.AuditLog;
import com.example.paymentapi.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditServiceImpl implements AuditService {
    private final AuditLogRepository auditLogRepository;

    public AuditServiceImpl(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public void logPaymentEvent(String paymentId, String event) {
        AuditLog auditLog = new AuditLog();
        auditLog.setPaymentId(paymentId);
        auditLog.setEvent(event);
        auditLog.setTimestamp(LocalDateTime.now());
        auditLogRepository.save(auditLog);
    }
}