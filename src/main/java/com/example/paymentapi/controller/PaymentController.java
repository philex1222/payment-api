package com.example.paymentapi.controller;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.PaymentStatusRequest;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.service.PaymentService;
import io.swagger.annotations.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@Api(value = "Payment API", description = "Operations related to payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ApiOperation(value = "Create a new payment", response = PaymentResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Payment created successfully"),
            @ApiResponse(code = 400, message = "Invalid payment request"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> createPayment(
            @ApiParam(value = "Payment request object", required = true)
            @Valid @RequestBody PaymentRequest paymentRequest) {
        PaymentResponse paymentResponse = paymentService.createPayment(paymentRequest);
        return ResponseEntity.ok(paymentResponse);
    }

    @GetMapping("/{id}")
    @ApiOperation(value = "Get a payment by ID", response = PaymentResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Payment found"),
            @ApiResponse(code = 404, message = "Payment not found"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> getPaymentById(
            @ApiParam(value = "Payment ID", required = true)
            @PathVariable String id) {
        PaymentResponse paymentResponse = paymentService.getPaymentById(id);
        return ResponseEntity.ok(paymentResponse);
    }

    @GetMapping
    @ApiOperation(value = "Get all payments", response = Payment.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Payments retrieved successfully"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<Page<Payment>> getPayments(
            @ApiParam(value = "Pagination information", required = false)
            Pageable pageable) {
        Page<Payment> payments = paymentService.getPayments(pageable);
        return ResponseEntity.ok(payments);
    }

    @GetMapping("/source-account")
    @ApiOperation(value = "Get payments by source account", response = Payment.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Payments retrieved successfully"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<List<Payment>> getPaymentsBySourceAccount(
            @ApiParam(value = "Source account", required = true)
            @RequestParam String sourceAccount) {
        List<Payment> payments = paymentService.getPaymentsBySourceAccount(sourceAccount);
        return ResponseEntity.ok(payments);
    }

    @GetMapping("/destination-account")
    @ApiOperation(value = "Get payments by destination account", response = Payment.class, responseContainer = "List")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Payments retrieved successfully"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<List<Payment>> getPaymentsByDestinationAccount(
            @ApiParam(value = "Destination account", required = true)
            @RequestParam String destinationAccount) {
        List<Payment> payments = paymentService.getPaymentsByDestinationAccount(destinationAccount);
        return ResponseEntity.ok(payments);
    }

    @PatchMapping("/{id}/status")
    @ApiOperation(value = "Update payment status", response = PaymentResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Payment status updated successfully"),
            @ApiResponse(code = 404, message = "Payment not found"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> updatePaymentStatus(
            @ApiParam(value = "Payment ID", required = true)
            @PathVariable String id,
            @ApiParam(value = "Payment status", required = true)
            @RequestBody PaymentStatusRequest request) {
        PaymentResponse paymentResponse = paymentService.updatePaymentStatus(id, request.getStatus());
        return ResponseEntity.ok(paymentResponse);
    }

    @DeleteMapping("/{id}")
    @ApiOperation(value = "Delete a payment", response = Void.class)
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "Payment deleted successfully"),
            @ApiResponse(code = 404, message = "Payment not found"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<Void> deletePayment(
            @ApiParam(value = "Payment ID", required = true)
            @PathVariable String id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reversal")
    @ApiOperation(value = "Initiate a payment reversal", response = PaymentResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Payment reversal initiated successfully"),
            @ApiResponse(code = 404, message = "Payment not found"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> initiatePaymentReversal(
            @ApiParam(value = "Payment ID", required = true)
            @PathVariable String id,
            @ApiParam(value = "Reversal request", required = true)
            @Valid @RequestBody ReversalRequest reversalRequest) {
        PaymentResponse paymentResponse = paymentService.initiatePaymentReversal(id, reversalRequest);
        return ResponseEntity.ok(paymentResponse);
    }
}