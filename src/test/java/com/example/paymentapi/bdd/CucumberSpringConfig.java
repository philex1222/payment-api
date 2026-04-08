package com.example.paymentapi.bdd;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.service.BankingAPIServiceImpl;
import io.cucumber.spring.CucumberContextConfiguration;
import io.restassured.RestAssured;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring context configuration for Cucumber scenarios.
 *
 * <p>Annotated with {@code @CucumberContextConfiguration} so that Cucumber
 * Spring uses this class to bootstrap the application context — once for the
 * entire Cucumber test run, shared across all scenarios.
 *
 * <p>RestAssured is configured here (not per-step-definition) because the
 * random port is only available after the context has started.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
public class CucumberSpringConfig {

    @LocalServerPort
    private int port;

    @Autowired
    private BankingAPIServiceImpl bankingService;

    @Autowired
    private CacheManager cacheManager;

    @PostConstruct
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        bankingService.resetBalances();
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }
}
