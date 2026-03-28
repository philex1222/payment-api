package com.example.paymentapi.service;

public interface SchedulerService {
    /** Retry FAILED payments that have not yet exceeded the max retry limit. */
    void retryFailedPayments();
    /** Nightly maintenance — e.g. future log archival, stale record cleanup. */
    void cleanupOldRecords();
}