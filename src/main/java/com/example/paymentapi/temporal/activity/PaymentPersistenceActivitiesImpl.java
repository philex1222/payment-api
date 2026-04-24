package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.temporal.metrics.PaymentWorkflowMetrics;
import com.example.paymentapi.util.PaymentConstants;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPersistenceActivitiesImpl implements PaymentPersistenceActivities {

    private static final Logger logger = LoggerFactory.getLogger(PaymentPersistenceActivitiesImpl.class);

    static final String ERR_PAYMENT_NOT_FOUND = "PaymentNotFound";
    static final String ERR_PERSISTENCE = "PersistenceError";

    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final PaymentWorkflowMetrics workflowMetrics;

    public PaymentPersistenceActivitiesImpl(PaymentRepository paymentRepository,
                                            TransactionService transactionService,
                                            AuditService auditService,
                                            PaymentWorkflowMetrics workflowMetrics) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.workflowMetrics = workflowMetrics;
    }

    @Override
    @Transactional
    public String persistPending(PaymentRequest request, String initiatedBy) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            String workflowId = currentWorkflowId();
            if (hasText(workflowId)) {
                var existing = paymentRepository.findByTemporalWorkflowId(workflowId);
                if (existing.isPresent()) {
                    logger.info("Reusing existing payment {} for Temporal workflow {}",
                            existing.get().getId(), workflowId);
                    return existing.get().getId();
                }
            }

            Payment payment = new Payment();
            payment.setSourceAccount(request.getSourceAccount());
            payment.setDestinationAccount(request.getDestinationAccount());
            payment.setAmount(request.getAmount());
            payment.setCurrency(request.getCurrency());
            payment.setStatus(PaymentStatus.PENDING.getCode());
            payment.setCreatedBy(initiatedBy);
            payment.setDescription(request.getDescription());
            payment.setTemporalWorkflowId(workflowId);
            Payment saved = paymentRepository.save(payment);
            transactionService.createTransaction(saved.getId());
            auditService.logPaymentEvent(saved.getId(), PaymentConstants.AUDIT_PAYMENT_CREATED);
            return saved.getId();
        }
    }

    @Override
    @Transactional
    public void completePayment(String paymentId) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                            "Payment not found: " + paymentId, ERR_PAYMENT_NOT_FOUND));
            if (PaymentStatus.COMPLETED.getCode().equals(payment.getStatus())) {
                logger.info("Payment {} is already COMPLETED; treating completePayment as idempotent success",
                        paymentId);
                return;
            }
            payment.setStatus(PaymentStatus.COMPLETED.getCode());
            paymentRepository.save(payment);
            auditService.logPaymentEvent(paymentId, PaymentConstants.AUDIT_PAYMENT_COMPLETED);
            workflowMetrics.recordCompleted();
        }
    }

    @Override
    @Transactional
    public void failPayment(String paymentId, String reason) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                            "Payment not found: " + paymentId, ERR_PAYMENT_NOT_FOUND));
            if (PaymentStatus.FAILED.getCode().equals(payment.getStatus())) {
                logger.info("Payment {} is already FAILED; treating failPayment as idempotent success",
                        paymentId);
                return;
            }
            if (PaymentStatus.COMPLETED.getCode().equals(payment.getStatus())) {
                logger.warn("Payment {} is already COMPLETED; refusing to mark it FAILED. Reason: {}",
                        paymentId, reason);
                return;
            }
            payment.setStatus(PaymentStatus.FAILED.getCode());
            paymentRepository.save(payment);
            logger.warn("Marking payment {} as FAILED. Reason: {}", paymentId, reason);
            auditService.logPaymentEvent(paymentId, PaymentConstants.AUDIT_PAYMENT_FAILED);
            workflowMetrics.recordFailed(reason);
        }
    }

    String currentWorkflowId() {
        try {
            return Activity.getExecutionContext().getInfo().getWorkflowId();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
