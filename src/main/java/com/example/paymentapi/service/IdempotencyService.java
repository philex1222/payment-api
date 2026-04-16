package com.example.paymentapi.service;

import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;

import java.util.Optional;

/**
 * Idempotency guard for payment creation.
 *
 * <p>Clients should send a unique {@code Idempotency-Key} header on POST requests.
 * The first response is stored and replayed verbatim for any subsequent request
 * that carries the same key, preventing duplicate charges caused by network retries.
 */
public interface IdempotencyService {

    Optional<PaymentWorkflowResponse> get(String idempotencyKey);

    void store(String idempotencyKey, PaymentWorkflowResponse response);
}
