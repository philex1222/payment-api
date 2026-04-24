package com.example.paymentapi;

import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void run_seedsLocalFixtureUsersWhenMissing() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));

        new DataInitializer(userRepository, passwordEncoder).run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(3)).save(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(User::getUsername)
                .containsExactly("admin", "user", "demo");
        assertThat(captor.getAllValues())
                .extracting(User::getRole)
                .containsExactly("ROLE_ADMIN", "ROLE_USER", "ROLE_USER");
    }

    @Test
    void run_doesNotOverwriteExistingFixtureUsers() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(new User()));

        new DataInitializer(userRepository, passwordEncoder).run();

        verify(userRepository, never()).save(any());
    }
}
