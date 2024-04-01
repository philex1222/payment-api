package com.example.paymentapi.controller;

import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.security.JwtTokenProvider;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Api(value = "Authentication API", description = "Operations related to authentication")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthenticationManager authenticationManager, JwtTokenProvider jwtTokenProvider) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    @ApiOperation(value = "Authenticate and generate JWT token", response = LoginResponse.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Authentication successful"),
            @ApiResponse(code = 401, message = "Invalid credentials"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword())
        );
        String token = jwtTokenProvider.generateToken(authentication);
        return ResponseEntity.ok(new LoginResponse(token));
    }
}