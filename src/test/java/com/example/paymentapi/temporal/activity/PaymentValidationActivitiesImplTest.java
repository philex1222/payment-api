package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.shared.PaymentValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentValidationActivitiesImplTest {

    @Mock
    private PaymentValidationService validationService;

    @InjectMocks
    private PaymentValidationActivitiesImpl activities;

    @Test
    void validateAccounts_delegatesToValidationService() {
        activities.validateAccounts("1234567890", "0987654321");
        verify(validationService).validateAccounts("1234567890", "0987654321");
    }

    @Test
    void validateAccounts_propagatesExceptionFromService() {
        doThrow(new IllegalArgumentException("Same account")).when(validationService)
                .validateAccounts("same", "same");
        assertThrows(IllegalArgumentException.class,
                () -> activities.validateAccounts("same", "same"));
    }

    @Test
    void validateFunds_delegatesToValidationService() {
        BigDecimal amount = BigDecimal.valueOf(100);
        activities.validateFunds("1234567890", amount);
        verify(validationService).validateSufficientFunds("1234567890", amount);
    }

    @Test
    void validateFunds_propagatesExceptionFromService() {
        BigDecimal amount = BigDecimal.valueOf(999999);
        doThrow(new IllegalStateException("Insufficient funds")).when(validationService)
                .validateSufficientFunds("1234567890", amount);
        assertThrows(IllegalStateException.class,
                () -> activities.validateFunds("1234567890", amount));
    }

    @Test
    void validateFunds_zeroAmount_delegatesWithoutError() {
        BigDecimal zero = BigDecimal.ZERO;
        assertDoesNotThrow(() -> activities.validateFunds("1234567890", zero));
        verify(validationService).validateSufficientFunds("1234567890", zero);
    }
}
