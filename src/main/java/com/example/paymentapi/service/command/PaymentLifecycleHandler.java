package com.example.paymentapi.service.command;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.metrics.PaymentMetrics;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.BankingAPIService;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.config.ResilienceConfig;
import com.example.paymentapi.util.PaymentConstants;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.service.shared.PaymentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PaymentLifecycleHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentLifecycleHandler.class);

    @Value("${scheduler.retry.max-attempts:3}")
    private int maxRetryAttempts;

    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final BankingAPIService bankingAPIService;
    private final NotificationService notificationService;
    private final PaymentMetrics paymentMetrics;
    private final PaymentStateMachine stateMachine;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentSecurityHelper security;
    private final PaymentMapper mapper;

    public PaymentLifecycleHandler(PaymentRepository paymentRepository,
                                   TransactionService transactionService,
                                   AuditService auditService,
                                   BankingAPIService bankingAPIService,
                                   NotificationService notificationService,
                                   PaymentMetrics paymentMetrics,
                                   PaymentStateMachine stateMachine,
                                   PaymentEventPublisher eventPublisher,
                                   PaymentSecurityHelper security,
                                   PaymentMapper mapper) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.bankingAPIService = bankingAPIService;
        this.notificationService = notificationService;
        this.paymentMetrics = paymentMetrics;
        this.stateMachine = stateMachine;
        this.eventPublisher = eventPublisher;
        this.security = security;
        this.mapper = mapper;
    }

    /** Admin-only: generic status update. */
    @Caching(evict = {
        @CacheEvict(value = ResilienceConfig.CACHE_PAYMENT_DETAIL, key = "#id"),
        @CacheEvict(value = ResilienceConfig.CACHE_USER_PAYMENT_LIST, allEntries = true)
    })
    public PaymentResponse updateStatus(String id, String status) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(PaymentConstants.ERR_PAYMENT_NOT_FOUND + id));
        security.checkOwnership(payment);
        PaymentStatus current = PaymentStatus.fromString(payment.getStatus());
        PaymentStatus target = PaymentStatus.fromString(status);
        stateMachine.transition(id, current, target);
        String previous = payment.getStatus();
        payment.setStatus(target.getCode());
        Payment updated = paymentRepository.save(payment);
        auditService.logPaymentEvent(id,
                String.format("PAYMENT_STATUS_UPDATED:%s->%s", previous, target.getCode()));
        PaymentResponse response = mapper.toResponse(updated);
        eventPublisher.publish(eventPublisher.resolveEventType(target), updated.getCreatedBy(), response);
        return response;
    }

    /** Delete a non-completed payment. */
    @Caching(evict = {
        @CacheEvict(value = ResilienceConfig.CACHE_PAYMENT_DETAIL, key = "#id"),
        @CacheEvict(value = ResilienceConfig.CACHE_USER_PAYMENT_LIST, allEntries = true)
    })
    public void delete(String id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(PaymentConstants.ERR_PAYMENT_NOT_FOUND + id));
        security.checkOwnership(payment);
        if (PaymentStatus.fromString(payment.getStatus()) == PaymentStatus.COMPLETED)
            throw new IllegalStateException(PaymentConstants.ERR_DELETE_COMPLETED);
        paymentRepository.delete(payment);
        auditService.logPaymentEvent(id, PaymentConstants.AUDIT_PAYMENT_DELETED);
    }

    /** Retry a FAILED payment. */
    @Caching(evict = {
        @CacheEvict(value = ResilienceConfig.CACHE_PAYMENT_DETAIL, key = "#id"),
        @CacheEvict(value = ResilienceConfig.CACHE_USER_PAYMENT_LIST, allEntries = true)
    })
    public PaymentResponse retry(String id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(PaymentConstants.ERR_PAYMENT_NOT_FOUND + id));
        security.checkOwnership(payment);
        PaymentStatus current = PaymentStatus.fromString(payment.getStatus());
        stateMachine.assertCanTransitionTo(id, current, PaymentStatus.PENDING);
        if (payment.getRetryCount() >= maxRetryAttempts)
            throw new IllegalStateException(PaymentConstants.ERR_MAX_RETRIES + maxRetryAttempts);

        int attempt = payment.getRetryCount() + 1;
        paymentMetrics.incrementRetried();
        auditService.logPaymentEvent(id, PaymentConstants.AUDIT_PAYMENT_RETRY + attempt);
        payment.setStatus(PaymentStatus.PENDING.getCode());
        paymentRepository.save(payment);

        try {
            if (!bankingAPIService.validateAccount(payment.getSourceAccount())
                    || !bankingAPIService.validateAccount(payment.getDestinationAccount()))
                throw new InvalidAccountException("Account validation failed during retry");
            if (!bankingAPIService.hasSufficientFunds(payment.getSourceAccount(), payment.getAmount()))
                throw new InsufficientFundsException("Insufficient funds during retry");

            Transaction transaction = transactionService.createTransaction(payment.getId());
            payment.setStatus(PaymentStatus.PROCESSING.getCode());
            paymentRepository.save(payment);
            bankingAPIService.transferFunds(payment.getSourceAccount(),
                    payment.getDestinationAccount(), payment.getAmount());
            payment.setStatus(PaymentStatus.COMPLETED.getCode());
            paymentRepository.save(payment);
            transactionService.updateTransactionStatus(transaction.getId(), "SUCCESS");
            paymentMetrics.incrementRetriedSuccess();
            auditService.logPaymentEvent(id, PaymentConstants.AUDIT_RETRY_SUCCEEDED);
            notificationService.sendPaymentNotification("user@example.com",
                    "Retried payment completed. Amount: " + payment.getAmount() + " " + payment.getCurrency());
        } catch (Exception e) {
            payment.setStatus(PaymentStatus.FAILED.getCode());
            payment.setRetryCount(attempt);
            paymentRepository.save(payment);
            auditService.logPaymentEvent(id, "PAYMENT_RETRY_FAILED:" + e.getMessage());
        }

        Payment refreshed = paymentRepository.findById(id).orElseThrow();
        PaymentResponse response = mapper.toResponse(refreshed);
        eventPublisher.publish(
                eventPublisher.resolveEventType(PaymentStatus.fromString(response.getStatus())),
                refreshed.getCreatedBy(), response);
        return response;
    }
}
