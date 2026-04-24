package com.example.paymentapi.controller;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.PaymentStatusRequest;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.dto.TransactionResponse;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.command.CancellationHandler;
import com.example.paymentapi.service.command.PaymentLifecycleHandler;
import com.example.paymentapi.service.command.ReversalHandler;
import com.example.paymentapi.service.query.PaymentQueryService;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.temporal.config.TemporalProperties;
import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.example.paymentapi.temporal.dto.WorkflowStatusResponse;
import com.example.paymentapi.temporal.metrics.PaymentWorkflowMetrics;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@Tag(name = "Payments", description = "Operations related to payment processing")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    // Temporal's workflowId has a 255-char server limit; we prepend "payment-" (8 chars)
    // and need headroom for future prefixes, so cap the client-provided key at 128.
    static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    // Cancellation reason is signalled into workflow history — bound it to keep
    // history payloads small and avoid an unbounded-free-text injection surface.
    static final int CANCEL_REASON_MAX_LENGTH = 500;

    private final WorkflowClient workflowClient;
    private final TemporalProperties temporalProperties;
    private final PaymentSecurityHelper security;
    private final ReversalHandler reversalHandler;
    private final CancellationHandler cancellationHandler;
    private final PaymentLifecycleHandler lifecycleHandler;
    private final PaymentQueryService queryService;
    private final IdempotencyService idempotencyService;
    private final TransactionService transactionService;
    private final PaymentWorkflowMetrics workflowMetrics;

    public PaymentController(WorkflowClient workflowClient,
                             TemporalProperties temporalProperties,
                             PaymentSecurityHelper security,
                             ReversalHandler reversalHandler,
                             CancellationHandler cancellationHandler,
                             PaymentLifecycleHandler lifecycleHandler,
                             PaymentQueryService queryService,
                             IdempotencyService idempotencyService,
                             TransactionService transactionService,
                             PaymentWorkflowMetrics workflowMetrics) {
        this.workflowClient = workflowClient;
        this.temporalProperties = temporalProperties;
        this.security = security;
        this.reversalHandler = reversalHandler;
        this.cancellationHandler = cancellationHandler;
        this.lifecycleHandler = lifecycleHandler;
        this.queryService = queryService;
        this.idempotencyService = idempotencyService;
        this.transactionService = transactionService;
        this.workflowMetrics = workflowMetrics;
    }

    @PostMapping
    @Operation(summary = "Create a new payment",
               description = "Starts a durable Temporal workflow. Returns 202 Accepted + workflowId. "
                           + "Poll GET /api/v1/payments/{paymentId} until status leaves PENDING. "
                           + "Optionally supply an 'Idempotency-Key' header to prevent duplicate charges.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Payment workflow started"),
        @ApiResponse(responseCode = "400", description = "Invalid payment request"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentWorkflowResponse> createPayment(
            @Parameter(description = "Client-generated unique key to prevent duplicate payments (UUID recommended)")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest paymentRequest) {

        if (paymentRequest.getSourceAccount().equals(paymentRequest.getDestinationAccount())) {
            throw new IllegalArgumentException("Source and destination accounts must differ");
        }

        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        if (hasIdempotencyKey && idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header must be at most "
                            + IDEMPOTENCY_KEY_MAX_LENGTH + " characters");
        }
        if (hasIdempotencyKey) {
            Optional<PaymentWorkflowResponse> cached = idempotencyService.get(idempotencyKey);
            if (cached.isPresent()) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(cached.get());
            }
        }

        // Derive workflowId from the idempotency key (so Temporal itself enforces
        // de-duplication via REJECT_DUPLICATE). Falls back to a random UUID when no
        // key is provided. This makes the first-line defence against duplicate starts
        // Temporal-native rather than dependent on the idempotency-cache round trip.
        String workflowId = hasIdempotencyKey
                ? "payment-" + idempotencyKey
                : "payment-" + UUID.randomUUID();
        String initiatedBy = security.currentUsername();

        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(
                PaymentCreationWorkflow.class.getSimpleName(),
                WorkflowOptions.newBuilder()
                        .setTaskQueue(temporalProperties.getTaskQueue())
                        .setWorkflowId(workflowId)
                        .setWorkflowIdReusePolicy(hasIdempotencyKey
                                ? WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE
                                : WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .setWorkflowRunTimeout(temporalProperties.getWorkflow().getRunTimeout())
                        .setWorkflowTaskTimeout(temporalProperties.getWorkflow().getTaskTimeout())
                        .build());
        String statusUrl = "/api/v1/payments/workflows/" + workflowId + "/status";
        PaymentWorkflowResponse wfResponse = new PaymentWorkflowResponse(workflowId, "PENDING", statusUrl);

        try {
            stub.start(paymentRequest, initiatedBy);
            workflowMetrics.recordStarted(hasIdempotencyKey);
        } catch (WorkflowExecutionAlreadyStarted e) {
            if (!hasIdempotencyKey) {
                throw e;
            }
        }

        if (hasIdempotencyKey) {
            idempotencyService.store(idempotencyKey, wfResponse);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(wfResponse);
    }

    @PostMapping("/workflows/{workflowId}/cancel")
    @Operation(summary = "Request graceful cancellation of a running payment workflow",
               description = "Sends a cancellation signal to the workflow. It finishes the activity "
                           + "currently in flight (to avoid a partial transfer) and then routes through "
                           + "saga compensation on the next checkpoint, marking the payment FAILED with "
                           + "reason 'cancelled-by-user'. Returns 202 once the signal is accepted — "
                           + "poll GET /workflows/{workflowId}/status to observe CANCELLED.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Cancellation signal accepted"),
        @ApiResponse(responseCode = "404", description = "Workflow not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Void> cancelWorkflow(
            @Parameter(description = "Workflow ID returned by POST /api/v1/payments", required = true)
            @PathVariable String workflowId,
            @Parameter(description = "Optional free-text reason for cancellation (audit trail, max 500 chars)")
            @RequestParam(required = false, defaultValue = "user-initiated") String reason) {
        if (reason != null && reason.length() > CANCEL_REASON_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "reason must be at most " + CANCEL_REASON_MAX_LENGTH + " characters");
        }
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
            stub.signal("requestCancel", reason);
            workflowMetrics.recordCancelled();
            return ResponseEntity.accepted().build();
        } catch (io.temporal.client.WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/workflows/{workflowId}/status")
    @Operation(summary = "Query the live status of a payment workflow",
               description = "Returns the workflow's current phase (PENDING / VALIDATING / PERSISTING / "
                           + "TRANSFERRING / COMPLETING / NOTIFYING / COMPLETED) and the paymentId once it "
                           + "has been persisted. Responds 200 while the workflow exists in the Temporal cluster.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Workflow status retrieved"),
        @ApiResponse(responseCode = "404", description = "Workflow not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<WorkflowStatusResponse> getWorkflowStatus(
            @Parameter(description = "Workflow ID returned by POST /api/v1/payments", required = true)
            @PathVariable String workflowId) {
        try {
            PaymentCreationWorkflow wf = workflowClient.newWorkflowStub(
                    PaymentCreationWorkflow.class, workflowId);
            String status = wf.getCurrentStatus();
            String paymentId = wf.getPaymentId();
            return ResponseEntity.ok(new WorkflowStatusResponse(workflowId, status, paymentId));
        } catch (io.temporal.client.WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
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
        return ResponseEntity.ok(queryService.findById(id));
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
        return ResponseEntity.ok(queryService.findAll(status, dateFrom, dateTo, amountFrom, amountTo, currency, pageable));
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
        return ResponseEntity.ok(queryService.findBySourceAccount(sourceAccount));
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
        return ResponseEntity.ok(queryService.findByDestinationAccount(destinationAccount));
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
        return ResponseEntity.ok(lifecycleHandler.updateStatus(id, request.getStatus()));
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
        return ResponseEntity.ok(cancellationHandler.handle(id));
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
        queryService.findById(id);
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
        lifecycleHandler.delete(id);
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
        return ResponseEntity.ok(lifecycleHandler.retry(id));
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
        return ResponseEntity.ok(reversalHandler.handle(id, reversalRequest));
    }
}
