package com.example.paymentapi.service.shared;

import com.example.paymentapi.exception.InvalidStatusTransitionException;
import com.example.paymentapi.model.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PaymentStateMachineTest {

    private final PaymentStateMachine machine = new PaymentStateMachine();

    @Test
    void transition_succeeds_forValidMove() {
        assertThatNoException().isThrownBy(() ->
                machine.transition("pay-1", PaymentStatus.PENDING, PaymentStatus.PROCESSING));
    }

    @Test
    void transition_throws_forInvalidMove() {
        assertThatThrownBy(() ->
                machine.transition("pay-1", PaymentStatus.COMPLETED, PaymentStatus.PENDING))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void transition_throws_fromTerminalStatus() {
        assertThatThrownBy(() ->
                machine.transition("pay-1", PaymentStatus.CANCELLED, PaymentStatus.PENDING))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void assertCanTransitionTo_passes_forValidTarget() {
        assertThatNoException().isThrownBy(() ->
                machine.assertCanTransitionTo("pay-1", PaymentStatus.COMPLETED, PaymentStatus.REVERSED));
    }

    @Test
    void assertCanTransitionTo_throws_forInvalidTarget() {
        assertThatThrownBy(() ->
                machine.assertCanTransitionTo("pay-1", PaymentStatus.PENDING, PaymentStatus.REVERSED))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }
}
