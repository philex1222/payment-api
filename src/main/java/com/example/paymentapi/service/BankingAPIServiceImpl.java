package com.example.paymentapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of BankingAPIService.
 * This is a simulated implementation for development/testing purposes.
 * In production, this would integrate with actual banking APIs.
 */
@Service
public class BankingAPIServiceImpl implements BankingAPIService {

    private static final Logger logger = LoggerFactory.getLogger(BankingAPIServiceImpl.class);

    // Simulated account balances for testing
    private final Map<String, BigDecimal> accountBalances = new ConcurrentHashMap<>();

    // Default starting balance for new accounts
    private static final BigDecimal DEFAULT_BALANCE = BigDecimal.valueOf(10000);

    public BankingAPIServiceImpl() {
        // Initialize some test accounts
        accountBalances.put("1234567890", BigDecimal.valueOf(10000));
        accountBalances.put("0987654321", BigDecimal.valueOf(5000));
        accountBalances.put("1111111111", BigDecimal.valueOf(500));
        accountBalances.put("2222222222", BigDecimal.valueOf(0));
    }

    @Override
    public boolean validateAccount(String accountNumber) {
        logger.debug("Validating account: {}", maskAccount(accountNumber));

        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            logger.warn("Account validation failed: null or empty account number");
            return false;
        }

        // Validate account number format (10 digits, numeric only)
        boolean isValid = accountNumber.matches("^\\d{10}$");

        if (!isValid) {
            logger.warn("Account validation failed for {}: invalid format", maskAccount(accountNumber));
        } else {
            logger.debug("Account {} validated successfully", maskAccount(accountNumber));
        }

        return isValid;
    }

    @Override
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

    @Override
    public void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount) {
        logger.info("Initiating transfer: {} -> {}, amount: {}",
                maskAccount(sourceAccount), maskAccount(destinationAccount), amount);

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        synchronized (this) {
            // Deduct from source
            BigDecimal sourceBalance = getAccountBalance(sourceAccount);
            if (sourceBalance.compareTo(amount) < 0) {
                logger.error("Transfer failed: insufficient funds in source account {}",
                        maskAccount(sourceAccount));
                throw new IllegalStateException("Insufficient funds for transfer");
            }

            deductFunds(sourceAccount, amount);
            addFunds(destinationAccount, amount);

            logger.info("Transfer completed successfully: {} -> {}, amount: {}",
                    maskAccount(sourceAccount), maskAccount(destinationAccount), amount);
        }
    }

    /**
     * Gets the current balance of an account.
     */
    public BigDecimal getAccountBalance(String accountNumber) {
        return accountBalances.computeIfAbsent(accountNumber, k -> DEFAULT_BALANCE);
    }

    private void deductFunds(String accountNumber, BigDecimal amount) {
        logger.debug("Deducting {} from account {}", amount, maskAccount(accountNumber));
        accountBalances.compute(accountNumber, (key, balance) -> {
            BigDecimal currentBalance = balance != null ? balance : DEFAULT_BALANCE;
            return currentBalance.subtract(amount);
        });
    }

    private void addFunds(String accountNumber, BigDecimal amount) {
        logger.debug("Adding {} to account {}", amount, maskAccount(accountNumber));
        accountBalances.compute(accountNumber, (key, balance) -> {
            BigDecimal currentBalance = balance != null ? balance : BigDecimal.ZERO;
            return currentBalance.add(amount);
        });
    }

    /**
     * Masks account number for logging security.
     */
    private String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }

    /**
     * Resets account balances to default values (for testing purposes).
     */
    public void resetBalances() {
        accountBalances.clear();
        accountBalances.put("1234567890", BigDecimal.valueOf(10000));
        accountBalances.put("0987654321", BigDecimal.valueOf(5000));
        accountBalances.put("1111111111", BigDecimal.valueOf(500));
        accountBalances.put("2222222222", BigDecimal.valueOf(0));
    }
}