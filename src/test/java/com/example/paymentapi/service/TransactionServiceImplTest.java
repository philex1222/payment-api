package com.example.paymentapi.service;

import com.example.paymentapi.exception.TransactionNotFoundException;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionServiceImpl Tests")
class TransactionServiceImplTest {

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionServiceImpl transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(transactionRepository);
    }

    private Transaction savedTransaction(String id, String paymentId, String status) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setPaymentId(paymentId);
        t.setStatus(status);
        return t;
    }

    @Test
    @DisplayName("createTransaction persists a PENDING transaction for the given payment")
    void createTransaction_persistsPendingTransaction() {
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> {
                    Transaction t = inv.getArgument(0);
                    t.setId("tx-001");
                    return t;
                });

        Transaction result = transactionService.createTransaction("payment-001");

        assertNotNull(result);
        assertEquals("payment-001", result.getPaymentId());
        assertEquals("PENDING", result.getStatus());
    }

    @Test
    @DisplayName("getTransactionById returns transaction when found")
    void getTransactionById_found() {
        Transaction tx = savedTransaction("tx-001", "p-001", "PENDING");
        when(transactionRepository.findById("tx-001")).thenReturn(Optional.of(tx));

        Transaction result = transactionService.getTransactionById("tx-001");

        assertEquals("tx-001", result.getId());
    }

    @Test
    @DisplayName("getTransactionById throws TransactionNotFoundException when not found")
    void getTransactionById_notFound() {
        when(transactionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(TransactionNotFoundException.class,
                () -> transactionService.getTransactionById("missing"));
    }

    @Test
    @DisplayName("updateTransactionStatus updates and saves the status")
    void updateTransactionStatus_updatesStatus() {
        Transaction tx = savedTransaction("tx-001", "p-001", "PENDING");
        when(transactionRepository.findById("tx-001")).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.updateTransactionStatus("tx-001", "SUCCESS");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("updateTransactionFailureReason saves the failure reason")
    void updateTransactionFailureReason_savesReason() {
        Transaction tx = savedTransaction("tx-001", "p-001", "PENDING");
        when(transactionRepository.findById("tx-001")).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.updateTransactionFailureReason("tx-001", "Network timeout");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertEquals("Network timeout", captor.getValue().getFailureReason());
    }

    @Test
    @DisplayName("updateTransactionStatus throws when transaction not found")
    void updateTransactionStatus_notFound() {
        when(transactionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(TransactionNotFoundException.class,
                () -> transactionService.updateTransactionStatus("missing", "SUCCESS"));
    }

    @Test
    @DisplayName("getTransactionsByPaymentId returns all transactions for the payment")
    void getTransactionsByPaymentId_returnsAll() {
        Transaction tx1 = savedTransaction("tx-001", "p-001", "SUCCESS");
        Transaction tx2 = savedTransaction("tx-002", "p-001", "FAILED");
        when(transactionRepository.findByPaymentId("p-001")).thenReturn(List.of(tx1, tx2));

        List<Transaction> result = transactionService.getTransactionsByPaymentId("p-001");

        assertEquals(2, result.size());
        assertEquals("tx-001", result.get(0).getId());
        assertEquals("tx-002", result.get(1).getId());
    }

    @Test
    @DisplayName("getTransactionsByPaymentId returns empty list when no transactions exist")
    void getTransactionsByPaymentId_empty() {
        when(transactionRepository.findByPaymentId("p-none")).thenReturn(List.of());

        List<Transaction> result = transactionService.getTransactionsByPaymentId("p-none");

        assertTrue(result.isEmpty());
    }
}
