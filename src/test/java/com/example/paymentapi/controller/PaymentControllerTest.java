package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.paymentapi.controller.PaymentController.IDEMPOTENCY_KEY_HEADER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public class PaymentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private IdempotencyService idempotencyService;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    public void setUp() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("password");

        String loginResponseJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        LoginResponse loginResponse = objectMapper.readValue(loginResponseJson, LoginResponse.class);
        token = "Bearer " + loginResponse.getToken();
    }

    @Test
    public void testCreatePayment() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment123");
        paymentResponse.setStatus("PENDING");
        paymentResponse.setCreatedAt(LocalDateTime.now());

        when(paymentService.createPayment(any(PaymentRequest.class))).thenReturn(paymentResponse);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("payment123"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    public void testGetPaymentById() throws Exception {
        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment123");
        paymentResponse.setStatus("COMPLETED");
        paymentResponse.setCreatedAt(LocalDateTime.now());

        when(paymentService.getPaymentById("payment123")).thenReturn(paymentResponse);

        mockMvc.perform(get("/api/v1/payments/payment123")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("payment123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    public void testCreatePayment_ValidationError() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        // Missing required fields

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_firstRequest_processesAndStores() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment123");
        paymentResponse.setStatus("COMPLETED");
        paymentResponse.setCreatedAt(LocalDateTime.now());

        when(idempotencyService.get("key-abc")).thenReturn(Optional.empty());
        when(paymentService.createPayment(any(PaymentRequest.class))).thenReturn(paymentResponse);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("payment123"));

        verify(idempotencyService).store(eq("key-abc"), any(PaymentResponse.class));
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_duplicateRequest_returnsCachedResponse() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentResponse cached = new PaymentResponse();
        cached.setId("payment123");
        cached.setStatus("COMPLETED");
        cached.setCreatedAt(LocalDateTime.now());

        when(idempotencyService.get("key-abc")).thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("payment123"))
                .andExpect(header().exists("Warning"));

        // Payment service must NOT be called on a replay
        verify(paymentService, never()).createPayment(any());
    }

    @Test
    public void testCreatePayment_withoutIdempotencyKey_skipsIdempotencyCheck() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment456");
        paymentResponse.setStatus("COMPLETED");

        when(paymentService.createPayment(any(PaymentRequest.class))).thenReturn(paymentResponse);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("payment456"));

        verify(idempotencyService, never()).get(any());
        verify(idempotencyService, never()).store(any(), any());
    }

    @Test
    public void testGetPayments_noFilters_returnsPage() throws Exception {
        PaymentResponse p = new PaymentResponse();
        p.setId("p1");
        p.setStatus("COMPLETED");
        p.setCreatedAt(LocalDateTime.now());

        when(paymentService.getPayments(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("p1"));
    }

    @Test
    public void testGetPayments_withStatusFilter_passesFilterToService() throws Exception {
        when(paymentService.getPayments(eq("FAILED"), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/api/v1/payments?status=FAILED")
                        .header("Authorization", token))
                .andExpect(status().isOk());

        verify(paymentService).getPayments(eq("FAILED"), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    public void testCancelPayment_pendingPayment_returns200() throws Exception {
        PaymentResponse cancelled = new PaymentResponse();
        cancelled.setId("pay-1");
        cancelled.setStatus("CANCELLED");
        cancelled.setCreatedAt(LocalDateTime.now());

        when(paymentService.cancelPayment("pay-1")).thenReturn(cancelled);

        mockMvc.perform(post("/api/v1/payments/pay-1/cancel")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}