package com.example.paymentapi.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;

@ActivityInterface
public interface PaymentValidationActivities {

    @ActivityMethod
    void validateAccounts(String sourceAccount, String destinationAccount);

    @ActivityMethod
    void validateFunds(String sourceAccount, BigDecimal amount);
}
