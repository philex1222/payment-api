package com.example.paymentapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CurrencyConversionService Tests")
class CurrencyConversionServiceTest {

    private CurrencyConversionService currencyConversionService;

    @BeforeEach
    void setUp() {
        currencyConversionService = new CurrencyConversionService();
    }

    @Nested
    @DisplayName("Currency Conversion Tests")
    class ConversionTests {

        @Test
        @DisplayName("Should convert USD to EUR correctly")
        void testConvert_USDtoEUR() {
            BigDecimal amount = BigDecimal.valueOf(100);
            BigDecimal result = currencyConversionService.convert("USD", "EUR", amount);

            assertEquals(BigDecimal.valueOf(85).setScale(4, RoundingMode.HALF_UP),
                    result.setScale(4, RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("Should convert EUR to USD correctly")
        void testConvert_EURtoUSD() {
            BigDecimal amount = BigDecimal.valueOf(100);
            BigDecimal result = currencyConversionService.convert("EUR", "USD", amount);

            // EUR rate is 0.85, so 100 EUR = 100 / 0.85 * 1 ≈ 117.65 USD
            assertTrue(result.compareTo(BigDecimal.valueOf(117)) > 0);
            assertTrue(result.compareTo(BigDecimal.valueOf(118)) < 0);
        }

        @Test
        @DisplayName("Should return same amount for same currency")
        void testConvert_SameCurrency() {
            BigDecimal amount = BigDecimal.valueOf(100);
            BigDecimal result = currencyConversionService.convert("USD", "USD", amount);

            assertEquals(amount, result);
        }

        @ParameterizedTest
        @CsvSource({
                "USD, GBP, 100",
                "GBP, USD, 100",
                "EUR, GBP, 100",
                "USD, JPY, 1000",
                "JPY, USD, 10000"
        })
        @DisplayName("Should convert between all supported currencies")
        void testConvert_SupportedCurrencies(String source, String target, String amount) {
            BigDecimal amountDecimal = new BigDecimal(amount);
            BigDecimal result = currencyConversionService.convert(source, target, amountDecimal);

            assertNotNull(result);
            assertTrue(result.compareTo(BigDecimal.ZERO) > 0);
        }

        @Test
        @DisplayName("Should throw exception for unsupported source currency")
        void testConvert_UnsupportedSourceCurrency() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.convert("XYZ", "USD", BigDecimal.valueOf(100)));
        }

        @Test
        @DisplayName("Should throw exception for unsupported target currency")
        void testConvert_UnsupportedTargetCurrency() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.convert("USD", "XYZ", BigDecimal.valueOf(100)));
        }

        @Test
        @DisplayName("Should throw exception for null source currency")
        void testConvert_NullSourceCurrency() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.convert(null, "USD", BigDecimal.valueOf(100)));
        }

        @Test
        @DisplayName("Should throw exception for null target currency")
        void testConvert_NullTargetCurrency() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.convert("USD", null, BigDecimal.valueOf(100)));
        }

        @Test
        @DisplayName("Should throw exception for null amount")
        void testConvert_NullAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.convert("USD", "EUR", null));
        }

        @Test
        @DisplayName("Should throw exception for negative amount")
        void testConvert_NegativeAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.convert("USD", "EUR", BigDecimal.valueOf(-100)));
        }

        @Test
        @DisplayName("Should handle zero amount")
        void testConvert_ZeroAmount() {
            BigDecimal result = currencyConversionService.convert("USD", "EUR", BigDecimal.ZERO);
            assertEquals(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP), result);
        }

        @Test
        @DisplayName("Should handle case-insensitive currency codes")
        void testConvert_CaseInsensitive() {
            BigDecimal result1 = currencyConversionService.convert("usd", "eur", BigDecimal.valueOf(100));
            BigDecimal result2 = currencyConversionService.convert("USD", "EUR", BigDecimal.valueOf(100));

            assertEquals(result1, result2);
        }

        @Test
        @DisplayName("Should handle large amounts")
        void testConvert_LargeAmount() {
            BigDecimal amount = new BigDecimal("1000000000");
            BigDecimal result = currencyConversionService.convert("USD", "EUR", amount);

            assertNotNull(result);
            assertTrue(result.compareTo(BigDecimal.ZERO) > 0);
        }

        @Test
        @DisplayName("Should handle small amounts with precision")
        void testConvert_SmallAmount() {
            BigDecimal amount = new BigDecimal("0.01");
            BigDecimal result = currencyConversionService.convert("USD", "EUR", amount);

            assertNotNull(result);
            assertTrue(result.compareTo(BigDecimal.ZERO) > 0);
        }
    }

    @Nested
    @DisplayName("Exchange Rate Tests")
    class ExchangeRateTests {

        @Test
        @DisplayName("Should get exchange rate between USD and EUR")
        void testGetExchangeRate_USDtoEUR() {
            BigDecimal rate = currencyConversionService.getExchangeRate("USD", "EUR");

            assertEquals(BigDecimal.valueOf(0.85).setScale(6, RoundingMode.HALF_UP),
                    rate.setScale(6, RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("Should get exchange rate of 1 for same currency")
        void testGetExchangeRate_SameCurrency() {
            BigDecimal rate = currencyConversionService.getExchangeRate("USD", "USD");

            assertEquals(BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP),
                    rate.setScale(6, RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("Should throw exception for unsupported currency pair")
        void testGetExchangeRate_Unsupported() {
            assertThrows(IllegalArgumentException.class,
                    () -> currencyConversionService.getExchangeRate("XYZ", "USD"));
        }
    }

    @Nested
    @DisplayName("Supported Currencies Tests")
    class SupportedCurrenciesTests {

        @Test
        @DisplayName("Should return set of supported currencies")
        void testGetSupportedCurrencies() {
            var currencies = currencyConversionService.getSupportedCurrencies();

            assertNotNull(currencies);
            assertTrue(currencies.contains("USD"));
            assertTrue(currencies.contains("EUR"));
            assertTrue(currencies.contains("GBP"));
            assertTrue(currencies.contains("JPY"));
        }

        @Test
        @DisplayName("Should return true for supported currency")
        void testIsCurrencySupported_Supported() {
            assertTrue(currencyConversionService.isCurrencySupported("USD"));
            assertTrue(currencyConversionService.isCurrencySupported("eur"));
        }

        @Test
        @DisplayName("Should return false for unsupported currency")
        void testIsCurrencySupported_Unsupported() {
            assertFalse(currencyConversionService.isCurrencySupported("XYZ"));
        }

        @Test
        @DisplayName("Should return false for null currency")
        void testIsCurrencySupported_Null() {
            assertFalse(currencyConversionService.isCurrencySupported(null));
        }
    }
}
