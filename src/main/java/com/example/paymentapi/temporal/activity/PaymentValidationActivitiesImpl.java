package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.shared.PaymentValidationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentValidationActivitiesImpl implements PaymentValidationActivities {

    private final PaymentValidationService validationService;

    public PaymentValidationActivitiesImpl(PaymentValidationService validationService) {
        this.validationService = validationService;
    }

    @Override
    public void validateAccounts(String sourceAccount, String destinationAccount) {
        validationService.validateAccounts(sourceAccount, destinationAccount);
    }

    @Override
    public void validateFunds(String sourceAccount, BigDecimal amount) {
        validationService.validateSufficientFunds(sourceAccount, amount);
    }
}
