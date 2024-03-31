package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentServiceTest {
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private TransactionService transactionService;

    @Mock
    private AuditService auditService;

    @Mock
    private BankingAPIService bankingAPIService;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Test
    public void testCreatePayment() {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        Payment payment = new Payment();
        payment.setId("payment123");
        payment.setStatus("COMPLETED");

        when(bankingAPIService.validateAccount(paymentRequest.getSourceAccount())).thenReturn(true);
        when(bankingAPIService.validateAccount(paymentRequest.getDestinationAccount())).thenReturn(true);
        when(bankingAPIService.hasSufficientFunds(paymentRequest.getSourceAccount(), paymentRequest.getAmount())).thenReturn(true);
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(transactionService.createTransaction(payment.getId())).thenReturn(new Transaction());

        PaymentResponse paymentResponse = paymentService.createPayment(paymentRequest);

        assertEquals("payment123", paymentResponse.getId());
        assertEquals("COMPLETED", paymentResponse.getStatus());

        verify(paymentRepository, times(2)).save(any(Payment.class));
        verify(transactionService, times(1)).createTransaction(payment.getId());
        verify(auditService, times(2)).logPaymentEvent(eq(payment.getId()), anyString());
        verify(bankingAPIService, times(1)).transferFunds(paymentRequest.getSourceAccount(), paymentRequest.getDestinationAccount(), paymentRequest.getAmount());
    }

// Other test methods...
}