package com.example.paymentapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service for currency conversion operations.
 * This implementation uses static exchange rates for simplicity.
 * In production, this would integrate with a real-time exchange rate API.
 */
@Service
public class CurrencyConversionService {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyConversionService.class);

    // Exchange rates relative to USD
    private static final Map<String, BigDecimal> EXCHANGE_RATES = new HashMap<>();

    static {
        // Base currency: USD = 1.00
        EXCHANGE_RATES.put("USD", BigDecimal.ONE);
        EXCHANGE_RATES.put("EUR", BigDecimal.valueOf(0.85));
        EXCHANGE_RATES.put("GBP", BigDecimal.valueOf(0.73));
        EXCHANGE_RATES.put("JPY", BigDecimal.valueOf(110.0));
        EXCHANGE_RATES.put("CHF", BigDecimal.valueOf(0.92));
        EXCHANGE_RATES.put("CAD", BigDecimal.valueOf(1.25));
        EXCHANGE_RATES.put("AUD", BigDecimal.valueOf(1.35));
        EXCHANGE_RATES.put("NZD", BigDecimal.valueOf(1.42));
        EXCHANGE_RATES.put("CNY", BigDecimal.valueOf(6.45));
        EXCHANGE_RATES.put("INR", BigDecimal.valueOf(74.50));
        EXCHANGE_RATES.put("MXN", BigDecimal.valueOf(20.10));
        EXCHANGE_RATES.put("BRL", BigDecimal.valueOf(5.25));
        EXCHANGE_RATES.put("SGD", BigDecimal.valueOf(1.35));
        EXCHANGE_RATES.put("HKD", BigDecimal.valueOf(7.78));
        EXCHANGE_RATES.put("KRW", BigDecimal.valueOf(1180.0));
    }

    /**
     * Converts an amount from one currency to another.
     *
     * @param sourceCurrency The source currency code (e.g., "USD")
     * @param targetCurrency The target currency code (e.g., "EUR")
     * @param amount         The amount to convert
     * @return The converted amount
     * @throws IllegalArgumentException if the currency is not supported
     */
    public BigDecimal convert(String sourceCurrency, String targetCurrency, BigDecimal amount) {
        logger.debug("Converting {} {} to {}", amount, sourceCurrency, targetCurrency);

        // Validate inputs
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be non-negative");
        }

        if (sourceCurrency == null || targetCurrency == null) {
            throw new IllegalArgumentException("Currency codes cannot be null");
        }

        String source = sourceCurrency.toUpperCase().trim();
        String target = targetCurrency.toUpperCase().trim();

        // Same currency, no conversion needed
        if (source.equals(target)) {
            logger.debug("Same currency, no conversion needed");
            return amount;
        }

        // Validate currencies are supported
        if (!EXCHANGE_RATES.containsKey(source)) {
            logger.warn("Unsupported source currency: {}", source);
            throw new IllegalArgumentException("Unsupported source currency: " + source);
        }

        if (!EXCHANGE_RATES.containsKey(target)) {
            logger.warn("Unsupported target currency: {}", target);
            throw new IllegalArgumentException("Unsupported target currency: " + target);
        }

        // Convert via USD as base currency
        BigDecimal sourceRate = EXCHANGE_RATES.get(source);
        BigDecimal targetRate = EXCHANGE_RATES.get(target);

        // amount in USD = amount / sourceRate
        // amount in target = amount in USD * targetRate
        BigDecimal amountInUsd = amount.divide(sourceRate, 10, RoundingMode.HALF_UP);
        BigDecimal convertedAmount = amountInUsd.multiply(targetRate)
                .setScale(4, RoundingMode.HALF_UP);

        logger.info("Converted {} {} to {} {} (rate: {})",
                amount, source, convertedAmount, target,
                targetRate.divide(sourceRate, 6, RoundingMode.HALF_UP));

        return convertedAmount;
    }

    /**
     * Gets the exchange rate between two currencies.
     *
     * @param sourceCurrency The source currency code
     * @param targetCurrency The target currency code
     * @return The exchange rate
     */
    public BigDecimal getExchangeRate(String sourceCurrency, String targetCurrency) {
        String source = sourceCurrency.toUpperCase().trim();
        String target = targetCurrency.toUpperCase().trim();

        if (!EXCHANGE_RATES.containsKey(source) || !EXCHANGE_RATES.containsKey(target)) {
            throw new IllegalArgumentException("Unsupported currency pair: " + source + "/" + target);
        }

        BigDecimal sourceRate = EXCHANGE_RATES.get(source);
        BigDecimal targetRate = EXCHANGE_RATES.get(target);

        return targetRate.divide(sourceRate, 6, RoundingMode.HALF_UP);
    }

    /**
     * Gets all supported currency codes.
     */
    public Set<String> getSupportedCurrencies() {
        return EXCHANGE_RATES.keySet();
    }

    /**
     * Checks if a currency is supported.
     */
    public boolean isCurrencySupported(String currencyCode) {
        return currencyCode != null && EXCHANGE_RATES.containsKey(currencyCode.toUpperCase().trim());
    }
}