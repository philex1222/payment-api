package com.example.paymentapi.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentNotificationActivities {

    @ActivityMethod
    void sendNotification(String paymentId, String email, String message);

    @ActivityMethod
    void publishWebhookEvent(String paymentId, String createdBy);
}
