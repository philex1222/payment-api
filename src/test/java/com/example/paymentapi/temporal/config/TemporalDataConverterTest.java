package com.example.paymentapi.temporal.config;

import com.example.paymentapi.dto.PaymentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the production {@link TemporalDataConverter}.
 *
 * Covers the edge cases that the SDK default converter fails on:
 *  - BigDecimal precision (no scientific notation in payloads)
 *  - java.time.LocalDateTime (JavaTimeModule registered)
 *  - Unknown properties tolerance (forward-compat with DTO evolution)
 */
class TemporalDataConverterTest {

    private final DataConverter converter = TemporalDataConverter.get();

    @Test
    void roundTrip_paymentRequest_preservesBigDecimalPrecision() {
        BigDecimal amount = new BigDecimal("12345.6789");
        PaymentRequest original = new PaymentRequest(
                "1234567890", "0987654321", amount, "USD", null);

        Optional<Payload> payload = converter.toPayload(original);
        assertTrue(payload.isPresent());

        PaymentRequest restored = converter.fromPayload(payload.get(), PaymentRequest.class, PaymentRequest.class);
        assertEquals(amount, restored.getAmount());
        assertEquals("1234567890", restored.getSourceAccount());
    }

    @Test
    void roundTrip_bigDecimal_writesAsPlainString() throws Exception {
        BigDecimal tiny = new BigDecimal("0.0000001");
        Optional<Payload> payload = converter.toPayload(tiny);
        assertTrue(payload.isPresent());
        String raw = payload.get().getData().toStringUtf8();
        // If WRITE_BIGDECIMAL_AS_PLAIN were off, we'd get "1E-7".
        assertFalse(raw.contains("E"), "BigDecimal should be plain, not scientific: " + raw);
        assertTrue(raw.contains("0.0000001"));
    }

    @Test
    void roundTrip_localDateTime_preservesValue() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 18, 10, 30, 45);
        Optional<Payload> payload = converter.toPayload(now);
        assertTrue(payload.isPresent());
        LocalDateTime restored = converter.fromPayload(payload.get(), LocalDateTime.class, LocalDateTime.class);
        assertEquals(now, restored);
    }

    @Test
    void objectMapper_writesIsoDatesNotEpochMillis() throws Exception {
        ObjectMapper om = TemporalDataConverter.objectMapper();
        String json = om.writeValueAsString(LocalDateTime.of(2026, 4, 18, 12, 0, 0));
        // Millis form would be a big integer; we expect an ISO-8601 string.
        assertTrue(json.startsWith("\"2026-04-18"), "Expected ISO date, got: " + json);
    }

    @Test
    void objectMapper_ignoresUnknownProperties() throws Exception {
        ObjectMapper om = TemporalDataConverter.objectMapper();
        String json = "{\"sourceAccount\":\"1234567890\",\"destinationAccount\":\"0987654321\","
                + "\"amount\":100,\"currency\":\"USD\",\"unknownField\":\"whatever\"}";
        PaymentRequest request = om.readValue(json, PaymentRequest.class);
        assertEquals("1234567890", request.getSourceAccount());
    }

    @Test
    void roundTrip_null_handledByNullConverter() {
        Optional<Payload> payload = converter.toPayload(null);
        assertTrue(payload.isPresent());
        Object restored = converter.fromPayload(payload.get(), Object.class, Object.class);
        assertNull(restored);
    }
}
