package com.example.paymentapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NotificationService Tests")
class NotificationServiceTest {

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService();
    }

    @Test
    @DisplayName("sendPaymentNotification records a notification")
    void sendPaymentNotification_recordsEntry() throws InterruptedException {
        notificationService.sendPaymentNotification("user@example.com", "Payment completed");

        // Small sleep to let the @Async method finish (it has a 10 ms simulated delay).
        // In unit tests the method runs synchronously because there is no Spring context,
        // so we just verify state synchronously.
        Thread.sleep(50);

        assertEquals(1, notificationService.getNotificationCount());
        List<NotificationService.NotificationRecord> records = notificationService.getSentNotifications();
        assertEquals(1, records.size());
        assertEquals("user@example.com", records.get(0).getRecipient());
        assertEquals(NotificationService.NotificationType.PAYMENT, records.get(0).getType());
        assertTrue(records.get(0).isSuccess());
    }

    @Test
    @DisplayName("sendAlertNotification records an ALERT entry synchronously")
    void sendAlertNotification_recordsEntry() {
        notificationService.sendAlertNotification("ops@example.com", "Rate Limit", "Threshold exceeded");

        assertEquals(1, notificationService.getNotificationCount());
        NotificationService.NotificationRecord record = notificationService.getSentNotifications().get(0);
        assertEquals(NotificationService.NotificationType.ALERT, record.getType());
        assertTrue(record.getMessage().contains("Rate Limit"));
    }

    @Test
    @DisplayName("clearNotifications empties the records list")
    void clearNotifications_emptiesList() throws InterruptedException {
        notificationService.sendPaymentNotification("a@example.com", "msg1");
        Thread.sleep(50);
        notificationService.sendAlertNotification("b@example.com", "S", "msg2");

        notificationService.clearNotifications();

        assertEquals(0, notificationService.getNotificationCount());
        assertTrue(notificationService.getSentNotifications().isEmpty());
    }

    @Test
    @DisplayName("getSentNotifications returns a defensive copy")
    void getSentNotifications_returnsDefensiveCopy() {
        notificationService.sendAlertNotification("a@a.com", "S", "m");
        List<NotificationService.NotificationRecord> copy = notificationService.getSentNotifications();
        copy.clear();

        // Original list should not be affected
        assertEquals(1, notificationService.getNotificationCount());
    }
}
