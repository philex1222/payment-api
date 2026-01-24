package com.example.paymentapi.config;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public CacheManager testCacheManager() {
        return new ConcurrentMapCacheManager("payments");
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        // Create a connection factory that won't actually connect
        LettuceConnectionFactory factory = new LettuceConnectionFactory();
        factory.setValidateConnection(false);
        return factory;
    }

    @Bean
    @Primary
    public RestTemplateBuilder restTemplateBuilder() {
        // Use Apache HttpClient which properly supports PATCH and handles auth errors
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplateBuilder()
                .requestFactory(() -> requestFactory);
    }
}
