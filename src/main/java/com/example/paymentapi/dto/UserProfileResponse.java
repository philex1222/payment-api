package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Profile of the currently authenticated user")
public class UserProfileResponse {

    @Schema(description = "Internal user ID", example = "1")
    private final Long id;

    @Schema(description = "Username", example = "admin")
    private final String username;

    @Schema(description = "Granted role", example = "ROLE_ADMIN")
    private final String role;

    public UserProfileResponse(Long id, String username, String role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
}
