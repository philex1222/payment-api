package com.example.paymentapi.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Bean Validation constraints on PaymentRequest.
 *
 * These tests run without Spring context — they exercise the JSR-380
 * annotations directly via the reference implementation (Hibernate Validator).
 */
@DisplayName("PaymentRequest Validation Tests")
class PaymentRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private PaymentRequest validRequest() {
        PaymentRequest r = new PaymentRequest();
        r.setSourceAccount("1234567890");
        r.setDestinationAccount("0987654321");
        r.setAmount(BigDecimal.valueOf(100.00));
        r.setCurrency("USD");
        return r;
    }

    @Nested
    @DisplayName("Source Account Validation")
    class SourceAccountValidation {

        @Test
        @DisplayName("Valid 10-digit source account passes")
        void valid_sourceAccount_passes() {
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(validRequest());
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"123456789", "12345678901", "abcdefghij", "1234 56789", "123456789a"})
        @DisplayName("Invalid source account formats are rejected")
        void invalid_sourceAccount_isRejected(String account) {
            PaymentRequest r = validRequest();
            r.setSourceAccount(account);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("sourceAccount");
        }

        @Test
        @DisplayName("Null source account is rejected")
        void null_sourceAccount_isRejected() {
            PaymentRequest r = validRequest();
            r.setSourceAccount(null);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("sourceAccount");
        }

        @Test
        @DisplayName("Blank source account is rejected")
        void blank_sourceAccount_isRejected() {
            PaymentRequest r = validRequest();
            r.setSourceAccount("   ");
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("sourceAccount");
        }
    }

    @Nested
    @DisplayName("Destination Account Validation")
    class DestinationAccountValidation {

        @ParameterizedTest
        @ValueSource(strings = {"123456789", "12345678901", "abcdefghij", "0000 00000"})
        @DisplayName("Invalid destination account formats are rejected")
        void invalid_destinationAccount_isRejected(String account) {
            PaymentRequest r = validRequest();
            r.setDestinationAccount(account);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("destinationAccount");
        }
    }

    @Nested
    @DisplayName("Amount Validation")
    class AmountValidation {

        @Test
        @DisplayName("Positive amount passes")
        void positive_amount_passes() {
            PaymentRequest r = validRequest();
            r.setAmount(BigDecimal.valueOf(0.01));
            assertThat(validator.validate(r)).isEmpty();
        }

        @Test
        @DisplayName("Zero amount is rejected")
        void zero_amount_isRejected() {
            PaymentRequest r = validRequest();
            r.setAmount(BigDecimal.ZERO);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("amount");
        }

        @Test
        @DisplayName("Negative amount is rejected")
        void negative_amount_isRejected() {
            PaymentRequest r = validRequest();
            r.setAmount(BigDecimal.valueOf(-1));
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("amount");
        }

        @Test
        @DisplayName("Null amount is rejected")
        void null_amount_isRejected() {
            PaymentRequest r = validRequest();
            r.setAmount(null);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("amount");
        }
    }

    @Nested
    @DisplayName("Currency Validation")
    class CurrencyValidation {

        @ParameterizedTest
        @ValueSource(strings = {"USD", "EUR", "GBP", "JPY", "CNY"})
        @DisplayName("Valid 3-letter uppercase ISO codes pass")
        void valid_currency_passes(String currency) {
            PaymentRequest r = validRequest();
            r.setCurrency(currency);
            assertThat(validator.validate(r)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"usd", "Us", "USDD", "123", "U S"})
        @DisplayName("Invalid currency codes are rejected")
        void invalid_currency_isRejected(String currency) {
            PaymentRequest r = validRequest();
            r.setCurrency(currency);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("currency");
        }

        @Test
        @DisplayName("Null currency is rejected")
        void null_currency_isRejected() {
            PaymentRequest r = validRequest();
            r.setCurrency(null);
            Set<ConstraintViolation<PaymentRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("currency");
        }
    }
}
