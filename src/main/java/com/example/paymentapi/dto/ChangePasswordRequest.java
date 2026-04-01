package com.example.paymentapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for changing the authenticated user's password")
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    @Schema(description = "The user's existing password", example = "currentPass123")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 100, message = "New password must be between 8 and 100 characters")
    @Schema(description = "The new password (minimum 8 characters)", example = "newSecurePass456")
    private String newPassword;
}
