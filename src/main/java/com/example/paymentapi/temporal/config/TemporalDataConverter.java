package com.example.paymentapi.temporal.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.common.converter.NullPayloadConverter;
import io.temporal.common.converter.ByteArrayPayloadConverter;
import io.temporal.common.converter.ProtobufJsonPayloadConverter;
import io.temporal.common.converter.ProtobufPayloadConverter;

/**
 * Production-grade {@link DataConverter} for Temporal payloads.
 *
 * Replaces the SDK default — which uses a bare Jackson {@link ObjectMapper} that does not
 * know about Java 8 dates or {@link java.math.BigDecimal} precision — with an explicitly
 * configured mapper that:
 * <ul>
 *   <li>Registers {@link JavaTimeModule} so {@code LocalDateTime} / {@code Instant} round-trip
 *       losslessly through workflow history.</li>
 *   <li>Writes dates as ISO-8601 strings (not millis) so event history is human-readable.</li>
 *   <li>Writes {@link java.math.BigDecimal} as a plain string to avoid scientific-notation
 *       artifacts (e.g. {@code 1E+2}) in stored payloads.</li>
 *   <li>Does not fail on unknown properties — workflow activities can evolve their DTOs
 *       without requiring a full drain-and-redeploy.</li>
 * </ul>
 *
 * Ordering matters: null, byte[], protobuf converters run before JSON so structured payloads
 * are preferred over JSON stringification.
 */
public final class TemporalDataConverter {

    private TemporalDataConverter() {}

    public static DataConverter get() {
        return DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(
                        new NullPayloadConverter(),
                        new ByteArrayPayloadConverter(),
                        new ProtobufJsonPayloadConverter(),
                        new ProtobufPayloadConverter(),
                        new JacksonJsonPayloadConverter(objectMapper()));
    }

    static ObjectMapper objectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return om;
    }
}
