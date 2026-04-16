package com.example.paymentapi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Consolidated resilience/infrastructure configuration — combines:
 *  - CacheConfig (Redis cache manager with named cache TTLs)
 *  - SchedulingConfig (@EnableScheduling)
 *  - AsyncConfig (@EnableAsync + taskExecutor)
 *
 * Cache name constants are defined here and referenced from service classes
 * to avoid magic strings.
 */
@Configuration
@EnableCaching
@EnableScheduling
@EnableAsync
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ResilienceConfig {

    // Cache name constants — referenced from service layer to avoid magic strings
    public static final String CACHE_PAYMENT_DETAIL    = "payment-detail";
    public static final String CACHE_USER_PAYMENT_LIST = "user-payment-list";
    public static final String CACHE_IDEMPOTENCY       = "idempotency";

    /**
     * Base serialization config reused by every named cache configuration.
     * JSON serialization ensures type safety across Redis restarts.
     */
    private RedisCacheConfiguration baseCacheConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()));
    }

    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
    public RedisCacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        // "payments" — individual payment lookups; 10-minute TTL balances freshness
        // vs. reducing DB read pressure on high-frequency GET /payments/{id} calls.
        RedisCacheConfiguration paymentsConfig = baseCacheConfig()
                .entryTtl(Duration.ofMinutes(10));

        // "payment-detail" — payment detail cache (60s TTL)
        RedisCacheConfiguration paymentDetailConfig = baseCacheConfig()
                .entryTtl(Duration.ofSeconds(60));

        // "user-payment-list" — list query cache (30s TTL)
        RedisCacheConfiguration userPaymentListConfig = baseCacheConfig()
                .entryTtl(Duration.ofSeconds(30));

        // "users" — UserDetails for JWT validation; 60-minute TTL is safe because
        // user role/password changes are rare and cache is evicted on update.
        RedisCacheConfiguration usersConfig = baseCacheConfig()
                .entryTtl(Duration.ofMinutes(60));

        // "idempotency" — idempotency key cache (24h TTL)
        RedisCacheConfiguration idempotencyConfig = baseCacheConfig()
                .entryTtl(Duration.ofHours(24));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("payments", paymentsConfig);
        cacheConfigurations.put(CACHE_PAYMENT_DETAIL, paymentDetailConfig);
        cacheConfigurations.put(CACHE_USER_PAYMENT_LIST, userPaymentListConfig);
        cacheConfigurations.put("users", usersConfig);
        cacheConfigurations.put(CACHE_IDEMPOTENCY, idempotencyConfig);

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(baseCacheConfig().entryTtl(Duration.ofMinutes(30)))
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * Bounded thread pool for @Async tasks (e.g. NotificationService).
     * On Java 21 with {@code spring.threads.virtual.enabled=true} Spring Boot
     * auto-configures a virtual-thread executor; this bean acts as a fallback
     * for environments where virtual threads are disabled.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        // Run in the caller's thread when the queue is full — never drop tasks
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
