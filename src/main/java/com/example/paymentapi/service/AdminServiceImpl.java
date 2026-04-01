package com.example.paymentapi.service;

import com.example.paymentapi.dto.AdminStatsResponse;
import com.example.paymentapi.dto.RoleUpdateRequest;
import com.example.paymentapi.dto.UserSummaryResponse;
import com.example.paymentapi.exception.UserNotFoundException;
import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final UserDetailsServiceImpl userDetailsService;

    public AdminServiceImpl(UserRepository userRepository,
                            PaymentRepository paymentRepository,
                            UserDetailsServiceImpl userDetailsService) {
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public AdminStatsResponse getStats() {
        // Payment counts grouped by status
        List<Object[]> rows = paymentRepository.countGroupedByStatus();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long totalPayments = 0;
        for (Object[] row : rows) {
            String status = (String) row[0];
            Long count = (Long) row[1];
            byStatus.put(status, count);
            totalPayments += count;
        }

        // Today's payment count and volume (midnight → now)
        LocalDateTime startOfToday = LocalDate.now().atTime(LocalTime.MIDNIGHT);
        Object[] todayStats = paymentRepository.getTodayStats(startOfToday);
        long todayCount = todayStats[0] instanceof Long l ? l : ((Number) todayStats[0]).longValue();
        BigDecimal todayVolume = (BigDecimal) todayStats[1];

        long totalUsers = userRepository.count();

        return new AdminStatsResponse(totalPayments, byStatus, todayCount, todayVolume,
                totalUsers, LocalDateTime.now());
    }

    @Override
    public Page<UserSummaryResponse> getUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserSummaryResponse::from);
    }

    @Override
    @Transactional
    public UserSummaryResponse updateUserRole(Long userId, RoleUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        user.setRole(request.getRole());
        User saved = userRepository.save(user);

        // Evict cached UserDetails so the role change takes effect on the next request
        userDetailsService.evictUser(saved.getUsername());

        return UserSummaryResponse.from(saved);
    }
}
