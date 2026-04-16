package com.example.paymentapi.temporal;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full round-trip integration tests for the Temporal payment workflow.
 *
 * <p>Tests the complete path: HTTP POST /api/v1/payments → Temporal workflow
 * (via TestWorkflowEnvironment) → activity execution → persistence → HTTP GET
 * /api/v1/payments/{id} verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DisplayName("Payment Workflow Integration Tests")
class PaymentWorkflowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestWorkflowEnvironment testEnv;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        LoginRequest req = new LoginRequest("admin", "password");
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = "Bearer " + objectMapper.readValue(json, LoginResponse.class).getToken();
    }

    @Test
    @DisplayName("POST /payments returns 202 with workflowId and PENDING status")
    void createPayment_returns202WithWorkflowId() throws Exception {
        PaymentRequest req = new PaymentRequest(
                "1234567890", "0987654321", BigDecimal.valueOf(100), "USD", null);

        String json = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        String workflowId = objectMapper.readTree(json).get("workflowId").asText();
        assertTrue(workflowId.startsWith("payment-"), "workflowId should start with 'payment-'");
    }

    @Test
    @DisplayName("Workflow completes and payment is persisted as COMPLETED")
    void createPayment_workflowCompletes_paymentPersistedAsCompleted() throws Exception {
        PaymentRequest req = new PaymentRequest(
                "1234567890", "0987654321", BigDecimal.valueOf(250), "USD", "Round-trip test");

        String json202 = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String workflowId = objectMapper.readTree(json202).get("workflowId").asText();

        // Await workflow completion (fast in TestWorkflowEnvironment)
        PaymentCreationResult result = testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class);

        assertNotNull(result.getPaymentId(), "paymentId must be set");
        assertEquals("COMPLETED", result.getFinalStatus());
        assertEquals(workflowId, result.getWorkflowId());

        // Verify the payment is retrievable via REST
        mockMvc.perform(get("/api/v1/payments/" + result.getPaymentId())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(result.getPaymentId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(250))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.description").value("Round-trip test"));
    }

    @Test
    @DisplayName("Workflow creates a transaction record for the payment")
    void createPayment_workflowCreatesTransaction() throws Exception {
        PaymentRequest req = new PaymentRequest(
                "1234567890", "0987654321", BigDecimal.valueOf(50), "EUR", null);

        String json202 = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String workflowId = objectMapper.readTree(json202).get("workflowId").asText();
        PaymentCreationResult result = testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class);

        mockMvc.perform(get("/api/v1/payments/" + result.getPaymentId() + "/transactions")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[0].paymentId").value(result.getPaymentId()));
    }

    @Test
    @DisplayName("Missing required fields returns 400 without starting workflow")
    void createPayment_missingFields_returns400() throws Exception {
        PaymentRequest req = new PaymentRequest();  // all fields null

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void createPayment_unauthenticated_returns401() throws Exception {
        PaymentRequest req = new PaymentRequest(
                "1234567890", "0987654321", BigDecimal.valueOf(10), "USD", null);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET workflow ID as payment ID returns 404 — paymentId and workflowId are distinct")
    void getPayment_withWorkflowIdNotPaymentId_returns404() throws Exception {
        PaymentRequest req = new PaymentRequest(
                "1234567890", "0987654321", BigDecimal.valueOf(10), "USD", null);

        String json202 = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String workflowId = objectMapper.readTree(json202).get("workflowId").asText();

        // Await workflow so payment is committed
        testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class);

        // The workflowId is NOT a valid payment DB id
        mockMvc.perform(get("/api/v1/payments/" + workflowId)
                        .header("Authorization", token))
                .andExpect(status().isNotFound());
    }
}
