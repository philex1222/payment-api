package com.example.paymentapi;

import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // Create a user with the desired username, password, and role
        User user = new User();
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole("ROLE_USER");

        // Save the user to the database
        userRepository.save(user);
    }
}