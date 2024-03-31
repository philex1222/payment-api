package com.example.paymentapi.controller;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.service.PaymentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

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
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest paymentRequest) {
        PaymentResponse paymentResponse = paymentService.createPayment(paymentRequest);
        return ResponseEntity.ok(paymentResponse);
    }

    @GetMapping("/{id}")
    @ApiOperation(value = "Get a payment by ID", response = PaymentResponse.class)
    public ResponseEntity<PaymentResponse> getPaymentById(@PathVariable String id) {
        PaymentResponse paymentResponse = paymentService.getPaymentById(id);
        return ResponseEntity.ok(paymentResponse);
    }

    @GetMapping("/source-account")
    @ApiOperation(value = "Get payments by source account", response = Payment.class, responseContainer = "List")
    public ResponseEntity<List<Payment>> getPaymentsBySourceAccount(@RequestParam String sourceAccount) {
        List<Payment> payments = paymentService.getPaymentsBySourceAccount(sourceAccount);
        return ResponseEntity.ok(payments);
    }

    @GetMapping("/destination-account")
    @ApiOperation(value = "Get payments by destination account", response = Payment.class, responseContainer = "List")
    public ResponseEntity<List<Payment>> getPaymentsByDestinationAccount(@RequestParam String destinationAccount) {
        List<Payment> payments = paymentService.getPaymentsByDestinationAccount(destinationAccount);
        return ResponseEntity.ok(payments);
    }

    @PatchMapping("/{id}/status")
    @ApiOperation(value = "Update payment status", response = PaymentResponse.class)
    public ResponseEntity<PaymentResponse> updatePaymentStatus(@PathVariable String id, @RequestBody Map<String, String> request) {
        String status = request.get("status");
        PaymentResponse paymentResponse = paymentService.updatePaymentStatus(id, status);
        return ResponseEntity.ok(paymentResponse);
    }

    // Other API endpoints...
}