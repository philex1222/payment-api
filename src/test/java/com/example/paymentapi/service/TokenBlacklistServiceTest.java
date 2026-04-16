package com.example.paymentapi.service;

import com.example.paymentapi.config.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class TokenBlacklistServiceTest {

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    private static final String TOKEN = "test.jwt.token.value";
    // 5-minute validity — enough for the test to complete before expiry
    private static final long VALIDITY_MS = 300_000L;

    @Test
    void blacklist_addsToken() {
        tokenBlacklistService.blacklist(TOKEN, VALIDITY_MS);
        assertThat(tokenBlacklistService.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    void isBlacklisted_returnsFalse_forUnknownToken() {
        assertThat(tokenBlacklistService.isBlacklisted("unknown.token.xyz")).isFalse();
    }

    @Test
    void isBlacklisted_returnsFalse_forNullToken() {
        assertThat(tokenBlacklistService.isBlacklisted(null)).isFalse();
    }

    @Test
    void blacklist_isIdempotent() {
        String idempotentToken = "idempotent.token.abc";
        tokenBlacklistService.blacklist(idempotentToken, VALIDITY_MS);
        tokenBlacklistService.blacklist(idempotentToken, VALIDITY_MS); // second call must not throw
        assertThat(tokenBlacklistService.isBlacklisted(idempotentToken)).isTrue();
    }

    @Test
    void blacklist_withZeroValidity_doesNotBlacklist() {
        String expiredToken = "already.expired.token";
        tokenBlacklistService.blacklist(expiredToken, 0L);
        assertThat(tokenBlacklistService.isBlacklisted(expiredToken)).isFalse();
    }
}
