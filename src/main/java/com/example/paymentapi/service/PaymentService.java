package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PaymentService {
    PaymentResponse createPayment(PaymentRequest paymentRequest) throws InsufficientFundsException, InvalidAccountException;
    PaymentResponse getPaymentById(String id);
    Page<Payment> getPayments(Pageable pageable);
    List<Payment> getPaymentsBySourceAccount(String sourceAccount);
    List<Payment> getPaymentsByDestinationAccount(String destinationAccount);
    PaymentResponse updatePaymentStatus(String id, String status);
    void deletePayment(String id);
}