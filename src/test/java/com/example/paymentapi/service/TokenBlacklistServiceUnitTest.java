package com.example.paymentapi.service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceUnitTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TokenBlacklistServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TokenBlacklistServiceImpl(redisTemplate);
    }

    @Test
    void blacklist_writesToRedisWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.blacklist("some.jwt.token", 10_000L);

        verify(valueOps).set(startsWith("blacklist:"), eq("1"), eq(Duration.ofMillis(10_000L)));
    }

    @Test
    void blacklist_fallsBackToInMemoryOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        service.blacklist("fallback.token", 60_000L);

        assertTrue(service.isBlacklisted("fallback.token"));
    }

    @Test
    void blacklist_withZeroValidityIsNoOp() {
        service.blacklist("t", 0L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void isBlacklisted_returnsFalseForNullToken() {
        assertFalse(service.isBlacklisted(null));
    }

    @Test
    void isBlacklisted_returnsTrueWhenRedisReportsKey() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.TRUE);
        assertTrue(service.isBlacklisted("present.token"));
    }

    @Test
    void isBlacklisted_fallsBackToInMemoryOnRedisError() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> fallback =
                (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(service, "inMemoryFallback");
        // Put a fake entry keyed by the hash
        String token = "mem-fallback.token";
        service.blacklist(token, 60_000L); // writes to Redis first, but will throw — wait, redisTemplate.opsForValue not stubbed
        // Re-seed directly via reflection to avoid depending on blacklist() Redis path
        fallback.clear();
        // Simulate prior blacklist stored only in-memory:
        fallback.put(sha256HashOf(token), System.currentTimeMillis() + 60_000L);

        assertTrue(service.isBlacklisted(token));
    }

    @Test
    void isBlacklisted_pruneAndReturnFalseWhenFallbackEntryExpired() {
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> fallback =
                (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(service, "inMemoryFallback");
        when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);
        String token = "expired.token";
        fallback.put(sha256HashOf(token), System.currentTimeMillis() - 1L);

        assertFalse(service.isBlacklisted(token));
        assertFalse(fallback.containsKey(sha256HashOf(token)));
    }

    @Test
    void isBlacklisted_returnsFalseWhenNotInRedisOrMemory() {
        when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);
        assertFalse(service.isBlacklisted("nothing.here.token"));
    }

    private String sha256HashOf(String token) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
