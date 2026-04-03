package com.example.paymentapi.service;

import com.example.paymentapi.exception.TransactionNotFoundException;
import com.example.paymentapi.model.Transaction;

import java.util.List;

public interface TransactionService {
    Transaction createTransaction(String paymentId);
    Transaction getTransactionById(String transactionId) throws TransactionNotFoundException;
    List<Transaction> getTransactionsByPaymentId(String paymentId);
    void updateTransactionStatus(String transactionId, String status);
    void updateTransactionFailureReason(String transactionId, String failureReason);
}