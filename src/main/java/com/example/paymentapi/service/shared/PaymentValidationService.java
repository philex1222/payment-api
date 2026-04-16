package com.example.paymentapi.service.shared;

import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.service.BankingAPIService;
import com.example.paymentapi.util.PaymentConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Centralises all payment input validation previously duplicated across
 * PaymentServiceImpl and BankingAPIServiceImpl.
 */
@Component
public class PaymentValidationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentValidationService.class);

    private final BankingAPIService bankingAPIService;
    private final PaymentMapper mapper;

    public PaymentValidationService(BankingAPIService bankingAPIService, PaymentMapper mapper) {
        this.bankingAPIService = bankingAPIService;
        this.mapper = mapper;
    }

    public void validateAccounts(String sourceAccount, String destinationAccount)
            throws InvalidAccountException {
        if (!bankingAPIService.validateAccount(sourceAccount)) {
            logger.warn("Invalid source account: {}", mapper.maskAccount(sourceAccount));
            throw new InvalidAccountException(PaymentConstants.ERR_INVALID_SOURCE);
        }
        if (!bankingAPIService.validateAccount(destinationAccount)) {
            logger.warn("Invalid destination account: {}", mapper.maskAccount(destinationAccount));
            throw new InvalidAccountException(PaymentConstants.ERR_INVALID_DESTINATION);
        }
        if (sourceAccount.equals(destinationAccount)) {
            logger.warn("Attempted self-transfer on account: {}", mapper.maskAccount(sourceAccount));
            throw new InvalidAccountException(PaymentConstants.ERR_SELF_TRANSFER);
        }
    }

    public void validateSufficientFunds(String sourceAccount, BigDecimal amount)
            throws InsufficientFundsException {
        if (!bankingAPIService.hasSufficientFunds(sourceAccount, amount)) {
            logger.warn("Insufficient funds in account: {}", mapper.maskAccount(sourceAccount));
            throw new InsufficientFundsException(PaymentConstants.ERR_INSUFFICIENT_FUNDS);
        }
    }
}
