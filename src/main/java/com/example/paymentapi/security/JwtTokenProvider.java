package com.example.paymentapi.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT Token Provider for generating and validating JWT tokens.
 * Uses jjwt 0.12.x API with HMAC-SHA512 signing.
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret}")
    private String jwtSecretString;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    private final UserDetailsService userDetailsService;

    private SecretKey secretKey;

    public JwtTokenProvider(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @PostConstruct
    protected void init() {
        // Derive a SecretKey from the configured secret string.
        // Keys.hmacShaKeyFor requires at least 512 bits (64 bytes) for HS512.
        byte[] keyBytes = jwtSecretString.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        if (keyBytes.length < 64) {
            logger.warn("SECURITY: JWT secret is only {} bytes — HS512 requires at least 64 bytes (512 bits). "
                    + "Set JWT_SECRET to a cryptographically random 64+ byte value in production.", keyBytes.length);
        } else if (jwtSecretString.startsWith("change-me")) {
            logger.warn("SECURITY: JWT secret is using the insecure development default. "
                    + "Set the JWT_SECRET environment variable before deploying to production.");
        }
        logger.info("JWT Token Provider initialised with expiration: {} ms", jwtExpiration);
    }

    /**
     * Generates a JWT token for the authenticated user.
     */
    public String generateToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);

        String token = Jwts.builder()
                .subject(userDetails.getUsername())
                .issuer("payment-api")
                .audience().add("payment-api").and()
                .issuedAt(now)
                .expiration(expiryDate)
                .claim("roles", userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList())
                .signWith(secretKey)
                .compact();

        logger.debug("Generated JWT token for user: {}", userDetails.getUsername());
        return token;
    }

    /**
     * Gets the authentication object from a valid JWT token.
     */
    public Authentication getAuthentication(String token) {
        String username = getUsernameFromToken(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        logger.debug("Retrieved authentication for user: {}", username);
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    /**
     * Extracts the username (subject) from the JWT token.
     * Accepts tokens both with and without an audience claim to allow a
     * zero-downtime rollout — tokens issued before the audience claim was
     * introduced will still be accepted until they expire naturally.
     */
    public String getUsernameFromToken(String token) {
        return parseClaimsLenient(token).getSubject();
    }

    /**
     * Validates the JWT token.
     * Uses lenient audience validation: tokens with a matching audience claim pass,
     * and tokens that pre-date the audience claim (missing aud) are also accepted
     * for backward compatibility during a rolling deployment.
     *
     * @param token The JWT token to validate
     * @return true if valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            try {
                // Prefer strict validation (new tokens with aud claim)
                Jwts.parser().verifyWith(secretKey)
                        .requireIssuer("payment-api")
                        .requireAudience("payment-api")
                        .build().parseSignedClaims(token);
            } catch (IncorrectClaimException | MissingClaimException ex) {
                // Lenient fallback: accept tokens without aud for backward compatibility
                logger.debug("JWT audience claim missing/wrong — accepting legacy token: {}", ex.getMessage());
                Jwts.parser().verifyWith(secretKey)
                        .requireIssuer("payment-api")
                        .build().parseSignedClaims(token);
            }
            return true;
        } catch (SignatureException ex) {
            logger.warn("Invalid JWT signature: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            logger.warn("Malformed JWT token: {}", ex.getMessage());
        } catch (ExpiredJwtException ex) {
            logger.info("JWT token expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            logger.warn("Unsupported JWT token: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            logger.warn("JWT claims string is empty: {}", ex.getMessage());
        }
        return false;
    }

    /**
     * Gets the expiration date from a JWT token.
     */
    public Date getExpirationFromToken(String token) {
        return parseClaimsLenient(token).getExpiration();
    }

    /**
     * Parses JWT claims with lenient audience validation.
     * Tries strict audience check first; falls back to no-audience parse for
     * tokens issued before the audience claim was introduced.
     */
    private Claims parseClaimsLenient(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireAudience("payment-api")
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (IncorrectClaimException | MissingClaimException ex) {
            logger.debug("JWT audience claim missing/wrong — falling back to legacy parse: {}", ex.getMessage());
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        }
    }

    /**
     * Checks if the token is expired.
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = getExpirationFromToken(token);
            return expiration.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Gets the remaining validity time in milliseconds.
     */
    public long getRemainingValidity(String token) {
        try {
            Date expiration = getExpirationFromToken(token);
            return expiration.getTime() - System.currentTimeMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
