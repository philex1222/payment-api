package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.*;
import com.example.paymentapi.service.AdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.example.paymentapi.exception.UserNotFoundException;

import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DisplayName("AdminController Tests")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("password");

        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        adminToken = "Bearer " + objectMapper.readValue(json, LoginResponse.class).getToken();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/stats")
    class GetStatsTests {

        @Test
        @DisplayName("Returns 200 with stats for admin user")
        void returnsStats() throws Exception {
            AdminStatsResponse stats = new AdminStatsResponse(
                    12L,
                    Map.of("COMPLETED", 10L, "FAILED", 2L),
                    3L,
                    new BigDecimal("500.00"),
                    5L,
                    LocalDateTime.now()
            );
            when(adminService.getStats()).thenReturn(stats);

            mockMvc.perform(get("/api/v1/admin/stats")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalPayments").value(12))
                    .andExpect(jsonPath("$.totalUsers").value(5))
                    .andExpect(jsonPath("$.todayPaymentCount").value(3));
        }

        @Test
        @DisplayName("Returns 401 without token")
        void requires_auth() throws Exception {
            mockMvc.perform(get("/api/v1/admin/stats"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/{id}")
    class GetUserByIdTests {

        @Test
        @DisplayName("Returns 200 with user for existing ID")
        void returnsUser() throws Exception {
            UserSummaryResponse user = new UserSummaryResponse(3L, "carol", "ROLE_USER");
            when(adminService.getUserById(3L)).thenReturn(user);

            mockMvc.perform(get("/api/v1/admin/users/3")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(3))
                    .andExpect(jsonPath("$.username").value("carol"))
                    .andExpect(jsonPath("$.role").value("ROLE_USER"));
        }

        @Test
        @DisplayName("Returns 404 when user not found")
        void returns404ForUnknownUser() throws Exception {
            when(adminService.getUserById(999L))
                    .thenThrow(new UserNotFoundException("User not found with id: 999"));

            mockMvc.perform(get("/api/v1/admin/users/999")
                            .header("Authorization", adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("User Not Found"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users")
    class GetUsersTests {

        @Test
        @DisplayName("Returns paginated user list for admin")
        void returnsPaginatedUsers() throws Exception {
            UserSummaryResponse user = new UserSummaryResponse(1L, "alice", "ROLE_USER");
            when(adminService.getUsers(any())).thenReturn(
                    new PageImpl<>(List.of(user), PageRequest.of(0, 10), 1));

            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].username").value("alice"))
                    .andExpect(jsonPath("$.content[0].role").value("ROLE_USER"))
                    .andExpect(jsonPath("$.content[0].id").value(1));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/users/{id}/role")
    class UpdateRoleTests {

        @Test
        @DisplayName("Updates role and returns updated user")
        void updatesRole() throws Exception {
            UserSummaryResponse updated = new UserSummaryResponse(2L, "bob", "ROLE_ADMIN");
            when(adminService.updateUserRole(any(), any())).thenReturn(updated);

            RoleUpdateRequest req = new RoleUpdateRequest("ROLE_ADMIN");

            mockMvc.perform(patch("/api/v1/admin/users/2/role")
                            .header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("bob"))
                    .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));
        }

        @Test
        @DisplayName("Returns 400 for invalid role value")
        void rejectsInvalidRole() throws Exception {
            RoleUpdateRequest req = new RoleUpdateRequest("ROLE_SUPERUSER");

            mockMvc.perform(patch("/api/v1/admin/users/2/role")
                            .header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 404 when user not found")
        void returns404ForUnknownUser() throws Exception {
            when(adminService.updateUserRole(any(), any()))
                    .thenThrow(new UserNotFoundException("User not found with id: 999"));

            mockMvc.perform(patch("/api/v1/admin/users/999/role")
                            .header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RoleUpdateRequest("ROLE_USER"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("User Not Found"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/users/{id}")
    class DeleteUserTests {

        @Test
        @DisplayName("Returns 204 when user is successfully deleted")
        void deletesUser() throws Exception {
            doNothing().when(adminService).deleteUser(anyLong(), anyString());

            mockMvc.perform(delete("/api/v1/admin/users/5")
                            .header("Authorization", adminToken))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("Returns 404 when user not found")
        void returns404ForUnknownUser() throws Exception {
            doThrow(new UserNotFoundException("User not found with id: 999"))
                    .when(adminService).deleteUser(anyLong(), anyString());

            mockMvc.perform(delete("/api/v1/admin/users/999")
                            .header("Authorization", adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("Returns 409 when admin tries to delete own account")
        void returns409WhenDeletingSelf() throws Exception {
            doThrow(new IllegalStateException("Admins cannot delete their own account"))
                    .when(adminService).deleteUser(anyLong(), anyString());

            mockMvc.perform(delete("/api/v1/admin/users/1")
                            .header("Authorization", adminToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    @Nested
    @DisplayName("Admin endpoint access control")
    class AccessControlTests {

        @Test
        @WithMockUser(username = "regularuser", roles = "USER")
        @DisplayName("Returns 403 for USER role on admin stats endpoint")
        void nonAdminGets403OnStats() throws Exception {
            // @WithMockUser sets USER role in SecurityContext; JWT filter leaves it intact
            // since there is no Bearer token header on this request.
            mockMvc.perform(get("/api/v1/admin/stats"))
                    .andExpect(status().isForbidden());
        }
    }
}
