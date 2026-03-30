package com.example.paymentapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token blacklist backed by Redis with an in-memory fallback for local-dev
 * (where Redis is not running).
 *
 * <p>The raw JWT is never stored — only a SHA-256 hash of it is persisted.
 * TTL is set to the token's remaining validity so entries expire automatically.
 *
 * <p>If Redis is unavailable, blacklisted tokens are stored in a
 * {@link ConcurrentHashMap} keyed by hash with the expiry epoch-millis as value.
 * Expired entries are pruned lazily on {@link #isBlacklisted} calls.
 * This fallback provides best-effort security for local dev; in production,
 * Redis must be available.
 */
@Service
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistServiceImpl.class);
    private static final String KEY_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, Long> inMemoryFallback = new ConcurrentHashMap<>();

    public TokenBlacklistServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void blacklist(String token, long remainingValidityMs) {
        if (remainingValidityMs <= 0) {
            return; // already expired — no need to blacklist
        }
        String hash = hashToken(token);
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + hash, "1", Duration.ofMillis(remainingValidityMs));
            logger.debug("Token blacklisted in Redis (TTL={}ms)", remainingValidityMs);
        } catch (Exception e) {
            logger.warn("Redis unavailable for token blacklist; using in-memory fallback: {}", e.getMessage());
            inMemoryFallback.put(hash, System.currentTimeMillis() + remainingValidityMs);
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        String hash = hashToken(token);
        // Check Redis first
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + hash))) {
                return true;
            }
        } catch (Exception e) {
            logger.warn("Redis unavailable for blacklist check; falling back to in-memory: {}", e.getMessage());
        }
        // Check in-memory fallback with lazy expiry cleanup
        Long expiry = inMemoryFallback.get(hash);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            inMemoryFallback.remove(hash);
            return false;
        }
        return true;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present in any standard JVM
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
