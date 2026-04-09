package com.example.paymentapi.exception;

import com.example.paymentapi.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class PaymentExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFoundException(
            PaymentNotFoundException ex, WebRequest request) {
        logger.warn("Payment not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Payment Not Found", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(
            UserNotFoundException ex, WebRequest request) {
        logger.warn("User not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "User Not Found", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFoundException(
            TransactionNotFoundException ex, WebRequest request) {
        logger.warn("Transaction not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Transaction Not Found", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(WebhookSubscriptionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWebhookSubscriptionNotFoundException(
            WebhookSubscriptionNotFoundException ex, WebRequest request) {
        logger.warn("Webhook subscription not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Webhook Subscription Not Found", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElementException(
            java.util.NoSuchElementException ex, WebRequest request) {
        logger.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFundsException(
            InsufficientFundsException ex, WebRequest request) {
        logger.warn("Insufficient funds: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Insufficient Funds", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(InvalidAccountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAccountException(
            InvalidAccountException ex, WebRequest request) {
        logger.warn("Invalid account: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Invalid Account", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStatusTransitionException(
            InvalidStatusTransitionException ex, WebRequest request) {
        logger.warn("Invalid status transition: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.CONFLICT, "Invalid Status Transition", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(PaymentReversalException.class)
    public ResponseEntity<ErrorResponse> handlePaymentReversalException(
            PaymentReversalException ex, WebRequest request) {
        logger.error("Payment reversal failed: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, "Reversal Failed", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        logger.warn("Validation failed: {}", ex.getMessage());
        BindingResult bindingResult = ex.getBindingResult();

        List<ErrorResponse.FieldError> fieldErrors = bindingResult.getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldError.builder()
                        .field(fe.getField())
                        .message(fe.getDefaultMessage())
                        .rejectedValue(isSensitiveField(fe.getField()) ? null : fe.getRejectedValue())
                        .build())
                .toList();

        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Validation Failed",
                "Request validation failed. Check field errors for details.", request);
        error.setFieldErrors(fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException ex, WebRequest request) {
        logger.warn("Constraint violation: {}", ex.getMessage());

        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> ErrorResponse.FieldError.builder()
                        .field(getFieldName(cv))
                        .message(cv.getMessage())
                        .rejectedValue(cv.getInvalidValue())
                        .build())
                .toList();

        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Constraint Violation",
                "Request validation failed. Check field errors for details.", request);
        error.setFieldErrors(fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameterException(
            MissingServletRequestParameterException ex, WebRequest request) {
        logger.warn("Missing parameter: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Missing Parameter",
                String.format("Required parameter '%s' is missing", ex.getParameterName()), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatchException(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        logger.warn("Type mismatch: {}", ex.getMessage());
        String message = String.format("Parameter '%s' should be of type %s",
                ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Type Mismatch", message, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        logger.warn("Malformed JSON request: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Malformed Request",
                "Request body is missing or contains invalid JSON", request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        logger.warn("Method not allowed: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                String.format("HTTP method '%s' is not supported for this endpoint", ex.getMethod()), request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        logger.warn("Access denied: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.FORBIDDEN, "Forbidden", "Access denied: insufficient permissions", request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandlerFoundException(
            NoHandlerFoundException ex, WebRequest request) {
        logger.warn("No handler found: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Not Found",
                "The requested resource was not found", request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex, WebRequest request) {
        logger.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Not Found", "The requested resource was not found", request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUsernameAlreadyExistsException(
            UsernameAlreadyExistsException ex, WebRequest request) {
        logger.warn("Username conflict: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.CONFLICT, "Username Already Taken",
                "The requested username is not available", request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        logger.warn("Illegal argument: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.BAD_REQUEST, "Invalid Argument", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException ex, WebRequest request) {
        logger.error("Data integrity violation: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.CONFLICT, "Data Integrity Violation",
                "A database constraint was violated. Please check your data and try again.", request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(
            IllegalStateException ex, WebRequest request) {
        logger.warn("Illegal state: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.CONFLICT, "Invalid Operation", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, WebRequest request) {
        // Prefer the Micrometer/Brave traceId already injected into MDC by the
        // tracing bridge; fall back to a new UUID only if tracing is disabled.
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        logger.error("Unexpected error [traceId={}]: {}", traceId, ex.getMessage(), ex);
        ErrorResponse error = createErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Please contact support with trace ID: " + traceId, request);
        error.setTraceId(traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ErrorResponse createErrorResponse(HttpStatus status, String error, String message, WebRequest request) {
        String path = "";
        if (request instanceof ServletWebRequest) {
            path = ((ServletWebRequest) request).getRequest().getRequestURI();
        }
        return ErrorResponse.of(status.value(), error, message, path);
    }

    private String getFieldName(ConstraintViolation<?> cv) {
        String path = cv.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }

    /** Returns true for field names that should never have their value echoed back in error responses. */
    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) return false;
        String lower = fieldName.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("token");
    }
}