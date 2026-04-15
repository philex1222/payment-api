package com.example.paymentapi.controller;

import com.example.paymentapi.config.LoginRateLimitInterceptor;
import com.example.paymentapi.config.RateLimitInterceptor;
import com.example.paymentapi.config.SecurityConfig;
import com.example.paymentapi.dto.ChangePasswordRequest;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.RegistrationRequest;
import com.example.paymentapi.exception.UsernameAlreadyExistsException;
import com.example.paymentapi.model.User;
import com.example.paymentapi.security.JwtTokenProvider;
import com.example.paymentapi.service.TokenBlacklistService;
import com.example.paymentapi.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @MockitoBean
    private UserService userService;

    // Mock out the interceptors so @WebMvcTest does not need RateLimitProperties on classpath
    @MockitoBean
    private RateLimitInterceptor rateLimitInterceptor;

    @MockitoBean
    private LoginRateLimitInterceptor loginRateLimitInterceptor;

    @org.junit.jupiter.api.BeforeEach
    void allowAllRequests() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(loginRateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

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

    // ── change-password ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "user", roles = "USER")
    @DisplayName("Change password with correct current password returns 200")
    void changePassword_correctCurrentPassword_returns200() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        doNothing().when(userService).changePassword("user", "oldPass1", "newPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("oldPass1", "newPass123"))))
                .andExpect(status().isOk());

        verify(userService).changePassword("user", "oldPass1", "newPass123");
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    @DisplayName("Change password with wrong current password returns 401")
    void changePassword_wrongCurrentPassword_returns401() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        doThrow(new BadCredentialsException("bad"))
                .when(userService).changePassword("user", "wrong", "newPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("wrong", "newPass123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "deleted-user", roles = "USER")
    @DisplayName("Change password when user no longer exists returns 401")
    void changePassword_userNotFound_returns401() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        doThrow(new UsernameNotFoundException("User not found: deleted-user"))
                .when(userService).changePassword("deleted-user", "oldPass1", "newPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("oldPass1", "newPass123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    @DisplayName("Change password with too-short new password returns 400")
    void changePassword_shortNewPassword_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("oldPass1", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Change password without authentication returns 401")
    void changePassword_unauthenticated_returns401() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("old", "newPass123"))))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /me ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("GET /me returns profile for authenticated user")
    void getMe_authenticated_returnsProfile() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        com.example.paymentapi.model.User admin = new com.example.paymentapi.model.User();
        admin.setUsername("admin");
        admin.setRole("ROLE_ADMIN");
        when(userService.findByUsername("admin")).thenReturn(Optional.of(admin));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("GET /me without authentication returns 401")
    void getMe_unauthenticated_returns401() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /register ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Register with valid request creates user and returns 201")
    void register_validRequest_returns201() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        User created = new User();
        created.setId(10L);
        created.setUsername("newuser");
        created.setRole("ROLE_USER");
        when(userService.register(any(RegistrationRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("newuser", "SecurePass123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    @DisplayName("Register with duplicate username returns 409")
    void register_duplicateUsername_returns409() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(userService.register(any(RegistrationRequest.class)))
                .thenThrow(new UsernameAlreadyExistsException("Username 'newuser' is already taken"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("newuser", "SecurePass123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Username Already Taken"));
    }

    @Test
    @DisplayName("Register with too-short username returns 400")
    void register_shortUsername_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("ab", "SecurePass123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register with too-short password returns 400")
    void register_shortPassword_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("validuser", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register with invalid username characters returns 400")
    void register_invalidUsernameChars_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("invalid user!", "SecurePass123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register with missing fields returns 400")
    void register_missingFields_returns400() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
