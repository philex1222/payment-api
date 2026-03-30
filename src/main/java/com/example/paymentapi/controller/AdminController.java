package com.example.paymentapi.controller;

import com.example.paymentapi.dto.AdminStatsResponse;
import com.example.paymentapi.dto.RoleUpdateRequest;
import com.example.paymentapi.dto.UserSummaryResponse;
import com.example.paymentapi.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Administrative operations — requires ROLE_ADMIN")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Get platform statistics",
               description = "Returns real-time counts of payments by status, today's volume, and total user count.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN")
    })
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/users")
    @Operation(summary = "List all users (paginated)",
               description = "Returns all registered users. Passwords are never included in the response.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Users retrieved successfully"),
        @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN")
    })
    public ResponseEntity<Page<UserSummaryResponse>> getUsers(Pageable pageable) {
        return ResponseEntity.ok(adminService.getUsers(pageable));
    }

    @PatchMapping("/users/{id}/role")
    @Operation(summary = "Update a user's role",
               description = "Changes the role of the specified user. Accepted values: ROLE_USER, ROLE_ADMIN. "
                           + "The user's cached credentials are invalidated immediately.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid role value"),
        @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserSummaryResponse> updateUserRole(
            @Parameter(description = "User ID", required = true)
            @PathVariable Long id,
            @Valid @RequestBody RoleUpdateRequest request) {
        return ResponseEntity.ok(adminService.updateUserRole(id, request));
    }
}
