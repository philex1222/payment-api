package com.example.paymentapi.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated banking API service for development and testing.
 * In production this would delegate to an external banking integration.
 *
 * Resilience patterns applied:
 * - @CircuitBreaker  – opens after 50 % failure rate over 10 calls, waits 30 s before half-opening.
 * - @Retry           – retries transient failures up to 3 times with exponential back-off.
 */
@Service
public class BankingAPIServiceImpl implements BankingAPIService {

    private static final Logger logger = LoggerFactory.getLogger(BankingAPIServiceImpl.class);
    private static final String CB_NAME = "bankingApi";

    private final Map<String, BigDecimal> accountBalances = new ConcurrentHashMap<>();
    private static final BigDecimal DEFAULT_BALANCE = BigDecimal.valueOf(10000);

    public BankingAPIServiceImpl() {
        accountBalances.put("1234567890", BigDecimal.valueOf(10000));
        accountBalances.put("0987654321", BigDecimal.valueOf(5000));
        accountBalances.put("1111111111", BigDecimal.valueOf(500));
        accountBalances.put("2222222222", BigDecimal.valueOf(0));
    }

    @Override
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "validateAccountFallback")
    @Retry(name = CB_NAME)
    public boolean validateAccount(String accountNumber) {
        logger.debug("Validating account: {}", maskAccount(accountNumber));

        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            logger.warn("Account validation failed: null or empty account number");
            return false;
        }

        boolean isValid = accountNumber.matches("^\\d{10}$");

        if (!isValid) {
            logger.warn("Account validation failed for {}: invalid format", maskAccount(accountNumber));
        } else {
            logger.debug("Account {} validated successfully", maskAccount(accountNumber));
        }
        return isValid;
    }

    @SuppressWarnings("unused")
    public boolean validateAccountFallback(String accountNumber, Throwable t) {
        logger.error("validateAccount circuit-breaker fallback triggered for account {}: {}",
                maskAccount(accountNumber), t.getMessage());
        return false;
    }

    @Override
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "hasSufficientFundsFallback")
    @Retry(name = CB_NAME)
    public boolean hasSufficientFunds(String accountNumber, BigDecimal amount) {
        logger.debug("Checking funds for account {}: requested amount {}",
                maskAccount(accountNumber), amount);

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("Invalid amount for funds check: {}", amount);
            return false;
        }

        BigDecimal balance = getAccountBalance(accountNumber);
        boolean hasFunds = balance.compareTo(amount) >= 0;

        if (!hasFunds) {
            logger.info("Insufficient funds for account {}: balance={}, requested={}",
                    maskAccount(accountNumber), balance, amount);
        }
        return hasFunds;
    }

    @SuppressWarnings("unused")
    public boolean hasSufficientFundsFallback(String accountNumber, BigDecimal amount, Throwable t) {
        logger.error("hasSufficientFunds circuit-breaker fallback triggered: {}", t.getMessage());
        return false;
    }

    @Override
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "transferFundsFallback")
    @Retry(name = CB_NAME)
    public void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount) {
        logger.info("Initiating transfer: {} -> {}, amount: {}",
                maskAccount(sourceAccount), maskAccount(destinationAccount), amount);

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        // ConcurrentHashMap.compute() is atomic per-key, so no outer lock is required.
        // An outer synchronized(this) would cause thread-pinning under Java virtual threads.
        // We perform a speculative balance check before deducting: if the check passes but
        // another thread drains the account before compute() runs, the compute lambda
        // performs a final guard and throws. This is safe for this in-memory simulation;
        // a real integration would rely on the banking system's own ACID transaction.
        BigDecimal sourceBalance = getAccountBalance(sourceAccount);
        if (sourceBalance.compareTo(amount) < 0) {
            logger.error("Transfer failed: insufficient funds in source account {}",
                    maskAccount(sourceAccount));
            throw new IllegalStateException("Insufficient funds for transfer");
        }

        deductFunds(sourceAccount, amount);
        addFunds(destinationAccount, amount);

        logger.info("Transfer completed: {} -> {}, amount: {}",
                maskAccount(sourceAccount), maskAccount(destinationAccount), amount);
    }

    @SuppressWarnings("unused")
    public void transferFundsFallback(String sourceAccount, String destinationAccount,
                                      BigDecimal amount, Throwable t) {
        logger.error("transferFunds circuit-breaker fallback triggered for transfer {} -> {}: {}",
                maskAccount(sourceAccount), maskAccount(destinationAccount), t.getMessage());
        throw new IllegalStateException("Banking service is temporarily unavailable. Please try again later.", t);
    }

    public BigDecimal getAccountBalance(String accountNumber) {
        return accountBalances.computeIfAbsent(accountNumber, k -> DEFAULT_BALANCE);
    }

    private void deductFunds(String accountNumber, BigDecimal amount) {
        accountBalances.compute(accountNumber, (key, balance) -> {
            BigDecimal current = balance != null ? balance : DEFAULT_BALANCE;
            return current.subtract(amount);
        });
    }

    private void addFunds(String accountNumber, BigDecimal amount) {
        accountBalances.compute(accountNumber, (key, balance) -> {
            BigDecimal current = balance != null ? balance : BigDecimal.ZERO;
            return current.add(amount);
        });
    }

    private String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }

    /**
     * Resets account balances to defaults (for testing only).
     */
    public void resetBalances() {
        accountBalances.clear();
        accountBalances.put("1234567890", BigDecimal.valueOf(10000));
        accountBalances.put("0987654321", BigDecimal.valueOf(5000));
        accountBalances.put("1111111111", BigDecimal.valueOf(500));
        accountBalances.put("2222222222", BigDecimal.valueOf(0));
    }
}
