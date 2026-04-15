package com.example.paymentapi.service.shared;

import com.example.paymentapi.exception.InvalidStatusTransitionException;
import com.example.paymentapi.model.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single authoritative source for all payment status transitions.
 * Delegates validity checks to PaymentStatus.canTransitionTo() and
 * throws InvalidStatusTransitionException on illegal moves.
 */
@Component
public class PaymentStateMachine {

    private static final Logger logger = LoggerFactory.getLogger(PaymentStateMachine.class);

    /**
     * Asserts that {@code current} can transition to {@code target}.
     * Throws {@link InvalidStatusTransitionException} if the transition is illegal.
     */
    public void assertCanTransitionTo(String paymentId, PaymentStatus current, PaymentStatus target) {
        if (!current.canTransitionTo(target)) {
            logger.warn("Illegal transition for payment {}: {} -> {}", paymentId, current, target);
            throw new InvalidStatusTransitionException(paymentId, current, target);
        }
    }

    /**
     * Validates and logs the transition. Equivalent to {@link #assertCanTransitionTo}
     * but named for use in state-update contexts where "transition" reads more naturally.
     */
    public void transition(String paymentId, PaymentStatus current, PaymentStatus target) {
        assertCanTransitionTo(paymentId, current, target);
        logger.debug("Payment {} transitioning {} -> {}", paymentId, current, target);
    }
}
