package com.example.paymentapi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Aggregated platform statistics returned by GET /api/v1/admin/stats.
 * All counts are point-in-time snapshots; no caching is applied.
 */
public class AdminStatsResponse {

    private long totalPayments;
    private Map<String, Long> paymentsByStatus;
    private long todayPaymentCount;
    private BigDecimal todayVolume;
    private long totalUsers;
    private LocalDateTime generatedAt;

    // ── constructor ────────────────────────────────────────────────────────────

    public AdminStatsResponse() {}

    public AdminStatsResponse(long totalPayments,
                              Map<String, Long> paymentsByStatus,
                              long todayPaymentCount,
                              BigDecimal todayVolume,
                              long totalUsers,
                              LocalDateTime generatedAt) {
        this.totalPayments = totalPayments;
        this.paymentsByStatus = paymentsByStatus;
        this.todayPaymentCount = todayPaymentCount;
        this.todayVolume = todayVolume;
        this.totalUsers = totalUsers;
        this.generatedAt = generatedAt;
    }

    // ── getters ────────────────────────────────────────────────────────────────

    public long getTotalPayments() { return totalPayments; }
    public Map<String, Long> getPaymentsByStatus() { return paymentsByStatus; }
    public long getTodayPaymentCount() { return todayPaymentCount; }
    public BigDecimal getTodayVolume() { return todayVolume; }
    public long getTotalUsers() { return totalUsers; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
}
