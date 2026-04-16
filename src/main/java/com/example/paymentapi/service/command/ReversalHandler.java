package com.example.paymentapi.service.command;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.exception.PaymentReversalException;
import com.example.paymentapi.metrics.PaymentMetrics;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.BankingAPIService;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.config.ResilienceConfig;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.service.shared.PaymentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Transactional
public class ReversalHandler {

    private static final Logger logger = LoggerFactory.getLogger(ReversalHandler.class);

    private final PaymentRepository paymentRepository;
    private final BankingAPIService bankingAPIService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final PaymentMetrics paymentMetrics;
    private final PaymentStateMachine stateMachine;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentSecurityHelper security;
    private final PaymentMapper mapper;

    public ReversalHandler(PaymentRepository paymentRepository,
                           BankingAPIService bankingAPIService,
                           AuditService auditService,
                           NotificationService notificationService,
                           PaymentMetrics paymentMetrics,
                           PaymentStateMachine stateMachine,
                           PaymentEventPublisher eventPublisher,
                           PaymentSecurityHelper security,
                           PaymentMapper mapper) {
        this.paymentRepository = paymentRepository;
        this.bankingAPIService = bankingAPIService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.paymentMetrics = paymentMetrics;
        this.stateMachine = stateMachine;
        this.eventPublisher = eventPublisher;
        this.security = security;
        this.mapper = mapper;
    }

    @Caching(evict = {
        @CacheEvict(value = ResilienceConfig.CACHE_PAYMENT_DETAIL, key = "#id"),
        @CacheEvict(value = ResilienceConfig.CACHE_USER_PAYMENT_LIST, allEntries = true)
    })
    public PaymentResponse handle(String id, ReversalRequest request) {
        logger.info("Initiating reversal for payment: {} reason: {}", id, request.getReason());
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found with ID: " + id));
        security.checkOwnership(payment);

        PaymentStatus current = PaymentStatus.fromString(payment.getStatus());
        stateMachine.assertCanTransitionTo(id, current, PaymentStatus.REVERSED);

        BigDecimal reversalAmount = request.isPartialReversal() && request.getReversalAmount() != null
                ? request.getReversalAmount() : payment.getAmount();

        if (request.isPartialReversal()) {
            if (reversalAmount.compareTo(BigDecimal.ZERO) <= 0)
                throw new PaymentReversalException(id, "Reversal amount must be positive");
            if (reversalAmount.compareTo(payment.getAmount()) > 0)
                throw new PaymentReversalException(id, "Reversal amount cannot exceed original payment amount");
        }

        try {
            bankingAPIService.transferFunds(payment.getDestinationAccount(),
                    payment.getSourceAccount(), reversalAmount);

            PaymentStatus newStatus = request.isPartialReversal()
                    ? PaymentStatus.REFUNDED : PaymentStatus.REVERSED;
            payment.setStatus(newStatus.getCode());
            Payment updated = paymentRepository.save(payment);

            if (newStatus == PaymentStatus.REVERSED) paymentMetrics.incrementReversed();
            else paymentMetrics.incrementRefunded();

            auditService.logPaymentEvent(id, String.format("PAYMENT_%s:amount=%s,reason=%s",
                    newStatus.getCode(), reversalAmount, request.getReason()));
            notificationService.sendPaymentNotification("user@example.com",
                    String.format("Reversal processed. Amount: %s %s. Reason: %s",
                            reversalAmount, payment.getCurrency(), request.getReason()));

            PaymentResponse response = mapper.toResponse(updated);
            WebhookEventType eventType = newStatus == PaymentStatus.REVERSED
                    ? WebhookEventType.PAYMENT_REVERSED : WebhookEventType.PAYMENT_REFUNDED;
            eventPublisher.publish(eventType, payment.getCreatedBy(), response);
            return response;

        } catch (PaymentReversalException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to reverse payment {}: {}", id, e.getMessage(), e);
            auditService.logPaymentEvent(id, "PAYMENT_REVERSAL_FAILED:" + e.getMessage());
            throw new PaymentReversalException(id, "Failed to process reversal: " + e.getMessage(), e);
        }
    }
}
