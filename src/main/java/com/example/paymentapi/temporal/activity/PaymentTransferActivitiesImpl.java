package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.service.BankingAPIService;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentTransferActivitiesImpl implements PaymentTransferActivities {

    private static final Logger logger = LoggerFactory.getLogger(PaymentTransferActivitiesImpl.class);

    static final String ERR_TRANSFER = "TransferError";
    static final String ERR_INVALID_ACCOUNT = "InvalidAccount";
    static final String ERR_INSUFFICIENT_FUNDS = "InsufficientFunds";

    private final BankingAPIService bankingAPIService;

    public PaymentTransferActivitiesImpl(BankingAPIService bankingAPIService) {
        this.bankingAPIService = bankingAPIService;
    }

    @Override
    public void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount) {
        try (ActivityMdcSupport.Scope ignored = ActivityMdcSupport.open()) {
            heartbeatIfAvailable("pre-transfer");
            try {
                bankingAPIService.transferFunds(sourceAccount, destinationAccount, amount);
                heartbeatIfAvailable("post-transfer");
            } catch (InvalidAccountException e) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), ERR_INVALID_ACCOUNT);
            } catch (InsufficientFundsException e) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), ERR_INSUFFICIENT_FUNDS);
            } catch (RuntimeException e) {
                // Transient banking/network failures — let Temporal retry per the configured policy.
                logger.warn("Transfer attempt failed (will retry if budget remains): {}", e.getMessage());
                throw ApplicationFailure.newFailure(
                        e.getMessage() == null ? "Transfer failed" : e.getMessage(), ERR_TRANSFER);
            }
        }
    }

    private void heartbeatIfAvailable(String phase) {
        try {
            ActivityInfo info = Activity.getExecutionContext().getInfo();
            Activity.getExecutionContext().heartbeat(phase + ":" + info.getAttempt());
        } catch (Exception ignored) {
            // No activity context (unit test) — skip.
        }
    }
}
