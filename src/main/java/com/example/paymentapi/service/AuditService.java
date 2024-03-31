package com.example.paymentapi.service;

import com.example.paymentapi.model.AuditLog;

public interface AuditService {
    void logPaymentEvent(String paymentId, String event);
}