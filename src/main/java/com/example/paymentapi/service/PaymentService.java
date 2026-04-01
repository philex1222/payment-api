package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PaymentService {
    PaymentResponse createPayment(PaymentRequest paymentRequest) throws InsufficientFundsException, InvalidAccountException;
    PaymentResponse getPaymentById(String id);
    Page<PaymentResponse> getPayments(String status, LocalDateTime dateFrom, LocalDateTime dateTo,
                                      BigDecimal amountFrom, BigDecimal amountTo,
                                      String currency, Pageable pageable);
    List<PaymentResponse> getPaymentsBySourceAccount(String sourceAccount);
    List<PaymentResponse> getPaymentsByDestinationAccount(String destinationAccount);
    PaymentResponse updatePaymentStatus(String id, String status);
    PaymentResponse cancelPayment(String id);
    void deletePayment(String id);
    PaymentResponse initiatePaymentReversal(String id, ReversalRequest reversalRequest);
    PaymentResponse retryPayment(String id);
}