package com.example.paymentapi.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;

@ActivityInterface
public interface PaymentTransferActivities {

    @ActivityMethod
    void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount);
}
