package com.example.paymentapi.service.command;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.metrics.PaymentMetrics;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.BankingAPIService;
import com.example.paymentapi.service.CurrencyConversionService;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.service.shared.PaymentValidationService;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Transactional
public class CreatePaymentHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreatePaymentHandler.class);

    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final BankingAPIService bankingAPIService;
    private final CurrencyConversionService currencyConversionService;
    private final NotificationService notificationService;
    private final PaymentMetrics paymentMetrics;
    private final PaymentValidationService validationService;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentSecurityHelper security;
    private final PaymentMapper mapper;

    public CreatePaymentHandler(PaymentRepository paymentRepository,
                                TransactionService transactionService,
                                AuditService auditService,
                                BankingAPIService bankingAPIService,
                                CurrencyConversionService currencyConversionService,
                                NotificationService notificationService,
                                PaymentMetrics paymentMetrics,
                                PaymentValidationService validationService,
                                PaymentEventPublisher eventPublisher,
                                PaymentSecurityHelper security,
                                PaymentMapper mapper) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.bankingAPIService = bankingAPIService;
        this.currencyConversionService = currencyConversionService;
        this.notificationService = notificationService;
        this.paymentMetrics = paymentMetrics;
        this.validationService = validationService;
        this.eventPublisher = eventPublisher;
        this.security = security;
        this.mapper = mapper;
    }

    public PaymentResponse handle(PaymentRequest request)
            throws InsufficientFundsException, InvalidAccountException {
        logger.info("Creating payment from {} to {} for {} {}",
                mapper.maskAccount(request.getSourceAccount()),
                mapper.maskAccount(request.getDestinationAccount()),
                request.getAmount(), request.getCurrency());

        Timer.Sample timerSample = paymentMetrics.startTimer();
        paymentMetrics.incrementCreated();

        validationService.validateAccounts(request.getSourceAccount(), request.getDestinationAccount());
        validationService.validateSufficientFunds(request.getSourceAccount(), request.getAmount());

        // Currency conversion
        String destCurrency = "USD"; // placeholder — real impl would call banking API
        BigDecimal finalAmount = request.getAmount();
        if (!request.getCurrency().equals(destCurrency)) {
            finalAmount = currencyConversionService.convert(request.getCurrency(), destCurrency, request.getAmount());
            request.setAmount(finalAmount);
            request.setCurrency(destCurrency);
        }

        Payment payment = new Payment();
        payment.setSourceAccount(request.getSourceAccount());
        payment.setDestinationAccount(request.getDestinationAccount());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setStatus(PaymentStatus.PENDING.getCode());
        payment.setCreatedBy(security.currentUsername());
        payment.setDescription(request.getDescription());
        Payment saved = paymentRepository.save(payment);

        Transaction transaction = transactionService.createTransaction(saved.getId());
        auditService.logPaymentEvent(saved.getId(), "PAYMENT_CREATED");

        try {
            bankingAPIService.transferFunds(request.getSourceAccount(),
                    request.getDestinationAccount(), request.getAmount());
            saved.setStatus(PaymentStatus.COMPLETED.getCode());
            paymentRepository.save(saved);
            paymentMetrics.incrementCompleted();
            paymentMetrics.stopTimer(timerSample);
            transactionService.updateTransactionStatus(transaction.getId(), "SUCCESS");
            auditService.logPaymentEvent(saved.getId(), "PAYMENT_COMPLETED");
            notificationService.sendPaymentNotification("user@example.com",
                    "Payment completed. Amount: " + request.getAmount() + " " + request.getCurrency());
        } catch (Exception e) {
            logger.error("Payment {} failed: {}", saved.getId(), e.getMessage());
            saved.setStatus(PaymentStatus.FAILED.getCode());
            paymentRepository.save(saved);
            paymentMetrics.incrementFailed();
            paymentMetrics.stopTimer(timerSample);
            transactionService.updateTransactionStatus(transaction.getId(), "FAILED");
            transactionService.updateTransactionFailureReason(transaction.getId(), e.getMessage());
            auditService.logPaymentEvent(saved.getId(), "PAYMENT_FAILED");
            throw e;
        }

        PaymentResponse response = PaymentResponse.builder()
                .id(saved.getId())
                .sourceAccount(mapper.maskAccount(saved.getSourceAccount()))
                .destinationAccount(mapper.maskAccount(saved.getDestinationAccount()))
                .amount(saved.getAmount())
                .currency(saved.getCurrency())
                .description(saved.getDescription())
                .status(saved.getStatus())
                .statusDescription(PaymentStatus.fromString(saved.getStatus()).getDescription())
                .createdAt(saved.getCreatedAt())
                .transactionId(transaction.getId())
                .message("Payment processed successfully")
                .build();
        eventPublisher.publish(WebhookEventType.PAYMENT_CREATED, saved.getCreatedBy(), response);
        return response;
    }
}
