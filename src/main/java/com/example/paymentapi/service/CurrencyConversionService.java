package com.example.paymentapi.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class CurrencyConversionService {
    public BigDecimal convert(String sourceCurrency, String targetCurrency, BigDecimal amount) {
        // Implement currency conversion logic using an external API or exchange rates
        // Example implementation:
        if (sourceCurrency.equals("USD") && targetCurrency.equals("EUR")) {
            return amount.multiply(BigDecimal.valueOf(0.85));
        } else if (sourceCurrency.equals("EUR") && targetCurrency.equals("USD")) {
            return amount.multiply(BigDecimal.valueOf(1.18));
        } else {
            throw new IllegalArgumentException("Unsupported currency conversion");
        }
    }
}