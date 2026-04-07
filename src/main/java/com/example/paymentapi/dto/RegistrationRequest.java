package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for registering a new user account")
public class RegistrationRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$",
             message = "Username may only contain letters, digits, and underscores")
    @Schema(description = "Desired username (3–50 chars, alphanumeric and underscores only)",
            example = "john_doe")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    @Pattern(regexp = "^(?=.*[A-Z])(?=.*[0-9]).{8,100}$",
             message = "Password must contain at least one uppercase letter and one digit")
    @Schema(description = "Password (8–100 characters, must include at least one uppercase letter and one digit)",
            example = "SecurePass123!")
    private String password;
}
