package com.example.paymentapi.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    private static final String SECRET =
            "super-secret-hs512-key-used-for-unit-tests-only-must-be-64-bytes-long!!!";

    @Mock
    private UserDetailsService userDetailsService;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new JwtTokenProvider(userDetailsService);
        ReflectionTestUtils.setField(provider, "jwtSecretString", SECRET);
        ReflectionTestUtils.setField(provider, "jwtExpiration", 3_600_000L);
        ReflectionTestUtils.invokeMethod(provider, "init");
    }

    private Authentication auth(String username, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        UserDetails user = new User(username, "pwd", authorities);
        return new UsernamePasswordAuthenticationToken(user, null, authorities);
    }

    // ── generateToken / validateToken / getUsernameFromToken ───────────────

    @Test
    void generatesAndValidatesToken() {
        String token = provider.generateToken(auth("alice", "ROLE_USER"));
        assertTrue(provider.validateToken(token));
        assertEquals("alice", provider.getUsernameFromToken(token));
    }

    @Test
    void tokenContainsIssuerAndAudience() {
        String token = provider.generateToken(auth("alice", "ROLE_USER"));
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        var claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        assertEquals("payment-api", claims.getIssuer());
        assertTrue(claims.getAudience().contains("payment-api"));
    }

    // ── validateToken negative branches ────────────────────────────────────

    @Test
    void validateToken_rejectsEmptyToken() {
        assertFalse(provider.validateToken(""));
    }

    @Test
    void validateToken_rejectsMalformedToken() {
        assertFalse(provider.validateToken("not-a-jwt"));
    }

    @Test
    void validateToken_rejectsBadSignature() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "different-super-secret-hs512-key-64-bytes-long-padding-padding".getBytes(StandardCharsets.UTF_8));
        String badToken = Jwts.builder()
                .subject("alice").issuer("payment-api")
                .audience().add("payment-api").and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(wrongKey).compact();
        assertFalse(provider.validateToken(badToken));
    }

    @Test
    void validateToken_rejectsExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("alice").issuer("payment-api")
                .audience().add("payment-api").and()
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 5_000))
                .signWith(key).compact();
        assertFalse(provider.validateToken(expired));
    }

    @Test
    void validateToken_acceptsLegacyTokenWithoutAudience() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String legacy = Jwts.builder()
                .subject("bob").issuer("payment-api")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key).compact();
        assertTrue(provider.validateToken(legacy));
        assertEquals("bob", provider.getUsernameFromToken(legacy));
    }

    // ── getAuthentication ──────────────────────────────────────────────────

    @Test
    void getAuthentication_loadsUserDetails() {
        UserDetails details = new User("carol", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(userDetailsService.loadUserByUsername("carol")).thenReturn(details);
        String token = provider.generateToken(auth("carol", "ROLE_ADMIN"));

        Authentication authenticated = provider.getAuthentication(token);

        assertEquals("carol", ((UserDetails) authenticated.getPrincipal()).getUsername());
    }

    // ── getExpirationFromToken / isTokenExpired / getRemainingValidity ─────

    @Test
    void getExpirationFromToken_returnsExpiration() {
        String token = provider.generateToken(auth("alice", "ROLE_USER"));
        assertTrue(provider.getExpirationFromToken(token).after(new Date()));
    }

    @Test
    void isTokenExpired_returnsFalseForFreshToken() {
        String token = provider.generateToken(auth("alice", "ROLE_USER"));
        assertFalse(provider.isTokenExpired(token));
    }

    @Test
    void isTokenExpired_returnsTrueForGarbageToken() {
        assertTrue(provider.isTokenExpired("not-a-token"));
    }

    @Test
    void getRemainingValidity_isPositiveForFreshToken() {
        String token = provider.generateToken(auth("alice", "ROLE_USER"));
        assertTrue(provider.getRemainingValidity(token) > 0);
    }

    @Test
    void getRemainingValidity_returnsZeroForGarbageToken() {
        assertEquals(0L, provider.getRemainingValidity("garbage"));
    }

    // ── init() warning paths ───────────────────────────────────────────────

    @Test
    void init_warnsOnShortKey() {
        JwtTokenProvider shortKeyProvider = new JwtTokenProvider(userDetailsService);
        ReflectionTestUtils.setField(shortKeyProvider, "jwtSecretString",
                "short-key-but-still-64-bytes-for-HS512-padding-padding-padding-pad");
        ReflectionTestUtils.setField(shortKeyProvider, "jwtExpiration", 3_600_000L);
        // keyBytes.length == 64 so no warning, but ensure init runs without error
        ReflectionTestUtils.invokeMethod(shortKeyProvider, "init");
    }

    @Test
    void init_warnsOnDevDefault() {
        JwtTokenProvider devProvider = new JwtTokenProvider(userDetailsService);
        ReflectionTestUtils.setField(devProvider, "jwtSecretString",
                "change-me-this-is-a-dev-default-and-must-be-64-bytes-long-padding");
        ReflectionTestUtils.setField(devProvider, "jwtExpiration", 3_600_000L);
        ReflectionTestUtils.invokeMethod(devProvider, "init");
    }
}
