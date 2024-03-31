package com.example.paymentapi.service;

import com.example.paymentapi.exception.TransactionNotFoundException;
import com.example.paymentapi.model.Transaction;

public interface TransactionService {
    Transaction createTransaction(String paymentId);
    Transaction getTransactionById(String transactionId) throws TransactionNotFoundException;
    void updateTransactionStatus(String transactionId, String status);
    void updateTransactionFailureReason(String transactionId, String failureReason);
}