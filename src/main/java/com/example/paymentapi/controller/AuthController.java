package com.example.paymentapi.controller;

import com.example.paymentapi.dto.ChangePasswordRequest;
import com.example.paymentapi.dto.ErrorResponse;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.RegistrationRequest;
import com.example.paymentapi.dto.UserProfileResponse;
import com.example.paymentapi.exception.UserNotFoundException;
import com.example.paymentapi.model.User;
import com.example.paymentapi.security.JwtTokenProvider;
import com.example.paymentapi.service.TokenBlacklistService;
import com.example.paymentapi.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Operations related to authentication")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserService userService;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenProvider jwtTokenProvider,
                          TokenBlacklistService tokenBlacklistService,
                          UserService userService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user account",
               description = "Creates a new account with ROLE_USER. Returns 409 if the username is already taken.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User registered successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error in request body"),
        @ApiResponse(responseCode = "409", description = "Username is already taken")
    })
    public ResponseEntity<UserProfileResponse> register(@Valid @RequestBody RegistrationRequest request) {
        User user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserProfileResponse(user.getId(), user.getUsername(), user.getRole(), user.getCreatedAt()));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and generate JWT token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authentication successful"),
        @ApiResponse(responseCode = "400", description = "Missing or invalid request body"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest loginRequest) {
        try {
            logger.debug("Attempting login for user: {}", loginRequest.getUsername());

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));

            String token = jwtTokenProvider.generateToken(authentication);
            logger.info("User '{}' logged in successfully", loginRequest.getUsername());
            return ResponseEntity.ok(new LoginResponse(token));

        } catch (BadCredentialsException e) {
            logger.warn("Failed login attempt for user: '{}' — bad credentials", loginRequest.getUsername());
            ErrorResponse error = ErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    "Invalid username or password",
                    "/api/v1/auth/login");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);

        } catch (Exception e) {
            logger.error("Login error for user: '{}' — {}", loginRequest.getUsername(), e.getMessage());
            ErrorResponse error = ErrorResponse.of(
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "Internal Server Error",
                    "An error occurred during authentication",
                    "/api/v1/auth/login");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Invalidate the current JWT token",
               description = "Blacklists the supplied Bearer token so it cannot be reused until it expires naturally.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Logged out successfully"),
        @ApiResponse(responseCode = "400", description = "No valid Bearer token provided")
    })
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().build();
        }
        String token = authHeader.substring(7);
        if (jwtTokenProvider.validateToken(token)) {
            long remainingMs = jwtTokenProvider.getRemainingValidity(token);
            tokenBlacklistService.blacklist(token, remainingMs);
            logger.info("JWT token blacklisted via logout");
        }
        // Always return 200 — don't reveal whether the token was valid
        return ResponseEntity.ok().build();
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change the authenticated user's password",
               description = "Verifies the current password then replaces it with the new one. "
                           + "The caller's active token remains valid; log out explicitly to invalidate it.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password changed successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error in request body"),
        @ApiResponse(responseCode = "401", description = "Current password is incorrect or no valid token provided")
    })
    public ResponseEntity<Object> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        try {
            userService.changePassword(
                    userDetails.getUsername(),
                    request.getCurrentPassword(),
                    request.getNewPassword());
            return ResponseEntity.ok().build();
        } catch (BadCredentialsException e) {
            logger.warn("Password change rejected for user '{}': {}", userDetails.getUsername(), e.getMessage());
            ErrorResponse error = ErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    "Current password is incorrect",
                    "/api/v1/auth/change-password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's profile",
               description = "Returns the id, username, and role of the currently authenticated principal.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile returned successfully"),
        @ApiResponse(responseCode = "401", description = "No valid Bearer token provided")
    })
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        com.example.paymentapi.model.User user = userService.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userDetails.getUsername()));
        return ResponseEntity.ok(new UserProfileResponse(user.getId(), user.getUsername(), user.getRole(), user.getCreatedAt()));
    }
}
