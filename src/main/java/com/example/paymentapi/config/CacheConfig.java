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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

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

        // "users" — UserDetails for JWT validation; 60-minute TTL is safe because
        // user role/password changes are rare and cache is evicted on update.
        RedisCacheConfiguration usersConfig = baseCacheConfig()
                .entryTtl(Duration.ofMinutes(60));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("payments", paymentsConfig);
        cacheConfigurations.put("users", usersConfig);

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(baseCacheConfig().entryTtl(Duration.ofMinutes(30)))
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
