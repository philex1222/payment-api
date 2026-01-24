package com.example.paymentapi.exception;

import com.example.paymentapi.dto.ErrorResponse;
import com.example.paymentapi.model.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentExceptionHandler Tests")
class PaymentExceptionHandlerTest {

    private PaymentExceptionHandler exceptionHandler;
    private WebRequest webRequest;

    @BeforeEach
    void setUp() {
        exceptionHandler = new PaymentExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/payments");
        webRequest = new ServletWebRequest(request);
    }

    @Nested
    @DisplayName("PaymentNotFoundException Handler Tests")
    class PaymentNotFoundExceptionTests {

        @Test
        @DisplayName("Should return 404 NOT_FOUND for PaymentNotFoundException")
        void testHandlePaymentNotFoundException() {
            PaymentNotFoundException ex = new PaymentNotFoundException("Payment not found with ID: 123");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handlePaymentNotFoundException(ex, webRequest);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(404, response.getBody().getStatus());
            assertEquals("Payment Not Found", response.getBody().getError());
            assertTrue(response.getBody().getMessage().contains("123"));
        }
    }

    @Nested
    @DisplayName("TransactionNotFoundException Handler Tests")
    class TransactionNotFoundExceptionTests {

        @Test
        @DisplayName("Should return 404 NOT_FOUND for TransactionNotFoundException")
        void testHandleTransactionNotFoundException() {
            TransactionNotFoundException ex = new TransactionNotFoundException("Transaction not found");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleTransactionNotFoundException(ex, webRequest);

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Transaction Not Found", response.getBody().getError());
        }
    }

    @Nested
    @DisplayName("InsufficientFundsException Handler Tests")
    class InsufficientFundsExceptionTests {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST for InsufficientFundsException")
        void testHandleInsufficientFundsException() {
            InsufficientFundsException ex = new InsufficientFundsException("Insufficient funds in account");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleInsufficientFundsException(ex, webRequest);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(400, response.getBody().getStatus());
            assertEquals("Insufficient Funds", response.getBody().getError());
        }
    }

    @Nested
    @DisplayName("InvalidAccountException Handler Tests")
    class InvalidAccountExceptionTests {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST for InvalidAccountException")
        void testHandleInvalidAccountException() {
            InvalidAccountException ex = new InvalidAccountException("Invalid account number");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleInvalidAccountException(ex, webRequest);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Invalid Account", response.getBody().getError());
        }
    }

    @Nested
    @DisplayName("InvalidStatusTransitionException Handler Tests")
    class InvalidStatusTransitionExceptionTests {

        @Test
        @DisplayName("Should return 409 CONFLICT for InvalidStatusTransitionException")
        void testHandleInvalidStatusTransitionException() {
            InvalidStatusTransitionException ex = new InvalidStatusTransitionException(
                    "payment123", PaymentStatus.COMPLETED, PaymentStatus.PENDING);

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleInvalidStatusTransitionException(ex, webRequest);

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(409, response.getBody().getStatus());
            assertEquals("Invalid Status Transition", response.getBody().getError());
        }
    }

    @Nested
    @DisplayName("PaymentReversalException Handler Tests")
    class PaymentReversalExceptionTests {

        @Test
        @DisplayName("Should return 422 UNPROCESSABLE_ENTITY for PaymentReversalException")
        void testHandlePaymentReversalException() {
            PaymentReversalException ex = new PaymentReversalException("payment123", "Cannot reverse payment");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handlePaymentReversalException(ex, webRequest);

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(422, response.getBody().getStatus());
            assertEquals("Reversal Failed", response.getBody().getError());
        }
    }

    @Nested
    @DisplayName("IllegalArgumentException Handler Tests")
    class IllegalArgumentExceptionTests {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST for IllegalArgumentException")
        void testHandleIllegalArgumentException() {
            IllegalArgumentException ex = new IllegalArgumentException("Invalid argument provided");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalArgumentException(ex, webRequest);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("Invalid Argument", response.getBody().getError());
        }
    }

    @Nested
    @DisplayName("Generic Exception Handler Tests")
    class GenericExceptionTests {

        @Test
        @DisplayName("Should return 500 INTERNAL_SERVER_ERROR for generic Exception")
        void testHandleGenericException() {
            Exception ex = new RuntimeException("Unexpected error occurred");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGenericException(ex, webRequest);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(500, response.getBody().getStatus());
            assertEquals("Internal Server Error", response.getBody().getError());
            assertNotNull(response.getBody().getTraceId());
        }

        @Test
        @DisplayName("Should include trace ID for internal errors")
        void testGenericException_HasTraceId() {
            Exception ex = new NullPointerException("NPE");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handleGenericException(ex, webRequest);

            assertNotNull(response.getBody());
            assertNotNull(response.getBody().getTraceId());
            assertFalse(response.getBody().getTraceId().isEmpty());
        }
    }

    @Nested
    @DisplayName("Error Response Structure Tests")
    class ErrorResponseStructureTests {

        @Test
        @DisplayName("Error response should have timestamp")
        void testErrorResponse_HasTimestamp() {
            PaymentNotFoundException ex = new PaymentNotFoundException("Not found");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handlePaymentNotFoundException(ex, webRequest);

            assertNotNull(response.getBody());
            assertNotNull(response.getBody().getTimestamp());
        }

        @Test
        @DisplayName("Error response should have path")
        void testErrorResponse_HasPath() {
            PaymentNotFoundException ex = new PaymentNotFoundException("Not found");

            ResponseEntity<ErrorResponse> response = exceptionHandler.handlePaymentNotFoundException(ex, webRequest);

            assertNotNull(response.getBody());
            assertEquals("/api/payments", response.getBody().getPath());
        }
    }
}
