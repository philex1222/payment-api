package com.example.paymentapi.controller;

import com.example.paymentapi.config.RateLimitInterceptor;
import com.example.paymentapi.config.SecurityConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.security.JwtTokenProvider;
import com.example.paymentapi.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
@DisplayName("AuthController Tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    // Mock out the interceptor so @WebMvcTest does not need RateLimitProperties on classpath
    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    @Test
    @DisplayName("Login with valid credentials returns 200 and JWT token")
    void login_validCredentials_returns200() throws Exception {
        // Allow all requests through the mocked interceptor
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        Authentication auth = new UsernamePasswordAuthenticationToken("admin", null);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtTokenProvider.generateToken(auth)).thenReturn("mock-jwt-token");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    @DisplayName("Login with bad credentials returns 401 with ErrorResponse body")
    void login_badCredentials_returns401() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    @DisplayName("Login with missing username returns 400 validation error")
    void login_missingUsername_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login with missing password returns 400 validation error")
    void login_missingPassword_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login with empty body returns 400")
    void login_emptyBody_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Logout with valid Bearer token returns 200 and blacklists the token")
    void logout_validToken_returns200AndBlacklists() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(jwtTokenProvider.validateToken("my-jwt")).thenReturn(true);
        when(jwtTokenProvider.getRemainingValidity("my-jwt")).thenReturn(60_000L);
        // Token is not blacklisted (filter passes through)
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer my-jwt"))
                .andExpect(status().isOk());

        verify(tokenBlacklistService).blacklist("my-jwt", 60_000L);
    }

    @Test
    @DisplayName("Logout without Authorization header returns 400")
    void logout_noAuthHeader_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isBadRequest());
    }
}
