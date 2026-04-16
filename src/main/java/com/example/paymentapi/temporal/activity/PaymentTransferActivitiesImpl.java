package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.BankingAPIService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentTransferActivitiesImpl implements PaymentTransferActivities {

    private final BankingAPIService bankingAPIService;

    public PaymentTransferActivitiesImpl(BankingAPIService bankingAPIService) {
        this.bankingAPIService = bankingAPIService;
    }

    @Override
    public void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount) {
        bankingAPIService.transferFunds(sourceAccount, destinationAccount, amount);
    }
}
