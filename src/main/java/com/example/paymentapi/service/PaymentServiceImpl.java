package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.exception.InvalidStatusTransitionException;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.exception.PaymentReversalException;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class PaymentServiceImpl implements PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final BankingAPIService bankingAPIService;
    private final CurrencyConversionService currencyConversionService;
    private final NotificationService notificationService;

    public PaymentServiceImpl(PaymentRepository paymentRepository, TransactionService transactionService,
                              AuditService auditService, BankingAPIService bankingAPIService,
                              CurrencyConversionService currencyConversionService, NotificationService notificationService) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.bankingAPIService = bankingAPIService;
        this.currencyConversionService = currencyConversionService;
        this.notificationService = notificationService;
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest paymentRequest) throws InsufficientFundsException, InvalidAccountException {
        logger.info("Creating payment from {} to {} for {} {}",
                maskAccount(paymentRequest.getSourceAccount()),
                maskAccount(paymentRequest.getDestinationAccount()),
                paymentRequest.getAmount(),
                paymentRequest.getCurrency());

        // Validate source and destination accounts
        if (!bankingAPIService.validateAccount(paymentRequest.getSourceAccount())) {
            logger.warn("Invalid source account: {}", maskAccount(paymentRequest.getSourceAccount()));
            throw new InvalidAccountException("Invalid source account");
        }
        if (!bankingAPIService.validateAccount(paymentRequest.getDestinationAccount())) {
            logger.warn("Invalid destination account: {}", maskAccount(paymentRequest.getDestinationAccount()));
            throw new InvalidAccountException("Invalid destination account");
        }

        // Prevent self-transfer
        if (paymentRequest.getSourceAccount().equals(paymentRequest.getDestinationAccount())) {
            logger.warn("Attempted self-transfer on account: {}", maskAccount(paymentRequest.getSourceAccount()));
            throw new InvalidAccountException("Source and destination accounts cannot be the same");
        }

        // Check sufficient funds
        if (!bankingAPIService.hasSufficientFunds(paymentRequest.getSourceAccount(), paymentRequest.getAmount())) {
            logger.warn("Insufficient funds in account: {}", maskAccount(paymentRequest.getSourceAccount()));
            throw new InsufficientFundsException("Insufficient funds in the source account");
        }

        // Convert currency if necessary
        String sourceCurrency = paymentRequest.getCurrency();
        String destinationCurrency = getAccountCurrency(paymentRequest.getDestinationAccount());
        BigDecimal finalAmount = paymentRequest.getAmount();
        if (!sourceCurrency.equals(destinationCurrency)) {
            logger.debug("Converting {} {} to {}", paymentRequest.getAmount(), sourceCurrency, destinationCurrency);
            finalAmount = currencyConversionService.convert(sourceCurrency, destinationCurrency, paymentRequest.getAmount());
            paymentRequest.setAmount(finalAmount);
            paymentRequest.setCurrency(destinationCurrency);
        }

        // Create a payment
        Payment payment = new Payment();
        payment.setSourceAccount(paymentRequest.getSourceAccount());
        payment.setDestinationAccount(paymentRequest.getDestinationAccount());
        payment.setAmount(paymentRequest.getAmount());
        payment.setCurrency(paymentRequest.getCurrency());
        payment.setStatus(PaymentStatus.PENDING.getCode());
        Payment createdPayment = paymentRepository.save(payment);
        logger.info("Payment created with ID: {}", createdPayment.getId());

        // Create a transaction
        Transaction transaction = transactionService.createTransaction(createdPayment.getId());
        String transactionId = transaction.getId();
        logger.debug("Transaction created with ID: {}", transactionId);

        // Log the payment event
        auditService.logPaymentEvent(createdPayment.getId(), "PAYMENT_CREATED");

        try {
            // Transfer funds
            bankingAPIService.transferFunds(paymentRequest.getSourceAccount(),
                    paymentRequest.getDestinationAccount(), paymentRequest.getAmount());

            // Update payment status to COMPLETED
            createdPayment.setStatus(PaymentStatus.COMPLETED.getCode());
            paymentRepository.save(createdPayment);
            logger.info("Payment {} completed successfully", createdPayment.getId());

            // Update transaction status
            transactionService.updateTransactionStatus(transactionId, "SUCCESS");

            // Log the payment completion event
            auditService.logPaymentEvent(createdPayment.getId(), "PAYMENT_COMPLETED");

            // Send payment notification
            notificationService.sendPaymentNotification(
                    getAccountEmail(paymentRequest.getSourceAccount()),
                    "Payment completed successfully. Amount: " + paymentRequest.getAmount() + " " + paymentRequest.getCurrency());

        } catch (Exception e) {
            logger.error("Payment {} failed: {}", createdPayment.getId(), e.getMessage());
            createdPayment.setStatus(PaymentStatus.FAILED.getCode());
            paymentRepository.save(createdPayment);
            transactionService.updateTransactionStatus(transactionId, "FAILED");
            transactionService.updateTransactionFailureReason(transactionId, e.getMessage());
            auditService.logPaymentEvent(createdPayment.getId(), "PAYMENT_FAILED");
            throw e;
        }

        // Prepare the payment response
        return PaymentResponse.builder()
                .id(createdPayment.getId())
                .sourceAccount(maskAccount(createdPayment.getSourceAccount()))
                .destinationAccount(maskAccount(createdPayment.getDestinationAccount()))
                .amount(createdPayment.getAmount())
                .currency(createdPayment.getCurrency())
                .status(createdPayment.getStatus())
                .statusDescription(PaymentStatus.fromString(createdPayment.getStatus()).getDescription())
                .createdAt(createdPayment.getCreatedAt())
                .transactionId(transactionId)
                .message("Payment processed successfully")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable("payments")
    public PaymentResponse getPaymentById(String id) {
        logger.debug("Retrieving payment with ID: {}", id);
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> {
                    logger.warn("Payment not found with ID: {}", id);
                    return new PaymentNotFoundException("Payment not found with ID: " + id);
                });

        return mapToResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Payment> getPayments(Pageable pageable) {
        logger.debug("Retrieving payments with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        return paymentRepository.findAll(pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> getPaymentsBySourceAccount(String sourceAccount) {
        logger.debug("Retrieving payments by source account: {}", maskAccount(sourceAccount));
        return paymentRepository.findBySourceAccount(sourceAccount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> getPaymentsByDestinationAccount(String destinationAccount) {
        logger.debug("Retrieving payments by destination account: {}", maskAccount(destinationAccount));
        return paymentRepository.findByDestinationAccount(destinationAccount);
    }

    @Override
    @CacheEvict(value = "payments", key = "#id")
    public PaymentResponse updatePaymentStatus(String id, String status) {
        logger.info("Updating payment {} status to {}", id, status);

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> {
                    logger.warn("Payment not found for status update: {}", id);
                    return new PaymentNotFoundException("Payment not found with ID: " + id);
                });

        // Validate the new status
        PaymentStatus newStatus = PaymentStatus.fromString(status);
        PaymentStatus currentStatus = PaymentStatus.fromString(payment.getStatus());

        // Check if the transition is valid
        if (!currentStatus.canTransitionTo(newStatus)) {
            logger.warn("Invalid status transition for payment {}: {} -> {}",
                    id, currentStatus.getCode(), newStatus.getCode());
            throw new InvalidStatusTransitionException(id, currentStatus, newStatus);
        }

        String previousStatus = payment.getStatus();
        payment.setStatus(newStatus.getCode());
        Payment updatedPayment = paymentRepository.save(payment);

        // Log the payment status update event
        auditService.logPaymentEvent(payment.getId(),
                String.format("PAYMENT_STATUS_UPDATED:%s->%s", previousStatus, newStatus.getCode()));

        logger.info("Payment {} status updated from {} to {}", id, previousStatus, newStatus.getCode());

        return mapToResponse(updatedPayment);
    }

    @Override
    @CacheEvict(value = "payments", key = "#id")
    public void deletePayment(String id) {
        logger.info("Deleting payment: {}", id);

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> {
                    logger.warn("Payment not found for deletion: {}", id);
                    return new PaymentNotFoundException("Payment not found with ID: " + id);
                });

        // Only allow deletion of non-completed payments
        PaymentStatus currentStatus = PaymentStatus.fromString(payment.getStatus());
        if (currentStatus == PaymentStatus.COMPLETED) {
            logger.warn("Cannot delete completed payment: {}", id);
            throw new IllegalStateException("Cannot delete a completed payment. Use reversal instead.");
        }

        paymentRepository.delete(payment);

        // Log the payment deletion event
        auditService.logPaymentEvent(payment.getId(), "PAYMENT_DELETED");
        logger.info("Payment {} deleted successfully", id);
    }

    @Override
    @CacheEvict(value = "payments", key = "#id")
    public PaymentResponse initiatePaymentReversal(String id, ReversalRequest reversalRequest) {
        logger.info("Initiating reversal for payment: {} with reason: {}", id, reversalRequest.getReason());

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> {
                    logger.warn("Payment not found for reversal: {}", id);
                    return new PaymentNotFoundException("Payment not found with ID: " + id);
                });

        // Validate current status allows reversal
        PaymentStatus currentStatus = PaymentStatus.fromString(payment.getStatus());
        if (!currentStatus.canTransitionTo(PaymentStatus.REVERSED)) {
            logger.warn("Cannot reverse payment {} with status {}", id, currentStatus.getCode());
            throw new PaymentReversalException(id,
                    String.format("Cannot reverse payment with status %s. Only COMPLETED payments can be reversed.",
                            currentStatus.getCode()));
        }

        // Determine reversal amount
        BigDecimal reversalAmount = reversalRequest.isPartialReversal() && reversalRequest.getReversalAmount() != null
                ? reversalRequest.getReversalAmount()
                : payment.getAmount();

        // Validate partial reversal amount
        if (reversalRequest.isPartialReversal()) {
            if (reversalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new PaymentReversalException(id, "Reversal amount must be positive");
            }
            if (reversalAmount.compareTo(payment.getAmount()) > 0) {
                throw new PaymentReversalException(id,
                        "Reversal amount cannot exceed original payment amount");
            }
        }

        try {
            // Perform the actual fund reversal (transfer money back)
            logger.debug("Transferring {} {} from {} to {} for reversal",
                    reversalAmount, payment.getCurrency(),
                    maskAccount(payment.getDestinationAccount()),
                    maskAccount(payment.getSourceAccount()));

            bankingAPIService.transferFunds(
                    payment.getDestinationAccount(),
                    payment.getSourceAccount(),
                    reversalAmount);

            // Update payment status
            PaymentStatus newStatus = reversalRequest.isPartialReversal()
                    ? PaymentStatus.REFUNDED
                    : PaymentStatus.REVERSED;

            payment.setStatus(newStatus.getCode());
            Payment updatedPayment = paymentRepository.save(payment);
            logger.info("Payment {} reversed successfully", id);

            // Log the payment reversal event with details
            String eventDetails = String.format("PAYMENT_%s:amount=%s,reason=%s",
                    newStatus.getCode(), reversalAmount, reversalRequest.getReason());
            auditService.logPaymentEvent(payment.getId(), eventDetails);

            // Send reversal notification
            String notificationMessage = String.format(
                    "Payment reversal processed successfully. Amount: %s %s. Reason: %s",
                    reversalAmount, payment.getCurrency(), reversalRequest.getReason());
            notificationService.sendPaymentNotification(
                    getAccountEmail(payment.getSourceAccount()),
                    notificationMessage);

            return PaymentResponse.builder()
                    .id(updatedPayment.getId())
                    .sourceAccount(maskAccount(updatedPayment.getSourceAccount()))
                    .destinationAccount(maskAccount(updatedPayment.getDestinationAccount()))
                    .amount(updatedPayment.getAmount())
                    .currency(updatedPayment.getCurrency())
                    .status(updatedPayment.getStatus())
                    .statusDescription(newStatus.getDescription())
                    .createdAt(updatedPayment.getCreatedAt())
                    .updatedAt(updatedPayment.getUpdatedAt())
                    .message("Reversal processed successfully. Amount reversed: " + reversalAmount)
                    .build();

        } catch (Exception e) {
            logger.error("Failed to reverse payment {}: {}", id, e.getMessage(), e);
            auditService.logPaymentEvent(payment.getId(), "PAYMENT_REVERSAL_FAILED:" + e.getMessage());
            throw new PaymentReversalException(id, "Failed to process reversal: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a Payment entity to PaymentResponse DTO.
     */
    private PaymentResponse mapToResponse(Payment payment) {
        PaymentStatus status = PaymentStatus.fromString(payment.getStatus());
        return PaymentResponse.builder()
                .id(payment.getId())
                .sourceAccount(maskAccount(payment.getSourceAccount()))
                .destinationAccount(maskAccount(payment.getDestinationAccount()))
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .statusDescription(status.getDescription())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    /**
     * Masks account number for security (shows last 4 digits only).
     */
    private String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }

    private String getAccountCurrency(String accountNumber) {
        // In a real implementation, this would call the banking API
        // For now, return USD as default
        return "USD";
    }

    private String getAccountEmail(String accountNumber) {
        // In a real implementation, this would look up the account holder's email
        // For now, return a placeholder
        return "user@example.com";
    }
}