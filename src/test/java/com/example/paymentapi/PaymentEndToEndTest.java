package com.example.paymentapi;

import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PaymentEndToEndTest {
    @Autowired
    private TestRestTemplate restTemplate;
    private String token;

    @BeforeEach
    public void setUp() {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("password");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> loginEntity = new HttpEntity<>(loginRequest, headers);

        ResponseEntity<String> loginResponse = restTemplate.exchange(
                "/api/auth/login",
                HttpMethod.POST,
                loginEntity,
                String.class
        );

        token = "Bearer " + loginResponse.getBody();
    }

    @Test
    public void testCreatePayment() {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", token);
        HttpEntity<PaymentRequest> requestEntity = new HttpEntity<>(paymentRequest, headers);

        ResponseEntity<PaymentResponse> responseEntity = restTemplate.exchange(
                "/api/payments",
                HttpMethod.POST,
                requestEntity,
                PaymentResponse.class
        );

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertNotNull(responseEntity.getBody());
        assertNotNull(responseEntity.getBody().getId());
        assertEquals("COMPLETED", responseEntity.getBody().getStatus());
    }

// Other test methods...
}