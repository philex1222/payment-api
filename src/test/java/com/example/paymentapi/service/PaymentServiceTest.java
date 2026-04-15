package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.metrics.PaymentMetrics;
import io.micrometer.core.instrument.Timer;
import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.exception.InvalidStatusTransitionException;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.exception.PaymentReversalException;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Tests")
public class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private TransactionService transactionService;
    @Mock
    private AuditService auditService;
    @Mock
    private BankingAPIService bankingAPIService;
    @Mock
    private CurrencyConversionService currencyConversionService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PaymentMetrics paymentMetrics;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        // Populate the security context so ownership checks pass (admin bypasses all checks)
        var adminAuth = new UsernamePasswordAuthenticationToken(
                "testuser", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(adminAuth);

        // lenient: startTimer() is only exercised by createPayment tests, not all test cases
        lenient().when(paymentMetrics.startTimer()).thenReturn(Timer.start());
        paymentService = new PaymentServiceImpl(
                paymentRepository,
                transactionService,
                auditService,
                bankingAPIService,
                currencyConversionService,
                notificationService,
                paymentMetrics,
                applicationEventPublisher
        );
        // @Value fields are not injected in plain Mockito tests — set them via reflection
        ReflectionTestUtils.setField(paymentService, "maxRetryAttempts", 3);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PaymentRequest createValidPaymentRequest() {
        PaymentRequest request = new PaymentRequest();
        request.setSourceAccount("1234567890");
        request.setDestinationAccount("0987654321");
        request.setAmount(BigDecimal.valueOf(100));
        request.setCurrency("USD");
        return request;
    }

    private Payment createPayment(String id, String status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setSourceAccount("1234567890");
        payment.setDestinationAccount("0987654321");
        payment.setAmount(BigDecimal.valueOf(100));
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setCreatedAt(LocalDateTime.now());
        payment.setUpdatedAt(LocalDateTime.now());
        return payment;
    }

    @Nested
    @DisplayName("Create Payment Tests")
    class CreatePaymentTests {

        @Test
        @DisplayName("Should create payment successfully with valid request")
        void testCreatePayment_Success() {
            PaymentRequest request = createValidPaymentRequest();
            Payment payment = createPayment("payment123", "COMPLETED");
            Transaction transaction = new Transaction();
            transaction.setId("trans123");

            when(bankingAPIService.validateAccount(request.getSourceAccount())).thenReturn(true);
            when(bankingAPIService.validateAccount(request.getDestinationAccount())).thenReturn(true);
            when(bankingAPIService.hasSufficientFunds(request.getSourceAccount(), request.getAmount())).thenReturn(true);
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            when(transactionService.createTransaction(payment.getId())).thenReturn(transaction);

            PaymentResponse response = paymentService.createPayment(request);

            assertNotNull(response);
            assertEquals("payment123", response.getId());
            assertEquals("COMPLETED", response.getStatus());
            assertNotNull(response.getTransactionId());

            verify(paymentRepository, times(2)).save(any(Payment.class));
            verify(transactionService).createTransaction(payment.getId());
            verify(auditService, times(2)).logPaymentEvent(eq(payment.getId()), anyString());
            verify(bankingAPIService).transferFunds(
                    request.getSourceAccount(),
                    request.getDestinationAccount(),
                    request.getAmount()
            );
            verify(notificationService).sendPaymentNotification(anyString(), anyString());
        }

        @Test
        @DisplayName("Should throw InvalidAccountException for invalid source account")
        void testCreatePayment_InvalidSourceAccount() {
            PaymentRequest request = createValidPaymentRequest();

            when(bankingAPIService.validateAccount(request.getSourceAccount())).thenReturn(false);

            InvalidAccountException exception = assertThrows(InvalidAccountException.class,
                    () -> paymentService.createPayment(request));

            assertTrue(exception.getMessage().contains("source account"));
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw InvalidAccountException for invalid destination account")
        void testCreatePayment_InvalidDestinationAccount() {
            PaymentRequest request = createValidPaymentRequest();

            when(bankingAPIService.validateAccount(request.getSourceAccount())).thenReturn(true);
            when(bankingAPIService.validateAccount(request.getDestinationAccount())).thenReturn(false);

            InvalidAccountException exception = assertThrows(InvalidAccountException.class,
                    () -> paymentService.createPayment(request));

            assertTrue(exception.getMessage().contains("destination account"));
        }

        @Test
        @DisplayName("Should throw InvalidAccountException for self-transfer")
        void testCreatePayment_SelfTransfer() {
            PaymentRequest request = createValidPaymentRequest();
            request.setDestinationAccount(request.getSourceAccount());

            when(bankingAPIService.validateAccount(request.getSourceAccount())).thenReturn(true);

            InvalidAccountException exception = assertThrows(InvalidAccountException.class,
                    () -> paymentService.createPayment(request));

            assertTrue(exception.getMessage().contains("cannot be the same"));
        }

        @Test
        @DisplayName("Should throw InsufficientFundsException when balance is low")
        void testCreatePayment_InsufficientFunds() {
            PaymentRequest request = createValidPaymentRequest();

            when(bankingAPIService.validateAccount(request.getSourceAccount())).thenReturn(true);
            when(bankingAPIService.validateAccount(request.getDestinationAccount())).thenReturn(true);
            when(bankingAPIService.hasSufficientFunds(request.getSourceAccount(), request.getAmount())).thenReturn(false);

            InsufficientFundsException exception = assertThrows(InsufficientFundsException.class,
                    () -> paymentService.createPayment(request));

            assertTrue(exception.getMessage().contains("Insufficient funds"));
        }

        @ParameterizedTest
        @CsvSource({
                "100.00, USD",
                "500.50, EUR",
                "1000.00, GBP",
                "0.01, USD"
        })
        @DisplayName("Should handle different amounts and currencies")
        void testCreatePayment_DifferentAmounts(String amount, String currency) {
            PaymentRequest request = createValidPaymentRequest();
            request.setAmount(new BigDecimal(amount));
            request.setCurrency(currency);

            Payment payment = createPayment("payment123", "COMPLETED");
            Transaction transaction = new Transaction();
            transaction.setId("trans123");

            when(bankingAPIService.validateAccount(anyString())).thenReturn(true);
            when(bankingAPIService.hasSufficientFunds(anyString(), any())).thenReturn(true);
            when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
            when(transactionService.createTransaction(anyString())).thenReturn(transaction);

            PaymentResponse response = paymentService.createPayment(request);

            assertNotNull(response);
            assertEquals("COMPLETED", response.getStatus());
        }
    }

    @Nested
    @DisplayName("Get Payment Tests")
    class GetPaymentTests {

        @Test
        @DisplayName("Should return payment when found")
        void testGetPaymentById_Found() {
            Payment payment = createPayment("payment123", "COMPLETED");
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            PaymentResponse response = paymentService.getPaymentById("payment123");

            assertNotNull(response);
            assertEquals("payment123", response.getId());
            assertEquals("COMPLETED", response.getStatus());
        }

        @Test
        @DisplayName("Should throw PaymentNotFoundException when not found")
        void testGetPaymentById_NotFound() {
            when(paymentRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.getPaymentById("nonexistent"));
        }

        @Test
        @SuppressWarnings("unchecked") // Specification<Payment> raw type required by Mockito any() matcher
        @DisplayName("Should return paginated payments")
        void testGetPayments_Paginated() {
            List<Payment> payments = Arrays.asList(
                    createPayment("p1", "COMPLETED"),
                    createPayment("p2", "PENDING")
            );
            Page<Payment> page = new PageImpl<>(payments);
            Pageable pageable = PageRequest.of(0, 10);

            when(paymentRepository.findAll(any(Specification.class), eq(pageable)))
                    .thenReturn(page);

            Page<PaymentResponse> result = paymentService.getPayments(null, null, null, null, null, null, pageable);

            assertNotNull(result);
            assertEquals(2, result.getTotalElements());
        }

        @Test
        @SuppressWarnings("unchecked") // Specification<Payment> raw type required by Mockito any() matcher
        @DisplayName("Should return empty page when no payments")
        void testGetPayments_Empty() {
            Page<Payment> emptyPage = new PageImpl<>(Collections.emptyList());
            Pageable pageable = PageRequest.of(0, 10);

            when(paymentRepository.findAll(any(Specification.class), eq(pageable)))
                    .thenReturn(emptyPage);

            Page<PaymentResponse> result = paymentService.getPayments(null, null, null, null, null, null, pageable);

            assertNotNull(result);
            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("Should return payments by source account")
        void testGetPaymentsBySourceAccount() {
            List<Payment> payments = Arrays.asList(
                    createPayment("p1", "COMPLETED"),
                    createPayment("p2", "COMPLETED")
            );
            when(paymentRepository.findBySourceAccount("1234567890")).thenReturn(payments);

            List<PaymentResponse> result = paymentService.getPaymentsBySourceAccount("1234567890");

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Should return payments by destination account")
        void testGetPaymentsByDestinationAccount() {
            List<Payment> payments = Collections.singletonList(createPayment("p1", "COMPLETED"));
            when(paymentRepository.findByDestinationAccount("0987654321")).thenReturn(payments);

            List<PaymentResponse> result = paymentService.getPaymentsByDestinationAccount("0987654321");

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("Update Payment Status Tests")
    class UpdatePaymentStatusTests {

        @Test
        @DisplayName("Should update status from PENDING to PROCESSING")
        void testUpdatePaymentStatus_PendingToProcessing() {
            Payment payment = createPayment("payment123", "PENDING");
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            PaymentResponse response = paymentService.updatePaymentStatus("payment123", "PROCESSING");

            assertEquals("PROCESSING", response.getStatus());
            verify(auditService).logPaymentEvent(eq("payment123"), contains("PAYMENT_STATUS_UPDATED"));
        }

        @Test
        @DisplayName("Should update status from PROCESSING to COMPLETED")
        void testUpdatePaymentStatus_ProcessingToCompleted() {
            Payment payment = createPayment("payment123", "PROCESSING");
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            PaymentResponse response = paymentService.updatePaymentStatus("payment123", "COMPLETED");

            assertEquals("COMPLETED", response.getStatus());
        }

        @Test
        @DisplayName("Should throw InvalidStatusTransitionException for invalid transition")
        void testUpdatePaymentStatus_InvalidTransition() {
            Payment payment = createPayment("payment123", "COMPLETED");
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            assertThrows(InvalidStatusTransitionException.class,
                    () -> paymentService.updatePaymentStatus("payment123", "PENDING"));
        }

        @Test
        @DisplayName("Should throw PaymentNotFoundException for non-existent payment")
        void testUpdatePaymentStatus_PaymentNotFound() {
            when(paymentRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.updatePaymentStatus("nonexistent", "COMPLETED"));
        }

        @ParameterizedTest
        @CsvSource({
                "PENDING, CANCELLED",
                "PENDING, FAILED",
                "FAILED, PENDING",
                "COMPLETED, REVERSED",
                "COMPLETED, REFUNDED"
        })
        @DisplayName("Should allow valid status transitions")
        void testValidStatusTransitions(String currentStatus, String newStatus) {
            Payment payment = createPayment("payment123", currentStatus);
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            PaymentResponse response = paymentService.updatePaymentStatus("payment123", newStatus);

            assertEquals(newStatus, response.getStatus());
        }

        @ParameterizedTest
        @CsvSource({
                "COMPLETED, PENDING",
                "COMPLETED, PROCESSING",
                "REVERSED, COMPLETED",
                "CANCELLED, PENDING"
        })
        @DisplayName("Should reject invalid status transitions")
        void testInvalidStatusTransitions(String currentStatus, String newStatus) {
            Payment payment = createPayment("payment123", currentStatus);
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            assertThrows(InvalidStatusTransitionException.class,
                    () -> paymentService.updatePaymentStatus("payment123", newStatus));
        }
    }

    @Nested
    @DisplayName("Delete Payment Tests")
    class DeletePaymentTests {

        @Test
        @DisplayName("Should delete pending payment")
        void testDeletePayment_Pending() {
            Payment payment = createPayment("payment123", "PENDING");
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            paymentService.deletePayment("payment123");

            verify(paymentRepository).delete(payment);
            verify(auditService).logPaymentEvent("payment123", "PAYMENT_DELETED");
        }

        @Test
        @DisplayName("Should throw exception when deleting completed payment")
        void testDeletePayment_Completed() {
            Payment payment = createPayment("payment123", "COMPLETED");
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            assertThrows(IllegalStateException.class,
                    () -> paymentService.deletePayment("payment123"));

            verify(paymentRepository, never()).delete(any(Payment.class));
        }

        @Test
        @DisplayName("Should throw PaymentNotFoundException for non-existent payment")
        void testDeletePayment_NotFound() {
            when(paymentRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.deletePayment("nonexistent"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PENDING", "FAILED", "CANCELLED"})
        @DisplayName("Should allow deletion of non-completed payments")
        void testDeletePayment_AllowedStatuses(String status) {
            Payment payment = createPayment("payment123", status);
            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            assertDoesNotThrow(() -> paymentService.deletePayment("payment123"));
            verify(paymentRepository).delete(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("Payment Reversal Tests")
    class PaymentReversalTests {

        private ReversalRequest createReversalRequest() {
            ReversalRequest request = new ReversalRequest();
            request.setReason("Customer requested refund");
            return request;
        }

        @Test
        @DisplayName("Should reverse completed payment successfully")
        void testInitiatePaymentReversal_Success() {
            Payment payment = createPayment("payment123", "COMPLETED");
            ReversalRequest request = createReversalRequest();

            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            PaymentResponse response = paymentService.initiatePaymentReversal("payment123", request);

            assertNotNull(response);
            assertEquals("REVERSED", response.getStatus());
            verify(bankingAPIService).transferFunds(
                    payment.getDestinationAccount(),
                    payment.getSourceAccount(),
                    payment.getAmount()
            );
            verify(auditService).logPaymentEvent(eq("payment123"), contains("REVERSED"));
            verify(notificationService).sendPaymentNotification(anyString(), contains("reversal"));
            verify(paymentMetrics).incrementReversed();
            verify(paymentMetrics, never()).incrementRefunded();
        }

        @Test
        @DisplayName("Should throw exception when reversing non-completed payment")
        void testInitiatePaymentReversal_NotCompleted() {
            Payment payment = createPayment("payment123", "PENDING");
            ReversalRequest request = createReversalRequest();

            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            assertThrows(PaymentReversalException.class,
                    () -> paymentService.initiatePaymentReversal("payment123", request));
        }

        @Test
        @DisplayName("Should handle partial reversal")
        void testInitiatePaymentReversal_Partial() {
            Payment payment = createPayment("payment123", "COMPLETED");
            payment.setAmount(BigDecimal.valueOf(100));

            ReversalRequest request = new ReversalRequest();
            request.setReason("Partial refund requested");
            request.setPartialReversal(true);
            request.setReversalAmount(BigDecimal.valueOf(50));

            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

            PaymentResponse response = paymentService.initiatePaymentReversal("payment123", request);

            assertNotNull(response);
            assertEquals("REFUNDED", response.getStatus());
            verify(bankingAPIService).transferFunds(
                    payment.getDestinationAccount(),
                    payment.getSourceAccount(),
                    BigDecimal.valueOf(50)
            );
            verify(paymentMetrics).incrementRefunded();
            verify(paymentMetrics, never()).incrementReversed();
        }

        @Test
        @DisplayName("Should throw exception for partial reversal exceeding amount")
        void testInitiatePaymentReversal_ExceedingAmount() {
            Payment payment = createPayment("payment123", "COMPLETED");
            payment.setAmount(BigDecimal.valueOf(100));

            ReversalRequest request = new ReversalRequest();
            request.setReason("Partial refund");
            request.setPartialReversal(true);
            request.setReversalAmount(BigDecimal.valueOf(150));

            when(paymentRepository.findById("payment123")).thenReturn(Optional.of(payment));

            assertThrows(PaymentReversalException.class,
                    () -> paymentService.initiatePaymentReversal("payment123", request));
        }

        @Test
        @DisplayName("Should throw PaymentNotFoundException for non-existent payment")
        void testInitiatePaymentReversal_NotFound() {
            when(paymentRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.initiatePaymentReversal("nonexistent", createReversalRequest()));
        }
    }

    @Nested
    @DisplayName("RetryPayment Tests")
    class RetryPaymentTests {

        @Test
        @DisplayName("Should throw InvalidStatusTransitionException when payment is not FAILED")
        void retryPayment_notFailed_throws() {
            Payment payment = createPayment("pay-1", "PENDING");
            when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));

            assertThrows(InvalidStatusTransitionException.class,
                    () -> paymentService.retryPayment("pay-1"));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when max retry limit is reached")
        void retryPayment_maxLimitReached_throws() {
            Payment payment = createPayment("pay-2", "FAILED");
            payment.setRetryCount(3); // equals maxRetryAttempts (3)
            when(paymentRepository.findById("pay-2")).thenReturn(Optional.of(payment));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> paymentService.retryPayment("pay-2"));
            assertTrue(ex.getMessage().contains("maximum retry limit"));
        }

        @Test
        @DisplayName("Should throw PaymentNotFoundException when payment does not exist")
        void retryPayment_notFound_throws() {
            when(paymentRepository.findById("missing")).thenReturn(Optional.empty());

            assertThrows(PaymentNotFoundException.class,
                    () -> paymentService.retryPayment("missing"));
        }

        @Test
        @DisplayName("Should succeed at boundary retryCount = maxAttempts - 1")
        void retryPayment_boundaryRetryCount_succeeds() {
            Payment payment = createPayment("pay-boundary", "FAILED");
            payment.setRetryCount(2); // maxRetryAttempts - 1 = boundary (2 < 3, so allowed)
            payment.setSourceAccount("1234567890");
            payment.setDestinationAccount("0987654321");
            payment.setAmount(BigDecimal.valueOf(50));
            payment.setCurrency("USD");

            Transaction tx = new Transaction();
            tx.setId("tx-boundary");
            tx.setPaymentId("pay-boundary");

            when(paymentRepository.findById("pay-boundary")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(bankingAPIService.validateAccount(any())).thenReturn(true);
            when(bankingAPIService.hasSufficientFunds(any(), any())).thenReturn(true);
            when(transactionService.createTransaction("pay-boundary")).thenReturn(tx);
            doNothing().when(bankingAPIService).transferFunds(any(), any(), any());

            PaymentResponse response = paymentService.retryPayment("pay-boundary");

            assertEquals("COMPLETED", response.getStatus());
            verify(paymentMetrics).incrementRetried();
            verify(paymentMetrics).incrementRetriedSuccess();
        }

        @Test
        @DisplayName("Should succeed when FAILED payment is within retry limit")
        void retryPayment_withinLimit_succeeds() {
            Payment payment = createPayment("pay-3", "FAILED");
            payment.setRetryCount(1); // below maxRetryAttempts (3)
            payment.setSourceAccount("1234567890");
            payment.setDestinationAccount("0987654321");
            payment.setAmount(BigDecimal.valueOf(100));
            payment.setCurrency("USD");

            Transaction tx = new Transaction();
            tx.setId("tx-001");
            tx.setPaymentId("pay-3");

            when(paymentRepository.findById("pay-3")).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(bankingAPIService.validateAccount(any())).thenReturn(true);
            when(bankingAPIService.hasSufficientFunds(any(), any())).thenReturn(true);
            when(transactionService.createTransaction("pay-3")).thenReturn(tx);
            doNothing().when(bankingAPIService).transferFunds(any(), any(), any());

            PaymentResponse response = paymentService.retryPayment("pay-3");

            assertEquals("COMPLETED", response.getStatus());
        }
    }
}
