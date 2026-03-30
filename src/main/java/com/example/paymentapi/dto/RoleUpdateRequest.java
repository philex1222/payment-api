package com.example.paymentapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for PATCH /api/v1/admin/users/{id}/role.
 * Only ROLE_USER and ROLE_ADMIN are recognised roles.
 */
public class RoleUpdateRequest {

    @NotBlank(message = "Role must not be blank")
    @Pattern(regexp = "ROLE_USER|ROLE_ADMIN",
             message = "Role must be ROLE_USER or ROLE_ADMIN")
    private String role;

    public RoleUpdateRequest() {}

    public RoleUpdateRequest(String role) {
        this.role = role;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
