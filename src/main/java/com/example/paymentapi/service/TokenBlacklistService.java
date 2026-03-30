package com.example.paymentapi.service;

/**
 * Service for tracking invalidated JWT tokens (logout).
 *
 * <p>Tokens are stored until their natural expiry so that a logged-out token
 * cannot be reused within its original validity window.
 */
public interface TokenBlacklistService {

    /**
     * Adds a token to the blacklist for the given duration.
     *
     * @param token              the raw JWT string
     * @param remainingValidityMs how many milliseconds the token would still be valid for
     */
    void blacklist(String token, long remainingValidityMs);

    /**
     * Returns {@code true} if the token has been blacklisted (i.e., the user has logged out).
     */
    boolean isBlacklisted(String token);
}
