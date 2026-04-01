package com.example.paymentapi.service;

import com.example.paymentapi.model.User;

import java.util.Optional;

public interface UserService {
    Optional<User> findByUsername(String username);

    /**
     * Changes the password for the given user after verifying the current password.
     *
     * @throws org.springframework.security.authentication.BadCredentialsException if {@code currentPassword} is wrong
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException if the user does not exist
     */
    void changePassword(String username, String currentPassword, String newPassword);
}