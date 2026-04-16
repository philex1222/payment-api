package com.example.paymentapi.service;

import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed implementation of {@link IdempotencyService}.
 *
 * <p>Keys are stored under the prefix {@code idempotency:} with a 24-hour TTL.
 * All Redis and serialisation errors are caught and logged; the service degrades
 * gracefully so a Redis outage never blocks payment processing.
 */
@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyServiceImpl.class);
    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PaymentWorkflowResponse> get(String idempotencyKey) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, PaymentWorkflowResponse.class));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to deserialise cached idempotency response for key {}: {}",
                    idempotencyKey, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Idempotency check unavailable for key {}, proceeding without deduplication: {}",
                    idempotencyKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(String idempotencyKey, PaymentWorkflowResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, json, TTL);
            logger.debug("Stored idempotency response for key {} (TTL={})", idempotencyKey, TTL);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialise idempotency response for key {}: {}",
                    idempotencyKey, e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to store idempotency response for key {}: {}",
                    idempotencyKey, e.getMessage());
        }
    }
}
