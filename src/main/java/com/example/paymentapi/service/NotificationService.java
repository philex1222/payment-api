package com.example.paymentapi.service;

import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    public void sendPaymentNotification(String recipient, String message) {
        // Implement notification sending logic using an email service provider or SMS gateway
        // Example implementation:
        System.out.println("Sending notification to " + recipient + ": " + message);
    }
}