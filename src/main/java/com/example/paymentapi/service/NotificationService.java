package com.example.paymentapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service for sending notifications to users.
 * This is a simulated implementation for development/testing purposes.
 * In production, this would integrate with email/SMS providers.
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    // Store sent notifications for testing/verification purposes
    private final List<NotificationRecord> sentNotifications = Collections.synchronizedList(new ArrayList<>());

    /**
     * Sends a payment notification to the specified recipient.
     *
     * @param recipient The email or phone number of the recipient
     * @param message   The notification message
     */
    @Async
    public void sendPaymentNotification(String recipient, String message) {
        logger.info("Sending notification to {}: {}", maskRecipient(recipient), summarizeMessage(message));

        try {
            // Simulate notification sending delay
            // In production, this would call an actual email/SMS service
            simulateNotificationSend(recipient, message);

            // Record the notification
            NotificationRecord record = new NotificationRecord(
                    recipient,
                    message,
                    LocalDateTime.now(),
                    NotificationType.PAYMENT,
                    true
            );
            sentNotifications.add(record);

            logger.debug("Notification sent successfully to {}", maskRecipient(recipient));

        } catch (Exception e) {
            logger.error("Failed to send notification to {}: {}", maskRecipient(recipient), e.getMessage());

            // Record the failed notification
            NotificationRecord record = new NotificationRecord(
                    recipient,
                    message,
                    LocalDateTime.now(),
                    NotificationType.PAYMENT,
                    false
            );
            sentNotifications.add(record);
        }
    }

    /**
     * Sends an alert notification (for system alerts, security events, etc.)
     */
    public void sendAlertNotification(String recipient, String subject, String message) {
        logger.warn("ALERT to {}: {} - {}", maskRecipient(recipient), subject, summarizeMessage(message));

        NotificationRecord record = new NotificationRecord(
                recipient,
                subject + ": " + message,
                LocalDateTime.now(),
                NotificationType.ALERT,
                true
        );
        sentNotifications.add(record);
    }

    /**
     * Gets all sent notifications (for testing purposes).
     */
    public List<NotificationRecord> getSentNotifications() {
        return new ArrayList<>(sentNotifications);
    }

    /**
     * Clears all notification records (for testing purposes).
     */
    public void clearNotifications() {
        sentNotifications.clear();
    }

    /**
     * Gets the count of sent notifications.
     */
    public int getNotificationCount() {
        return sentNotifications.size();
    }

    private void simulateNotificationSend(String recipient, String message) {
        // Simulate network latency
        try {
            Thread.sleep(10); // Small delay to simulate async processing
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String maskRecipient(String recipient) {
        if (recipient == null || recipient.length() < 4) {
            return "****";
        }
        if (recipient.contains("@")) {
            int atIndex = recipient.indexOf("@");
            if (atIndex > 2) {
                return recipient.substring(0, 2) + "***" + recipient.substring(atIndex);
            }
        }
        return recipient.substring(0, 2) + "****";
    }

    private String summarizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 50 ? message.substring(0, 50) + "..." : message;
    }

    /**
     * Record of a sent notification.
     */
    public static class NotificationRecord {
        private final String recipient;
        private final String message;
        private final LocalDateTime sentAt;
        private final NotificationType type;
        private final boolean success;

        public NotificationRecord(String recipient, String message, LocalDateTime sentAt,
                                  NotificationType type, boolean success) {
            this.recipient = recipient;
            this.message = message;
            this.sentAt = sentAt;
            this.type = type;
            this.success = success;
        }

        public String getRecipient() { return recipient; }
        public String getMessage() { return message; }
        public LocalDateTime getSentAt() { return sentAt; }
        public NotificationType getType() { return type; }
        public boolean isSuccess() { return success; }
    }

    public enum NotificationType {
        PAYMENT, ALERT, SYSTEM
    }
}