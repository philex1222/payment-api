package com.example.paymentapi.service;

import com.example.paymentapi.dto.AdminStatsResponse;
import com.example.paymentapi.dto.RoleUpdateRequest;
import com.example.paymentapi.dto.UserSummaryResponse;
import com.example.paymentapi.exception.UserNotFoundException;
import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminServiceImpl Tests")
class AdminServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserDetailsServiceImpl userDetailsService;

    @InjectMocks
    private AdminServiceImpl adminService;

    @Nested
    @DisplayName("getStats()")
    class GetStatsTests {

        @Test
        @DisplayName("Returns correct counts and today's volume")
        void returnsStatsAggregate() {
            when(paymentRepository.countGroupedByStatus()).thenReturn(List.of(
                    new Object[]{"COMPLETED", 10L},
                    new Object[]{"FAILED", 2L}
            ));
            when(paymentRepository.getTodayStats(any())).thenReturn(new Object[]{3L, new BigDecimal("500.00")});
            when(userRepository.count()).thenReturn(5L);

            AdminStatsResponse stats = adminService.getStats();

            assertEquals(12L, stats.getTotalPayments());
            assertEquals(10L, stats.getPaymentsByStatus().get("COMPLETED"));
            assertEquals(2L, stats.getPaymentsByStatus().get("FAILED"));
            assertEquals(3L, stats.getTodayPaymentCount());
            assertEquals(new BigDecimal("500.00"), stats.getTodayVolume());
            assertEquals(5L, stats.getTotalUsers());
            assertNotNull(stats.getGeneratedAt());
        }

        @Test
        @DisplayName("Handles empty payments table")
        void handlesNoPayments() {
            when(paymentRepository.countGroupedByStatus()).thenReturn(List.of());
            when(paymentRepository.getTodayStats(any())).thenReturn(new Object[]{0L, BigDecimal.ZERO});
            when(userRepository.count()).thenReturn(1L);

            AdminStatsResponse stats = adminService.getStats();

            assertEquals(0L, stats.getTotalPayments());
            assertTrue(stats.getPaymentsByStatus().isEmpty());
        }
    }

    @Nested
    @DisplayName("getUsers()")
    class GetUsersTests {

        @Test
        @DisplayName("Returns paginated user summaries without passwords")
        void returnsPaginatedUsers() {
            User u = user(1L, "alice", "ROLE_USER");
            Page<User> page = new PageImpl<>(List.of(u));
            when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);

            Page<UserSummaryResponse> result = adminService.getUsers(PageRequest.of(0, 10));

            assertEquals(1, result.getTotalElements());
            UserSummaryResponse summary = result.getContent().get(0);
            assertEquals(1L, summary.getId());
            assertEquals("alice", summary.getUsername());
            assertEquals("ROLE_USER", summary.getRole());
        }
    }

    @Nested
    @DisplayName("getUserById()")
    class GetUserByIdTests {

        @Test
        @DisplayName("Returns user summary for existing ID")
        void returnsUserSummary() {
            User u = user(5L, "carol", "ROLE_USER");
            when(userRepository.findById(5L)).thenReturn(Optional.of(u));

            UserSummaryResponse result = adminService.getUserById(5L);

            assertEquals(5L, result.getId());
            assertEquals("carol", result.getUsername());
            assertEquals("ROLE_USER", result.getRole());
        }

        @Test
        @DisplayName("Throws UserNotFoundException for unknown ID")
        void throwsForUnknownUser() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(UserNotFoundException.class, () -> adminService.getUserById(99L));
        }
    }

    @Nested
    @DisplayName("deleteUser()")
    class DeleteUserTests {

        @Test
        @DisplayName("Deletes user and evicts cache when target != requester")
        void deletesUser() {
            User u = user(10L, "dave", "ROLE_USER");
            when(userRepository.findById(10L)).thenReturn(Optional.of(u));

            adminService.deleteUser(10L, "admin");

            verify(userDetailsService).evictUser("dave");
            verify(userRepository).delete(u);
        }

        @Test
        @DisplayName("Throws IllegalStateException when admin deletes own account")
        void throwsWhenDeletingSelf() {
            User u = user(1L, "admin", "ROLE_ADMIN");
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));

            assertThrows(IllegalStateException.class,
                    () -> adminService.deleteUser(1L, "admin"));

            verify(userRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Throws UserNotFoundException for unknown ID")
        void throwsForUnknownUser() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(UserNotFoundException.class,
                    () -> adminService.deleteUser(99L, "admin"));
        }
    }

    @Nested
    @DisplayName("updateUserRole()")
    class UpdateUserRoleTests {

        @Test
        @DisplayName("Updates role and evicts user from cache")
        void updatesRoleAndEvictsCache() {
            User u = user(2L, "bob", "ROLE_USER");
            when(userRepository.findById(2L)).thenReturn(Optional.of(u));
            when(userRepository.save(u)).thenReturn(u);

            RoleUpdateRequest req = new RoleUpdateRequest("ROLE_ADMIN");
            UserSummaryResponse result = adminService.updateUserRole(2L, req);

            assertEquals("ROLE_ADMIN", result.getRole());
            verify(userDetailsService).evictUser("bob");
        }

        @Test
        @DisplayName("Throws when user not found")
        void throwsForUnknownUser() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThrows(UserNotFoundException.class,
                    () -> adminService.updateUserRole(99L, new RoleUpdateRequest("ROLE_ADMIN")));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User user(Long id, String username, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("$2a$10$encoded");
        u.setRole(role);
        return u;
    }
}
