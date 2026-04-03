package com.example.paymentapi.controller;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.PaymentStatusRequest;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.dto.TransactionResponse;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.PaymentService;
import com.example.paymentapi.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/payments")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@Tag(name = "Payments", description = "Operations related to payment processing")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final TransactionService transactionService;

    public PaymentController(PaymentService paymentService,
                             IdempotencyService idempotencyService,
                             TransactionService transactionService) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
        this.transactionService = transactionService;
    }

    @PostMapping
    @Operation(summary = "Create a new payment",
               description = "Optionally supply an 'Idempotency-Key' header to prevent duplicate charges on network retries.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Payment created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid payment request"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> createPayment(
            @Parameter(description = "Client-generated unique key to prevent duplicate payments (UUID recommended)")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest paymentRequest) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentResponse> cached = idempotencyService.get(idempotencyKey);
            if (cached.isPresent()) {
                // Return the original response — same status code, with a replay marker
                return ResponseEntity.status(HttpStatus.CREATED)
                        .header(HttpHeaders.WARNING, "299 - \"Idempotency-Replayed\"")
                        .body(cached.get());
            }
        }

        PaymentResponse paymentResponse = paymentService.createPayment(paymentRequest);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.store(idempotencyKey, paymentResponse);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(paymentResponse);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment found"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> getPaymentById(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @GetMapping
    @Operation(summary = "Get payments (paginated + filtered)",
               description = "All filter params are optional. Combine freely: ?status=FAILED&currency=USD&amountFrom=100&amountTo=500")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid filter parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Page<PaymentResponse>> getPayments(
            @Parameter(description = "Filter by status (PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED, REVERSED, REFUNDED)")
            @RequestParam(required = false) String status,
            @Parameter(description = "Filter by creation date — from (ISO-8601, e.g. 2026-01-01T00:00:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @Parameter(description = "Filter by creation date — to (ISO-8601, e.g. 2026-12-31T23:59:59)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @Parameter(description = "Filter by minimum amount (inclusive)")
            @RequestParam(required = false) BigDecimal amountFrom,
            @Parameter(description = "Filter by maximum amount (inclusive)")
            @RequestParam(required = false) BigDecimal amountTo,
            @Parameter(description = "Filter by ISO 4217 currency code (e.g. USD, EUR, GBP)")
            @RequestParam(required = false) String currency,
            Pageable pageable) {
        if (amountFrom != null && amountTo != null && amountFrom.compareTo(amountTo) > 0) {
            throw new IllegalArgumentException("amountFrom cannot be greater than amountTo");
        }
        return ResponseEntity.ok(paymentService.getPayments(status, dateFrom, dateTo, amountFrom, amountTo, currency, pageable));
    }

    @GetMapping("/source-account")
    @Operation(summary = "Get payments by source account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<PaymentResponse>> getPaymentsBySourceAccount(
            @Parameter(description = "Source account number", required = true)
            @RequestParam String sourceAccount) {
        return ResponseEntity.ok(paymentService.getPaymentsBySourceAccount(sourceAccount));
    }

    @GetMapping("/destination-account")
    @Operation(summary = "Get payments by destination account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<PaymentResponse>> getPaymentsByDestinationAccount(
            @Parameter(description = "Destination account number", required = true)
            @RequestParam String destinationAccount) {
        return ResponseEntity.ok(paymentService.getPaymentsByDestinationAccount(destinationAccount));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update payment status")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment status updated successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> updatePaymentStatus(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id,
            @Valid @RequestBody PaymentStatusRequest request) {
        return ResponseEntity.ok(paymentService.updatePaymentStatus(id, request.getStatus()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a payment",
               description = "Only PENDING payments can be cancelled. Returns 409 for any other status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment cancelled successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment cannot be cancelled in its current status"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> cancelPayment(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(paymentService.cancelPayment(id));
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "List transactions for a payment",
               description = "Returns all transaction records associated with the given payment. "
                           + "Non-admin users may only retrieve transactions for payments they own.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transactions retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "403", description = "Access denied: payment belongs to another user"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<TransactionResponse>> getTransactionsByPaymentId(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        // Ownership is enforced inside getPaymentById — if the caller doesn't own this
        // payment the call throws AccessDeniedException before we touch transactions.
        paymentService.getPaymentById(id);
        List<TransactionResponse> txns = transactionService.getTransactionsByPaymentId(id)
                .stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(txns);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a payment")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Payment deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Cannot delete a completed payment"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Void> deletePayment(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a failed payment",
               description = "Re-attempts a FAILED payment. Returns 409 if the payment is not in FAILED status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Retry initiated successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment is not in FAILED status"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> retryPayment(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(paymentService.retryPayment(id));
    }

    @PostMapping("/{id}/reversal")
    @Operation(summary = "Initiate a payment reversal or partial refund")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reversal processed successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "422", description = "Reversal not possible for current payment state"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> initiatePaymentReversal(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id,
            @Valid @RequestBody ReversalRequest reversalRequest) {
        return ResponseEntity.ok(paymentService.initiatePaymentReversal(id, reversalRequest));
    }
}
