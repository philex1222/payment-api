package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.util.PaymentConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPersistenceActivitiesImpl implements PaymentPersistenceActivities {

    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;

    public PaymentPersistenceActivitiesImpl(PaymentRepository paymentRepository,
                                            TransactionService transactionService,
                                            AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public String persistPending(PaymentRequest request, String initiatedBy) {
        Payment payment = new Payment();
        payment.setSourceAccount(request.getSourceAccount());
        payment.setDestinationAccount(request.getDestinationAccount());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setStatus(PaymentStatus.PENDING.getCode());
        payment.setCreatedBy(initiatedBy);
        payment.setDescription(request.getDescription());
        Payment saved = paymentRepository.save(payment);
        transactionService.createTransaction(saved.getId());
        auditService.logPaymentEvent(saved.getId(), PaymentConstants.AUDIT_PAYMENT_CREATED);
        return saved.getId();
    }

    @Override
    @Transactional
    public void completePayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        payment.setStatus(PaymentStatus.COMPLETED.getCode());
        paymentRepository.save(payment);
        auditService.logPaymentEvent(paymentId, PaymentConstants.AUDIT_PAYMENT_COMPLETED);
    }

    @Override
    @Transactional
    public void failPayment(String paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        payment.setStatus(PaymentStatus.FAILED.getCode());
        paymentRepository.save(payment);
        auditService.logPaymentEvent(paymentId, PaymentConstants.AUDIT_PAYMENT_FAILED);
    }
}
