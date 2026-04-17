package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.service.shared.PaymentValidationService;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Temporal activity for payment input validation. Business failures (invalid account,
 * insufficient funds) are translated into non-retryable {@link ApplicationFailure}s so
 * Temporal fails the workflow fast instead of burning the full retry budget on a
 * deterministic "no" answer.
 */
@Service
public class PaymentValidationActivitiesImpl implements PaymentValidationActivities {

    static final String ERR_INVALID_ACCOUNT = "InvalidAccount";
    static final String ERR_INSUFFICIENT_FUNDS = "InsufficientFunds";
    static final String ERR_VALIDATION = "ValidationError";

    private final PaymentValidationService validationService;

    public PaymentValidationActivitiesImpl(PaymentValidationService validationService) {
        this.validationService = validationService;
    }

    @Override
    public void validateAccounts(String sourceAccount, String destinationAccount) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            try {
                validationService.validateAccounts(sourceAccount, destinationAccount);
            } catch (InvalidAccountException e) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), ERR_INVALID_ACCOUNT);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), ERR_VALIDATION);
            }
        }
    }

    @Override
    public void validateFunds(String sourceAccount, BigDecimal amount) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            try {
                validationService.validateSufficientFunds(sourceAccount, amount);
            } catch (InsufficientFundsException e) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), ERR_INSUFFICIENT_FUNDS);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), ERR_VALIDATION);
            }
        }
    }
}
