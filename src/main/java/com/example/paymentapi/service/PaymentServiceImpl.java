package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.repository.PaymentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;
    private final BankingAPIService bankingAPIService;

    public PaymentServiceImpl(PaymentRepository paymentRepository, TransactionService transactionService,
                              AuditService auditService, BankingAPIService bankingAPIService) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.bankingAPIService = bankingAPIService;
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest paymentRequest) throws InsufficientFundsException, InvalidAccountException {
        // Validate source and destination accounts
        if (!bankingAPIService.validateAccount(paymentRequest.getSourceAccount())) {
            throw new InvalidAccountException("Invalid source account");
        }
        if (!bankingAPIService.validateAccount(paymentRequest.getDestinationAccount())) {
            throw new InvalidAccountException("Invalid destination account");
        }

        // Check sufficient funds
        if (!bankingAPIService.hasSufficientFunds(paymentRequest.getSourceAccount(), paymentRequest.getAmount())) {
            throw new InsufficientFundsException("Insufficient funds in the source account");
        }

        // Create a payment
        Payment payment = new Payment();
        payment.setSourceAccount(paymentRequest.getSourceAccount());
        payment.setDestinationAccount(paymentRequest.getDestinationAccount());
        payment.setAmount(paymentRequest.getAmount());
        payment.setCurrency(paymentRequest.getCurrency());
        payment.setStatus("PENDING");
        Payment createdPayment = paymentRepository.save(payment);

        // Create a transaction
        String transactionId = transactionService.createTransaction(createdPayment.getId()).getId();

        // Log the payment event
        auditService.logPaymentEvent(createdPayment.getId(), "PAYMENT_CREATED");

        // Transfer funds
        bankingAPIService.transferFunds(paymentRequest.getSourceAccount(), paymentRequest.getDestinationAccount(), paymentRequest.getAmount());

        // Update payment status
        createdPayment.setStatus("COMPLETED");
        paymentRepository.save(createdPayment);

        // Log the payment completion event
        auditService.logPaymentEvent(createdPayment.getId(), "PAYMENT_COMPLETED");

        // Prepare the payment response
        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId(createdPayment.getId());
        paymentResponse.setStatus(createdPayment.getStatus());
        paymentResponse.setCreatedAt(createdPayment.getCreatedAt());

        return paymentResponse;
    }

    @Override
    public PaymentResponse getPaymentById(String id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found with ID: " + id));

        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId(payment.getId());
        paymentResponse.setStatus(payment.getStatus());
        paymentResponse.setCreatedAt(payment.getCreatedAt());

        return paymentResponse;
    }

    @Override
    public Page<Payment>getPayments(Pageable pageable) {
        return paymentRepository.findAll(pageable);
    }
    @Override
    public List<Payment> getPaymentsBySourceAccount(String sourceAccount) {
        return paymentRepository.findBySourceAccount(sourceAccount);
    }

    @Override
    public List<Payment> getPaymentsByDestinationAccount(String destinationAccount) {
        return paymentRepository.findByDestinationAccount(destinationAccount);
    }

    @Override
    public PaymentResponse updatePaymentStatus(String id, String status) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found with ID: " + id));

        payment.setStatus(status);
        Payment updatedPayment = paymentRepository.save(payment);

        // Log the payment status update event
        auditService.logPaymentEvent(payment.getId(), "PAYMENT_STATUS_UPDATED");

        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId(updatedPayment.getId());
        paymentResponse.setStatus(updatedPayment.getStatus());
        paymentResponse.setCreatedAt(updatedPayment.getCreatedAt());

        return paymentResponse;
    }

    @Override
    public void deletePayment(String id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found with ID: " + id));

        paymentRepository.delete(payment);

        // Log the payment deletion event
        auditService.logPaymentEvent(payment.getId(), "PAYMENT_DELETED");
    }
}