package com.example.paymentapi.service;

import com.example.paymentapi.dto.RegistrationRequest;
import com.example.paymentapi.exception.UsernameAlreadyExistsException;
import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl Tests")
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserServiceImpl(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Should return user when username exists")
    void findByUsername_found() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("hashed");
        user.setRole("ROLE_ADMIN");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        Optional<User> result = userService.findByUsername("admin");

        assertTrue(result.isPresent());
        assertEquals("admin", result.get().getUsername());
        verify(userRepository).findByUsername("admin");
    }

    @Test
    @DisplayName("Should return empty Optional when username does not exist")
    void findByUsername_notFound() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        Optional<User> result = userService.findByUsername("unknown");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("Should delegate to repository exactly once")
    void findByUsername_delegatesToRepository() {
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());

        userService.findByUsername("testuser");

        verify(userRepository, times(1)).findByUsername("testuser");
        verifyNoMoreInteractions(userRepository);
    }

    // ── changePassword ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("changePassword succeeds when current password matches")
    void changePassword_correctCurrentPassword_savesNewHash() {
        String rawCurrent = "oldPass1";
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode(rawCurrent));
        user.setRole("ROLE_USER");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        userService.changePassword("alice", rawCurrent, "newSecurePass");

        verify(userRepository).save(user);
        assertTrue(passwordEncoder.matches("newSecurePass", user.getPassword()),
                "Saved password hash should match the new password");
    }

    @Test
    @DisplayName("changePassword throws BadCredentialsException when current password is wrong")
    void changePassword_wrongCurrentPassword_throws() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode("realPassword"));
        user.setRole("ROLE_USER");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class,
                () -> userService.changePassword("alice", "wrongPassword", "newPass123"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword throws UsernameNotFoundException when user does not exist")
    void changePassword_unknownUser_throws() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> userService.changePassword("ghost", "any", "newPass123"));
    }

    // ── register ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register saves user with ROLE_USER and encoded password")
    void register_newUsername_savesUserWithRoleUser() {
        RegistrationRequest request = new RegistrationRequest("newuser", "SecurePass123");
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User saved = i.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        User result = userService.register(request);

        assertEquals("newuser", result.getUsername());
        assertEquals("ROLE_USER", result.getRole());
        assertTrue(passwordEncoder.matches("SecurePass123", result.getPassword()),
                "Saved password hash should match the raw password");
        verify(userRepository).existsByUsername("newuser");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("register throws UsernameAlreadyExistsException for duplicate username")
    void register_duplicateUsername_throwsUsernameAlreadyExistsException() {
        RegistrationRequest request = new RegistrationRequest("existing", "SecurePass123");
        when(userRepository.existsByUsername("existing")).thenReturn(true);

        assertThrows(UsernameAlreadyExistsException.class, () -> userService.register(request));

        verify(userRepository, never()).save(any());
    }
}
