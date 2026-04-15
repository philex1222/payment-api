package com.example.paymentapi.service.shared;

import com.example.paymentapi.model.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class PaymentSecurityHelper {

    private static final Logger logger = LoggerFactory.getLogger(PaymentSecurityHelper.class);

    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
    }

    public boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    public void checkOwnership(Payment payment) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return;
        if (isCurrentUserAdmin()) return;
        String username = auth.getName();
        if (payment.getCreatedBy() == null || !payment.getCreatedBy().equals(username)) {
            logger.warn("Access denied: user '{}' attempted to access payment '{}' owned by '{}'",
                    username, payment.getId(), payment.getCreatedBy());
            throw new AccessDeniedException("Access denied: you do not own this payment");
        }
    }
}
