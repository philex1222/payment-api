package com.example.paymentapi.security;

import com.example.paymentapi.service.TokenBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet filter that extracts and validates the JWT Bearer token from
 * every inbound request and populates the SecurityContext.
 *
 * <p>A token is accepted only when it passes structural validation AND is not
 * present in the token blacklist (i.e., the user has not logged out).
 *
 * <p>If token validation succeeds but the subsequent {@code getAuthentication()}
 * call fails (e.g. the user was deleted since the token was issued), the
 * exception is caught and logged — the request proceeds with no security
 * context so that downstream authorization checks will reject it with 403.
 */
public class JwtTokenFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtTokenFilter(JwtTokenProvider jwtTokenProvider, TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null
                && jwtTokenProvider.validateToken(token)
                && !tokenBlacklistService.isBlacklisted(token)) {
            try {
                Authentication authentication = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                // Token was structurally valid but the principal could not be loaded
                // (e.g. user deleted). Clear context so the request is treated as
                // unauthenticated — Spring Security will respond with 403.
                SecurityContextHolder.clearContext();
                logger.warn("Could not set authentication from valid JWT token: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}