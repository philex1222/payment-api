package com.example.paymentapi.service;

import com.example.paymentapi.model.User;

import java.util.Optional;

public interface UserService {
    Optional<User> findByUsername(String username);
}