package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.command.CancellationHandler;
import com.example.paymentapi.service.command.PaymentLifecycleHandler;
import com.example.paymentapi.service.command.ReversalHandler;
import com.example.paymentapi.service.query.PaymentQueryService;
import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.paymentapi.controller.PaymentController.IDEMPOTENCY_KEY_HEADER;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private ReversalHandler reversalHandler;

    @MockitoBean
    private CancellationHandler cancellationHandler;

    @MockitoBean
    private PaymentLifecycleHandler lifecycleHandler;

    @MockitoBean
    private PaymentQueryService queryService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private TransactionService transactionService;

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

    private WorkflowStub stubWorkflowStub() {
        WorkflowStub mockStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(
                eq("PaymentCreationWorkflow"), any())).thenReturn(mockStub);
        return mockStub;
    }

    @Test
    public void testCreatePayment() throws Exception {
        stubWorkflowStub();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
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
        stubWorkflowStub();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        when(idempotencyService.get("key-abc")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty());

        verify(idempotencyService).store(eq("key-abc"), any(PaymentWorkflowResponse.class));
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_duplicateRequest_returnsCachedResponse() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentWorkflowResponse cached = new PaymentWorkflowResponse("payment-cached-xyz", "PENDING", null);
        when(idempotencyService.get("key-abc")).thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value("payment-cached-xyz"));

        verify(workflowClient, never()).newUntypedWorkflowStub(anyString(), any(io.temporal.client.WorkflowOptions.class));
    }

    @Test
    public void testGetPaymentById() throws Exception {
        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment123");
        paymentResponse.setStatus("COMPLETED");
        paymentResponse.setCreatedAt(LocalDateTime.now());

        when(queryService.findById("payment123")).thenReturn(paymentResponse);

        mockMvc.perform(get("/api/v1/payments/payment123")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("payment123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    public void testGetPayments() throws Exception {
        when(queryService.findAll(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        mockMvc.perform(get("/api/v1/payments")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }
}
