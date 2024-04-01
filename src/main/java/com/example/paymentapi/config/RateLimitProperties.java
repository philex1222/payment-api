package com.example.paymentapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {
    private int limit;
    private long refreshPeriod;
    private long timeout;

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public long getRefreshPeriod() {
        return refreshPeriod;
    }

    public void setRefreshPeriod(long refreshPeriod) {
        this.refreshPeriod = refreshPeriod;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }
// Getters and setters
}