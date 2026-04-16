package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentPersistenceActivities {

    @ActivityMethod
    String persistPending(PaymentRequest request, String initiatedBy);

    @ActivityMethod
    void completePayment(String paymentId);

    @ActivityMethod
    void failPayment(String paymentId, String reason);
}
