package com.example.paymentapi.dto;

import com.example.paymentapi.model.User;

/**
 * User representation for admin list endpoint — password field intentionally excluded.
 */
public class UserSummaryResponse {

    private Long id;
    private String username;
    private String role;

    public UserSummaryResponse() {}

    public UserSummaryResponse(Long id, String username, String role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getRole());
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
}
