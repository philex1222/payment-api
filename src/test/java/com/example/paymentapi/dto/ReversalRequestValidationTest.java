package com.example.paymentapi.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Bean Validation constraints on ReversalRequest.
 */
@DisplayName("ReversalRequest Validation Tests")
class ReversalRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private ReversalRequest validFullReversal() {
        ReversalRequest r = new ReversalRequest();
        r.setReason("Customer requested a full refund");
        return r;
    }

    @Nested
    @DisplayName("Reason Validation")
    class ReasonValidation {

        @Test
        @DisplayName("Valid reason (10-500 chars) passes")
        void valid_reason_passes() {
            assertThat(validator.validate(validFullReversal())).isEmpty();
        }

        @Test
        @DisplayName("Null reason is rejected")
        void null_reason_isRejected() {
            ReversalRequest r = validFullReversal();
            r.setReason(null);
            Set<ConstraintViolation<ReversalRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("reason");
        }

        @Test
        @DisplayName("Reason shorter than 10 characters is rejected")
        void short_reason_isRejected() {
            ReversalRequest r = validFullReversal();
            r.setReason("short");
            Set<ConstraintViolation<ReversalRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("reason");
        }

        @Test
        @DisplayName("Reason longer than 500 characters is rejected")
        void long_reason_isRejected() {
            ReversalRequest r = validFullReversal();
            r.setReason("x".repeat(501));
            Set<ConstraintViolation<ReversalRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("reason");
        }
    }

    @Nested
    @DisplayName("Reversal Amount Validation")
    class ReversalAmountValidation {

        @Test
        @DisplayName("No reversalAmount on full reversal passes")
        void full_reversal_noAmount_passes() {
            ReversalRequest r = validFullReversal();
            r.setPartialReversal(false);
            assertThat(validator.validate(r)).isEmpty();
        }

        @Test
        @DisplayName("Positive reversalAmount on partial reversal passes")
        void partial_reversal_positiveAmount_passes() {
            ReversalRequest r = validFullReversal();
            r.setPartialReversal(true);
            r.setReversalAmount(BigDecimal.valueOf(50.00));
            assertThat(validator.validate(r)).isEmpty();
        }

        @Test
        @DisplayName("Zero reversalAmount is rejected")
        void zero_reversalAmount_isRejected() {
            ReversalRequest r = validFullReversal();
            r.setPartialReversal(true);
            r.setReversalAmount(BigDecimal.ZERO);
            Set<ConstraintViolation<ReversalRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("reversalAmount");
        }

        @Test
        @DisplayName("Negative reversalAmount is rejected")
        void negative_reversalAmount_isRejected() {
            ReversalRequest r = validFullReversal();
            r.setPartialReversal(true);
            r.setReversalAmount(BigDecimal.valueOf(-10));
            Set<ConstraintViolation<ReversalRequest>> violations = validator.validate(r);
            assertThat(violations)
                    .extracting(cv -> cv.getPropertyPath().toString())
                    .contains("reversalAmount");
        }
    }
}
