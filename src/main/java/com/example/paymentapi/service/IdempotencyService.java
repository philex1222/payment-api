package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentResponse;

import java.util.Optional;

/**
 * Idempotency guard for payment creation.
 *
 * <p>Clients should send a unique {@code Idempotency-Key} header on POST requests.
 * The first response is stored and replayed verbatim for any subsequent request
 * that carries the same key, preventing duplicate charges caused by network retries.
 */
public interface IdempotencyService {

    /**
     * Returns the previously stored response for the given key, or empty if this
     * is the first time the key has been seen.
     */
    Optional<PaymentResponse> get(String idempotencyKey);

    /**
     * Persists the response so it can be replayed on duplicate requests.
     * The entry expires automatically after 24 hours.
     */
    void store(String idempotencyKey, PaymentResponse response);
}
