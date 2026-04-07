package com.example.paymentapi.exception;

/**
 * Thrown when a registration attempt uses a username that is already taken.
 * Maps to HTTP 409 Conflict in {@link PaymentExceptionHandler}.
 */
public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException(String message) {
        super(message);
    }
}
