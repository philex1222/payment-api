package com.example.paymentapi.service.shared;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.paymentapi.model.Payment;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PaymentSecurityHelperTest {

    private final PaymentSecurityHelper helper = new PaymentSecurityHelper();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUsername_returnsName_whenAuthenticated() {
        setAuth("alice", "ROLE_USER");
        assertThat(helper.currentUsername()).isEqualTo("alice");
    }

    @Test
    void currentUsername_returnsAnonymous_whenNoContext() {
        assertThat(helper.currentUsername()).isEqualTo("anonymous");
    }

    @Test
    void isCurrentUserAdmin_returnsTrue_forAdminRole() {
        setAuth("admin", "ROLE_ADMIN");
        assertThat(helper.isCurrentUserAdmin()).isTrue();
    }

    @Test
    void isCurrentUserAdmin_returnsFalse_forUserRole() {
        setAuth("alice", "ROLE_USER");
        assertThat(helper.isCurrentUserAdmin()).isFalse();
    }

    @Test
    void checkOwnership_passes_forAdmin() {
        setAuth("admin", "ROLE_ADMIN");
        Payment p = new Payment();
        p.setCreatedBy("alice");
        assertThatNoException().isThrownBy(() -> helper.checkOwnership(p));
    }

    @Test
    void checkOwnership_passes_forOwner() {
        setAuth("alice", "ROLE_USER");
        Payment p = new Payment();
        p.setCreatedBy("alice");
        assertThatNoException().isThrownBy(() -> helper.checkOwnership(p));
    }

    @Test
    void checkOwnership_throws_forNonOwner() {
        setAuth("bob", "ROLE_USER");
        Payment p = new Payment();
        p.setCreatedBy("alice");
        assertThatThrownBy(() -> helper.checkOwnership(p))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void setAuth(String username, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
