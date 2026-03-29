package com.example.paymentapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtTokenFilter.
 *
 * Verifies that:
 * - a valid token populates the SecurityContext
 * - an invalid/expired token is ignored (SecurityContext stays empty)
 * - an exception during getAuthentication() is swallowed and the context
 *   is cleared (rather than propagating up to the filter chain)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtTokenFilter Tests")
class JwtTokenFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private FilterChain filterChain;

    private JwtTokenFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtTokenFilter(jwtTokenProvider);
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    @DisplayName("Valid token populates the SecurityContext")
    void validToken_populatesSecurityContext() throws ServletException, IOException {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "user", null, java.util.List.of());
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getAuthentication("valid-token")).thenReturn(auth);

        filter.doFilterInternal(
                requestWithBearer("valid-token"),
                new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("Invalid token leaves SecurityContext empty and continues chain")
    void invalidToken_leavesContextEmpty() throws ServletException, IOException {
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        filter.doFilterInternal(
                requestWithBearer("bad-token"),
                new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("No Authorization header continues chain without populating context")
    void noAuthorizationHeader_leavesContextEmpty() throws ServletException, IOException {
        filter.doFilterInternal(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(any(), any());
        verify(jwtTokenProvider, never()).validateToken(any());
    }

    @Test
    @DisplayName("Exception during getAuthentication is swallowed, context cleared, chain continues")
    void getAuthentication_throws_swallowedAndContextCleared() throws ServletException, IOException {
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getAuthentication("valid-token"))
                .thenThrow(new RuntimeException("User not found"));

        filter.doFilterInternal(
                requestWithBearer("valid-token"),
                new MockHttpServletResponse(),
                filterChain);

        // Context must be cleared — request proceeds as unauthenticated
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Chain must still be called so the request can be rejected by downstream authorization
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("Non-Bearer Authorization header is ignored")
    void nonBearerAuthHeader_isIgnored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenProvider, never()).validateToken(any());
        verify(filterChain).doFilter(any(), any());
    }
}
