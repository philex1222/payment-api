package com.example.paymentapi.service;

import org.springframework.scheduling.annotation.Scheduled;

public interface SchedulerService {
    @Scheduled(cron = "0 0 0 * * ?") // Run every day at midnight
    void performDailyMaintenance();
    @Scheduled(cron = "0 0 * * * ?") // Run every hour
    void generateHourlyReport();
}