package com.example.paymentapi.controller;

import com.example.paymentapi.dto.ErrorResponse;
import io.temporal.client.WorkflowClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@ConditionalOnMissingBean(WorkflowClient.class)
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class PaymentUnavailableController {

    private static final String MESSAGE =
            "Payment workflow processing is unavailable because Temporal is disabled";

    @GetMapping({"", "/**"})
    public ResponseEntity<ErrorResponse> getUnavailable(HttpServletRequest request) {
        return unavailable(request);
    }

    @PostMapping({"", "/**"})
    public ResponseEntity<ErrorResponse> postUnavailable(HttpServletRequest request) {
        return unavailable(request);
    }

    @PutMapping({"", "/**"})
    public ResponseEntity<ErrorResponse> putUnavailable(HttpServletRequest request) {
        return unavailable(request);
    }

    @PatchMapping({"", "/**"})
    public ResponseEntity<ErrorResponse> patchUnavailable(HttpServletRequest request) {
        return unavailable(request);
    }

    @DeleteMapping({"", "/**"})
    public ResponseEntity<ErrorResponse> deleteUnavailable(HttpServletRequest request) {
        return unavailable(request);
    }

    private ResponseEntity<ErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Service Unavailable",
                        MESSAGE,
                        request.getRequestURI()));
    }
}
