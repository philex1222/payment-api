package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Profile of the currently authenticated user")
public class UserProfileResponse {

    @Schema(description = "Internal user ID", example = "1")
    private final Long id;

    @Schema(description = "Username", example = "admin")
    private final String username;

    @Schema(description = "Granted role", example = "ROLE_ADMIN")
    private final String role;

    @Schema(description = "Account creation timestamp")
    private final LocalDateTime createdAt;

    public UserProfileResponse(Long id, String username, String role, LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
