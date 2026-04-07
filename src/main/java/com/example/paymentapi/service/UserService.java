package com.example.paymentapi.service;

import com.example.paymentapi.dto.RegistrationRequest;
import com.example.paymentapi.model.User;

import java.util.Optional;

public interface UserService {
    Optional<User> findByUsername(String username);

    /**
     * Registers a new user with ROLE_USER. The username must be unique.
     *
     * @throws com.example.paymentapi.exception.UsernameAlreadyExistsException if the username is taken
     */
    User register(RegistrationRequest request);

    /**
     * Changes the password for the given user after verifying the current password.
     *
     * @throws org.springframework.security.authentication.BadCredentialsException if {@code currentPassword} is wrong
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException if the user does not exist
     */
    void changePassword(String username, String currentPassword, String newPassword);
}