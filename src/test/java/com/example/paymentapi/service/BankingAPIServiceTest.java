package com.example.paymentapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BankingAPIService Tests")
class BankingAPIServiceTest {

    private BankingAPIServiceImpl bankingAPIService;

    @BeforeEach
    void setUp() {
        bankingAPIService = new BankingAPIServiceImpl();
    }

    @Nested
    @DisplayName("Account Validation Tests")
    class ValidateAccountTests {

        @ParameterizedTest
        @ValueSource(strings = {"1234567890", "0987654321", "1111111111", "0000000000"})
        @DisplayName("Should accept valid 10-digit account numbers")
        void testValidateAccount_ValidAccounts(String accountNumber) {
            assertTrue(bankingAPIService.validateAccount(accountNumber));
        }

        @ParameterizedTest
        @ValueSource(strings = {"123456789", "12345678901", "12345", "123456789012345"})
        @DisplayName("Should reject account numbers with wrong length")
        void testValidateAccount_WrongLength(String accountNumber) {
            assertFalse(bankingAPIService.validateAccount(accountNumber));
        }

        @ParameterizedTest
        @ValueSource(strings = {"123456789a", "abcdefghij", "12345-6789", "1234 56789"})
        @DisplayName("Should reject non-numeric account numbers")
        void testValidateAccount_NonNumeric(String accountNumber) {
            assertFalse(bankingAPIService.validateAccount(accountNumber));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should reject null and empty account numbers")
        void testValidateAccount_NullOrEmpty(String accountNumber) {
            assertFalse(bankingAPIService.validateAccount(accountNumber));
        }

        @Test
        @DisplayName("Should reject whitespace-only account numbers")
        void testValidateAccount_Whitespace() {
            assertFalse(bankingAPIService.validateAccount("          "));
        }
    }

    @Nested
    @DisplayName("Sufficient Funds Tests")
    class HasSufficientFundsTests {

        @Test
        @DisplayName("Should return true when balance is greater than amount")
        void testHasSufficientFunds_BalanceGreaterThanAmount() {
            // Account 1234567890 has 10000 balance
            assertTrue(bankingAPIService.hasSufficientFunds("1234567890", BigDecimal.valueOf(5000)));
        }

        @Test
        @DisplayName("Should return true when balance equals amount")
        void testHasSufficientFunds_BalanceEqualsAmount() {
            assertTrue(bankingAPIService.hasSufficientFunds("1234567890", BigDecimal.valueOf(10000)));
        }

        @Test
        @DisplayName("Should return false when balance is less than amount")
        void testHasSufficientFunds_InsufficientBalance() {
            assertFalse(bankingAPIService.hasSufficientFunds("1234567890", BigDecimal.valueOf(15000)));
        }

        @Test
        @DisplayName("Should return false for null amount")
        void testHasSufficientFunds_NullAmount() {
            assertFalse(bankingAPIService.hasSufficientFunds("1234567890", null));
        }

        @Test
        @DisplayName("Should return false for zero amount")
        void testHasSufficientFunds_ZeroAmount() {
            assertFalse(bankingAPIService.hasSufficientFunds("1234567890", BigDecimal.ZERO));
        }

        @Test
        @DisplayName("Should return false for negative amount")
        void testHasSufficientFunds_NegativeAmount() {
            assertFalse(bankingAPIService.hasSufficientFunds("1234567890", BigDecimal.valueOf(-100)));
        }

        @Test
        @DisplayName("Should handle small amounts")
        void testHasSufficientFunds_SmallAmount() {
            assertTrue(bankingAPIService.hasSufficientFunds("1234567890", BigDecimal.valueOf(0.01)));
        }
    }

    @Nested
    @DisplayName("Fund Transfer Tests")
    class TransferFundsTests {

        @Test
        @DisplayName("Should transfer funds successfully")
        void testTransferFunds_Success() {
            BigDecimal initialSourceBalance = bankingAPIService.getAccountBalance("1234567890");
            BigDecimal initialDestBalance = bankingAPIService.getAccountBalance("0987654321");
            BigDecimal transferAmount = BigDecimal.valueOf(100);

            bankingAPIService.transferFunds("1234567890", "0987654321", transferAmount);

            assertEquals(initialSourceBalance.subtract(transferAmount),
                    bankingAPIService.getAccountBalance("1234567890"));
            assertEquals(initialDestBalance.add(transferAmount),
                    bankingAPIService.getAccountBalance("0987654321"));
        }

        @Test
        @DisplayName("Should throw exception for insufficient funds during transfer")
        void testTransferFunds_InsufficientFunds() {
            assertThrows(IllegalStateException.class,
                    () -> bankingAPIService.transferFunds("1234567890", "0987654321",
                            BigDecimal.valueOf(100000)));
        }

        @Test
        @DisplayName("Should throw exception for null amount")
        void testTransferFunds_NullAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> bankingAPIService.transferFunds("1234567890", "0987654321", null));
        }

        @Test
        @DisplayName("Should throw exception for zero amount")
        void testTransferFunds_ZeroAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> bankingAPIService.transferFunds("1234567890", "0987654321", BigDecimal.ZERO));
        }

        @Test
        @DisplayName("Should throw exception for negative amount")
        void testTransferFunds_NegativeAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> bankingAPIService.transferFunds("1234567890", "0987654321",
                            BigDecimal.valueOf(-100)));
        }

        @Test
        @DisplayName("Should handle multiple consecutive transfers")
        void testTransferFunds_MultipleTransfers() {
            bankingAPIService.resetBalances();

            bankingAPIService.transferFunds("1234567890", "0987654321", BigDecimal.valueOf(100));
            bankingAPIService.transferFunds("1234567890", "0987654321", BigDecimal.valueOf(200));
            bankingAPIService.transferFunds("1234567890", "0987654321", BigDecimal.valueOf(300));

            assertEquals(BigDecimal.valueOf(9400), bankingAPIService.getAccountBalance("1234567890"));
            assertEquals(BigDecimal.valueOf(5600), bankingAPIService.getAccountBalance("0987654321"));
        }

        @Test
        @DisplayName("Should create new account with default balance if not exists")
        void testTransferFunds_NewAccount() {
            bankingAPIService.transferFunds("1234567890", "9999999999", BigDecimal.valueOf(100));

            assertNotNull(bankingAPIService.getAccountBalance("9999999999"));
        }
    }

    @Nested
    @DisplayName("Account Balance Tests")
    class GetAccountBalanceTests {

        @Test
        @DisplayName("Should return preset balance for known accounts")
        void testGetAccountBalance_KnownAccount() {
            bankingAPIService.resetBalances();
            assertEquals(BigDecimal.valueOf(10000), bankingAPIService.getAccountBalance("1234567890"));
            assertEquals(BigDecimal.valueOf(5000), bankingAPIService.getAccountBalance("0987654321"));
        }

        @Test
        @DisplayName("Should return default balance for new accounts")
        void testGetAccountBalance_NewAccount() {
            BigDecimal balance = bankingAPIService.getAccountBalance("5555555555");
            assertEquals(BigDecimal.valueOf(10000), balance);
        }
    }

    @Test
    @DisplayName("Should reset balances correctly")
    void testResetBalances() {
        // Make some transfers
        bankingAPIService.transferFunds("1234567890", "0987654321", BigDecimal.valueOf(1000));

        // Reset
        bankingAPIService.resetBalances();

        // Verify balances are reset
        assertEquals(BigDecimal.valueOf(10000), bankingAPIService.getAccountBalance("1234567890"));
        assertEquals(BigDecimal.valueOf(5000), bankingAPIService.getAccountBalance("0987654321"));
    }
}
