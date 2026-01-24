package com.example.paymentapi;

import com.example.paymentapi.config.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PaymentApplicationTests {
    @Test
    void contextLoads() {
    }
}