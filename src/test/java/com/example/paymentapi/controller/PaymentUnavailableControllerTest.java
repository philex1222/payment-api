package com.example.paymentapi.controller;

import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "temporal.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Payment unavailable fallback")
class PaymentUnavailableControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createPayment_whenTemporalDisabled_returns503() throws Exception {
        String token = loginAsUser();
        PaymentRequest request = new PaymentRequest(
                "1234567890",
                "0987654321",
                BigDecimal.valueOf(25),
                "USD",
                "temporal disabled smoke");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message")
                        .value("Payment workflow processing is unavailable because Temporal is disabled"));
    }

    private String loginAsUser() throws Exception {
        LoginRequest request = new LoginRequest("user", "password");
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return "Bearer " + objectMapper.readValue(json, LoginResponse.class).getToken();
    }
}
