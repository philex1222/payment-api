package com.example.paymentapi.service;

import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Caches UserDetails by username to reduce DB round-trips on every JWT-authenticated
     * request. TTL is 60 minutes (configured in CacheConfig). Call evictUser() on any
     * role or password change to prevent stale credentials from being accepted.
     */
    @Override
    @Cacheable(value = "users", key = "#username")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with username: " + username));

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority(user.getRole()))
        );
    }

    /**
     * Evicts the cached UserDetails for the given username.
     * Must be called whenever a user's role, password, or enabled state changes.
     */
    @CacheEvict(value = "users", key = "#username")
    public void evictUser(String username) {
        // Spring AOP handles the eviction; no body required.
    }
}
