package com.example.paymentapi.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentStatus Enum Tests")
class PaymentStatusTest {

    @Nested
    @DisplayName("Valid Transitions Tests")
    class ValidTransitionsTests {

        @Test
        @DisplayName("PENDING should have correct valid transitions")
        void testPendingTransitions() {
            Set<PaymentStatus> validTransitions = PaymentStatus.PENDING.getValidTransitions();

            assertTrue(validTransitions.contains(PaymentStatus.PROCESSING));
            assertTrue(validTransitions.contains(PaymentStatus.CANCELLED));
            assertTrue(validTransitions.contains(PaymentStatus.FAILED));
            assertFalse(validTransitions.contains(PaymentStatus.COMPLETED));
            assertFalse(validTransitions.contains(PaymentStatus.REVERSED));
        }

        @Test
        @DisplayName("PROCESSING should have correct valid transitions")
        void testProcessingTransitions() {
            Set<PaymentStatus> validTransitions = PaymentStatus.PROCESSING.getValidTransitions();

            assertTrue(validTransitions.contains(PaymentStatus.COMPLETED));
            assertTrue(validTransitions.contains(PaymentStatus.FAILED));
            assertFalse(validTransitions.contains(PaymentStatus.PENDING));
        }

        @Test
        @DisplayName("COMPLETED should have correct valid transitions")
        void testCompletedTransitions() {
            Set<PaymentStatus> validTransitions = PaymentStatus.COMPLETED.getValidTransitions();

            assertTrue(validTransitions.contains(PaymentStatus.REVERSED));
            assertTrue(validTransitions.contains(PaymentStatus.REFUNDED));
            assertFalse(validTransitions.contains(PaymentStatus.PENDING));
            assertFalse(validTransitions.contains(PaymentStatus.PROCESSING));
        }

        @Test
        @DisplayName("FAILED should allow retry to PENDING")
        void testFailedTransitions() {
            Set<PaymentStatus> validTransitions = PaymentStatus.FAILED.getValidTransitions();

            assertTrue(validTransitions.contains(PaymentStatus.PENDING));
        }

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "REVERSED", "REFUNDED"})
        @DisplayName("Terminal states should have no valid transitions")
        void testTerminalStates(PaymentStatus status) {
            Set<PaymentStatus> validTransitions = status.getValidTransitions();

            assertTrue(validTransitions.isEmpty());
        }
    }

    @Nested
    @DisplayName("canTransitionTo Tests")
    class CanTransitionToTests {

        @ParameterizedTest
        @CsvSource({
                "PENDING, PROCESSING, true",
                "PENDING, CANCELLED, true",
                "PENDING, FAILED, true",
                "PENDING, COMPLETED, false",
                "PROCESSING, COMPLETED, true",
                "PROCESSING, FAILED, true",
                "PROCESSING, PENDING, false",
                "COMPLETED, REVERSED, true",
                "COMPLETED, REFUNDED, true",
                "COMPLETED, PENDING, false",
                "FAILED, PENDING, true",
                "FAILED, COMPLETED, false",
                "CANCELLED, PENDING, false",
                "REVERSED, COMPLETED, false",
                "REFUNDED, COMPLETED, false"
        })
        @DisplayName("Should correctly determine if transition is valid")
        void testCanTransitionTo(PaymentStatus from, PaymentStatus to, boolean expected) {
            assertEquals(expected, from.canTransitionTo(to));
        }
    }

    @Nested
    @DisplayName("isTerminal Tests")
    class IsTerminalTests {

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "REVERSED", "REFUNDED"})
        @DisplayName("Should identify terminal states correctly")
        void testIsTerminal_TerminalStates(PaymentStatus status) {
            assertTrue(status.isTerminal());
        }

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, names = {"PENDING", "PROCESSING", "COMPLETED", "FAILED"})
        @DisplayName("Should identify non-terminal states correctly")
        void testIsTerminal_NonTerminalStates(PaymentStatus status) {
            assertFalse(status.isTerminal());
        }
    }

    @Nested
    @DisplayName("fromString Tests")
    class FromStringTests {

        @ParameterizedTest
        @EnumSource(PaymentStatus.class)
        @DisplayName("Should parse all enum values correctly")
        void testFromString_AllValues(PaymentStatus status) {
            assertEquals(status, PaymentStatus.fromString(status.getCode()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"pending", "PENDING", "Pending", "pEnDiNg"})
        @DisplayName("Should parse case-insensitively")
        void testFromString_CaseInsensitive(String input) {
            assertEquals(PaymentStatus.PENDING, PaymentStatus.fromString(input));
        }

        @Test
        @DisplayName("Should handle whitespace")
        void testFromString_Whitespace() {
            assertEquals(PaymentStatus.PENDING, PaymentStatus.fromString("  PENDING  "));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should throw exception for null and empty strings")
        void testFromString_NullAndEmpty(String input) {
            assertThrows(IllegalArgumentException.class, () -> PaymentStatus.fromString(input));
        }

        @Test
        @DisplayName("Should throw exception for invalid status")
        void testFromString_Invalid() {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> PaymentStatus.fromString("INVALID"));

            assertTrue(exception.getMessage().contains("Invalid payment status"));
        }
    }

    @Nested
    @DisplayName("isValid Tests")
    class IsValidTests {

        @ParameterizedTest
        @EnumSource(PaymentStatus.class)
        @DisplayName("Should return true for all valid enum values")
        void testIsValid_AllValues(PaymentStatus status) {
            assertTrue(PaymentStatus.isValid(status.getCode()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"pending", "COMPLETED", "Failed"})
        @DisplayName("Should be case-insensitive")
        void testIsValid_CaseInsensitive(String status) {
            assertTrue(PaymentStatus.isValid(status));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return false for null and empty")
        void testIsValid_NullAndEmpty(String status) {
            assertFalse(PaymentStatus.isValid(status));
        }

        @Test
        @DisplayName("Should return false for invalid status")
        void testIsValid_Invalid() {
            assertFalse(PaymentStatus.isValid("INVALID"));
            assertFalse(PaymentStatus.isValid("UNKNOWN"));
        }
    }

    @Nested
    @DisplayName("Properties Tests")
    class PropertiesTests {

        @ParameterizedTest
        @EnumSource(PaymentStatus.class)
        @DisplayName("All statuses should have non-null code and description")
        void testProperties_NotNull(PaymentStatus status) {
            assertNotNull(status.getCode());
            assertNotNull(status.getDescription());
            assertFalse(status.getCode().isEmpty());
            assertFalse(status.getDescription().isEmpty());
        }

        @Test
        @DisplayName("Code should match enum name")
        void testCode_MatchesEnumName() {
            for (PaymentStatus status : PaymentStatus.values()) {
                assertEquals(status.name(), status.getCode());
            }
        }
    }
}
