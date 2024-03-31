package com.example.paymentapi.service;

import com.example.paymentapi.exception.TransactionNotFoundException;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.repository.TransactionRepository;
import org.springframework.stereotype.Service;

@Service
public class TransactionServiceImpl implements TransactionService {
    private final TransactionRepository transactionRepository;

    public TransactionServiceImpl(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Transaction createTransaction(String paymentId) {
        Transaction transaction = new Transaction();
        transaction.setPaymentId(paymentId);
        transaction.setStatus("PENDING");
        return transactionRepository.save(transaction);
    }

    @Override
    public Transaction getTransactionById(String transactionId) throws TransactionNotFoundException {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found with ID: " + transactionId));
    }

    @Override
    public void updateTransactionStatus(String transactionId, String status) {
        Transaction transaction = getTransactionById(transactionId);
        transaction.setStatus(status);
        transactionRepository.save(transaction);
    }

    @Override
    public void updateTransactionFailureReason(String transactionId, String failureReason) {
        Transaction transaction = getTransactionById(transactionId);
        transaction.setFailureReason(failureReason);
        transactionRepository.save(transaction);
    }
}