package com.example.paymentapi;

import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // Only create admin user if it doesn't exist
        if (userRepository.findByUsername("admin").isEmpty()) {
            User user = new User();
            user.setUsername("admin");
            user.setPassword(passwordEncoder.encode("password"));
            // ROLE_ADMIN grants access to payment endpoints (hasAnyRole USER/ADMIN)
            // plus actuator and /api/v1/admin/** endpoints
            user.setRole("ROLE_ADMIN");

            userRepository.save(user);
            logger.info("Created default admin user");
        } else {
            logger.info("Admin user already exists, skipping creation");
        }
    }
}