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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CancellationHandler {

    private static final Logger logger = LoggerFactory.getLogger(CancellationHandler.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final PaymentMetrics paymentMetrics;
    private final PaymentStateMachine stateMachine;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentSecurityHelper security;
    private final PaymentMapper mapper;

    public CancellationHandler(PaymentRepository paymentRepository,
                               AuditService auditService,
                               PaymentMetrics paymentMetrics,
                               PaymentStateMachine stateMachine,
                               PaymentEventPublisher eventPublisher,
                               PaymentSecurityHelper security,
                               PaymentMapper mapper) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.paymentMetrics = paymentMetrics;
        this.stateMachine = stateMachine;
        this.eventPublisher = eventPublisher;
        this.security = security;
        this.mapper = mapper;
    }

    public PaymentResponse handle(String id) {
        logger.info("Cancelling payment: {}", id);
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found with ID: " + id));
        security.checkOwnership(payment);
        PaymentStatus current = PaymentStatus.fromString(payment.getStatus());
        stateMachine.assertCanTransitionTo(id, current, PaymentStatus.CANCELLED);
        payment.setStatus(PaymentStatus.CANCELLED.getCode());
        Payment updated = paymentRepository.save(payment);
        paymentMetrics.incrementCancelled();
        auditService.logPaymentEvent(id, "PAYMENT_CANCELLED");
        PaymentResponse response = mapper.toResponse(updated);
        eventPublisher.publish(WebhookEventType.PAYMENT_CANCELLED, payment.getCreatedBy(), response);
        return response;
    }
}
