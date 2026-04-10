package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.*;
import com.example.paymentapi.service.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
class WebhookControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookService webhookService;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        userToken  = obtainToken("user",  "password");
        adminToken = obtainToken("admin", "password");
    }

    private String obtainToken(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private WebhookSubscriptionResponse sampleResponse(String id) {
        return WebhookSubscriptionResponse.builder()
                .id(id)
                .userId(1L)
                .targetUrl("http://example.com/hook")
                .bearerToken("***")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createSubscription_returns201() throws Exception {
        when(webhookService.createSubscription(any(), eq("user"))).thenReturn(sampleResponse("sub-1"));

        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("token")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sub-1"))
                .andExpect(jsonPath("$.bearerToken").value("***"));
    }

    @Test
    void createSubscription_missingTargetUrl_returns400() throws Exception {
        String body = "{\"bearerToken\":\"tok\",\"eventTypes\":[\"PAYMENT_COMPLETED\"]}";

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSubscription_invalidUrl_returns400() throws Exception {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("not-a-url")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listSubscriptions_returns200WithList() throws Exception {
        when(webhookService.listSubscriptions(eq("user"), eq(false)))
                .thenReturn(List.of(sampleResponse("sub-1")));

        mockMvc.perform(get("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sub-1"));
    }

    @Test
    void getSubscription_notFound_returns404() throws Exception {
        when(webhookService.getSubscription(eq("bad-id"), eq("user"), eq(false)))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/v1/webhooks/bad-id")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSubscription_returns204() throws Exception {
        doNothing().when(webhookService).deleteSubscription(eq("sub-1"), eq("admin"), eq(true));

        mockMvc.perform(delete("/api/v1/webhooks/sub-1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void getDeliveries_adminOnly_returns200() throws Exception {
        when(webhookService.getDeliveries("sub-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/webhooks/sub-1/deliveries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void getDeliveries_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/sub-1/deliveries")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks"))
                .andExpect(status().isUnauthorized());
    }
}
