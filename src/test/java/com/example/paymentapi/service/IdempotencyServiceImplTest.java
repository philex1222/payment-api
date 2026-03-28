package com.example.paymentapi.service;

import com.example.paymentapi.dto.PaymentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ObjectMapper objectMapper;

    @InjectMocks IdempotencyServiceImpl idempotencyService;

    private static final String KEY = "test-idempotency-key";
    private static final String REDIS_KEY = "idempotency:" + KEY;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    class GetTests {

        @Test
        void get_whenKeyExists_returnsDeserializedResponse() throws Exception {
            PaymentResponse expected = PaymentResponse.builder()
                    .id("pay-1").status("COMPLETED").amount(BigDecimal.TEN).build();
            String json = "{\"id\":\"pay-1\"}";
            when(valueOps.get(REDIS_KEY)).thenReturn(json);
            when(objectMapper.readValue(json, PaymentResponse.class)).thenReturn(expected);

            Optional<PaymentResponse> result = idempotencyService.get(KEY);

            assertThat(result).isPresent().contains(expected);
        }

        @Test
        void get_whenKeyAbsent_returnsEmpty() {
            when(valueOps.get(REDIS_KEY)).thenReturn(null);

            assertThat(idempotencyService.get(KEY)).isEmpty();
        }

        @Test
        void get_whenRedisThrows_degradesGracefullyAndReturnsEmpty() {
            when(valueOps.get(REDIS_KEY)).thenThrow(new RuntimeException("Redis connection refused"));

            assertThat(idempotencyService.get(KEY)).isEmpty();
        }

        @Test
        void get_whenDeserializationFails_returnsEmpty() throws Exception {
            when(valueOps.get(REDIS_KEY)).thenReturn("{bad-json}");
            when(objectMapper.readValue("{bad-json}", PaymentResponse.class))
                    .thenThrow(mock(JsonProcessingException.class));

            assertThat(idempotencyService.get(KEY)).isEmpty();
        }
    }

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class StoreTests {

        @Test
        void store_serialisesAndSetsWithTTL() throws Exception {
            PaymentResponse response = PaymentResponse.builder().id("pay-1").build();
            String json = "{\"id\":\"pay-1\"}";
            when(objectMapper.writeValueAsString(response)).thenReturn(json);

            idempotencyService.store(KEY, response);

            verify(valueOps).set(REDIS_KEY, json, Duration.ofHours(24));
        }

        @Test
        void store_whenRedisThrows_doesNotPropagateException() throws Exception {
            PaymentResponse response = PaymentResponse.builder().id("pay-1").build();
            when(objectMapper.writeValueAsString(response)).thenReturn("{}");
            doThrow(new RuntimeException("Redis down")).when(valueOps)
                    .set(anyString(), anyString(), any(Duration.class));

            // Should not throw — graceful degradation
            idempotencyService.store(KEY, response);
        }

        @Test
        void store_whenSerializationFails_doesNotPropagateException() throws Exception {
            PaymentResponse response = PaymentResponse.builder().id("pay-1").build();
            // doThrow is more reliable than when().thenThrow() for checked exceptions
            doThrow(new RuntimeException("serialization failed"))
                    .when(objectMapper).writeValueAsString(any());

            // Should not throw
            idempotencyService.store(KEY, response);

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }
}
