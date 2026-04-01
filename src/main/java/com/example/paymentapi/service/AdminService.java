package com.example.paymentapi.service;

import com.example.paymentapi.dto.AdminStatsResponse;
import com.example.paymentapi.dto.UserSummaryResponse;
import com.example.paymentapi.dto.RoleUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminService {

    /** Returns a real-time snapshot of platform payment and user statistics. */
    AdminStatsResponse getStats();

    /** Returns a paginated list of all users (passwords never included). */
    Page<UserSummaryResponse> getUsers(Pageable pageable);

    /** Returns a single user by ID, or throws {@link com.example.paymentapi.exception.UserNotFoundException}. */
    UserSummaryResponse getUserById(Long userId);

    /** Updates the role of the user identified by {@code userId}. */
    UserSummaryResponse updateUserRole(Long userId, RoleUpdateRequest request);
}
