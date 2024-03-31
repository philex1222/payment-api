package com.example.paymentapi.service;

import java.math.BigDecimal;

public interface BankingAPIService {
    boolean validateAccount(String accountNumber);
    boolean hasSufficientFunds(String accountNumber, BigDecimal amount);
    void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount);
}