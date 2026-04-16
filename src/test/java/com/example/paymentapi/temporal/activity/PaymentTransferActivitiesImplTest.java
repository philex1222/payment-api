package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.BankingAPIService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentTransferActivitiesImplTest {

    @Mock
    private BankingAPIService bankingAPIService;

    @InjectMocks
    private PaymentTransferActivitiesImpl activities;

    @Test
    void transferFunds_delegatesToBankingService() {
        activities.transferFunds("src", "dst", BigDecimal.valueOf(50));
        verify(bankingAPIService).transferFunds("src", "dst", BigDecimal.valueOf(50));
    }

    @Test
    void transferFunds_propagatesExceptionFromBankingService() {
        doThrow(new RuntimeException("Banking API unavailable")).when(bankingAPIService)
                .transferFunds(anyString(), anyString(), any(BigDecimal.class));
        assertThrows(RuntimeException.class,
                () -> activities.transferFunds("src", "dst", BigDecimal.valueOf(100)));
    }

    @Test
    void transferFunds_largeAmount_delegatesCorrectly() {
        BigDecimal large = new BigDecimal("9999.99");
        activities.transferFunds("acc1", "acc2", large);
        verify(bankingAPIService).transferFunds("acc1", "acc2", large);
    }
}
