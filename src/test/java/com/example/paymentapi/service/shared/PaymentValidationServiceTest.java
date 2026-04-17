package com.example.paymentapi.service.shared;

import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.service.BankingAPIService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentValidationServiceTest {

    @Mock private BankingAPIService bankingAPIService;
    private PaymentValidationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentValidationService(bankingAPIService, new PaymentMapper());
    }

    @Test
    void validateAccounts_happyPath_doesNotThrow() {
        when(bankingAPIService.validateAccount("src")).thenReturn(true);
        when(bankingAPIService.validateAccount("dst")).thenReturn(true);
        assertDoesNotThrow(() -> service.validateAccounts("src", "dst"));
    }

    @Test
    void validateAccounts_invalidSource_throwsInvalidAccount() {
        when(bankingAPIService.validateAccount("src")).thenReturn(false);
        InvalidAccountException ex = assertThrows(InvalidAccountException.class,
                () -> service.validateAccounts("src", "dst"));
        assertTrue(ex.getMessage().contains("source"));
    }

    @Test
    void validateAccounts_invalidDestination_throwsInvalidAccount() {
        when(bankingAPIService.validateAccount("src")).thenReturn(true);
        when(bankingAPIService.validateAccount("dst")).thenReturn(false);
        InvalidAccountException ex = assertThrows(InvalidAccountException.class,
                () -> service.validateAccounts("src", "dst"));
        assertTrue(ex.getMessage().contains("destination"));
    }

    @Test
    void validateAccounts_selfTransfer_throwsInvalidAccount() {
        when(bankingAPIService.validateAccount("same")).thenReturn(true);
        InvalidAccountException ex = assertThrows(InvalidAccountException.class,
                () -> service.validateAccounts("same", "same"));
        assertTrue(ex.getMessage().toLowerCase().contains("same"));
    }

    @Test
    void validateSufficientFunds_insufficient_throws() {
        when(bankingAPIService.hasSufficientFunds("src", new BigDecimal("100"))).thenReturn(false);
        assertThrows(InsufficientFundsException.class,
                () -> service.validateSufficientFunds("src", new BigDecimal("100")));
    }

    @Test
    void validateSufficientFunds_sufficient_doesNotThrow() {
        when(bankingAPIService.hasSufficientFunds("src", new BigDecimal("100"))).thenReturn(true);
        assertDoesNotThrow(() -> service.validateSufficientFunds("src", new BigDecimal("100")));
    }
}
