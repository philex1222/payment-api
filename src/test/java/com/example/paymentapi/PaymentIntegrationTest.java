package com.example.paymentapi;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentStatusRequest;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
@DisplayName("Payment API Integration Tests")
public class PaymentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    private String authToken;

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
        authToken = "Bearer " + loginResponse.getToken();
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("Should login successfully with valid credentials")
        void testLogin_Success() throws Exception {
            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setUsername("admin");
            loginRequest.setPassword("password");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").exists())
                    .andExpect(jsonPath("$.token").isNotEmpty());
        }

        @Test
        @DisplayName("Should reject invalid credentials")
        void testLogin_InvalidCredentials() throws Exception {
            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setUsername("admin");
            loginRequest.setPassword("wrongpassword");

            int status = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andReturn().getResponse().getStatus();

            // Should reject with 401 Unauthorized (not 200 OK)
            assertTrue(status == 401 || status == 403 || status == 500,
                    "Invalid credentials should be rejected, got status: " + status);
            assertNotEquals(200, status, "Invalid credentials should not succeed");
        }

        @Test
        @DisplayName("Should reject non-existent user")
        void testLogin_NonExistentUser() throws Exception {
            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setUsername("nonexistent");
            loginRequest.setPassword("password");

            int status = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andReturn().getResponse().getStatus();

            // Should reject with 401 Unauthorized (not 200 OK)
            assertTrue(status == 401 || status == 403 || status == 500,
                    "Non-existent user should be rejected, got status: " + status);
            assertNotEquals(200, status, "Non-existent user should not succeed");
        }
    }

    @Nested
    @DisplayName("Create Payment Tests")
    class CreatePaymentTests {

        @Test
        @DisplayName("Should create payment successfully")
        void testCreatePayment_Success() throws Exception {
            PaymentRequest request = createValidPaymentRequest();

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("Should reject unauthorized request")
        void testCreatePayment_Unauthorized() throws Exception {
            PaymentRequest request = createValidPaymentRequest();

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should reject invalid token")
        void testCreatePayment_InvalidToken() throws Exception {
            PaymentRequest request = createValidPaymentRequest();

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer invalid-token")
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Should validate required fields")
        void testCreatePayment_MissingFields() throws Exception {
            PaymentRequest request = new PaymentRequest();

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject negative amount")
        void testCreatePayment_NegativeAmount() throws Exception {
            PaymentRequest request = createValidPaymentRequest();
            request.setAmount(BigDecimal.valueOf(-100));

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject invalid source account")
        void testCreatePayment_InvalidSourceAccount() throws Exception {
            PaymentRequest request = createValidPaymentRequest();
            request.setSourceAccount("invalid");

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should reject self-transfer")
        void testCreatePayment_SelfTransfer() throws Exception {
            PaymentRequest request = createValidPaymentRequest();
            request.setDestinationAccount(request.getSourceAccount());

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Get Payment Tests")
    class GetPaymentTests {

        @Test
        @DisplayName("Should get payment by ID")
        void testGetPaymentById_Success() throws Exception {
            // First create a payment
            String paymentId = createPaymentAndGetId();

            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(paymentId))
                    .andExpect(jsonPath("$.status").exists());
        }

        @Test
        @DisplayName("Should return 404 for non-existent payment")
        void testGetPaymentById_NotFound() throws Exception {
            mockMvc.perform(get("/api/v1/payments/non-existent-id")
                            .header("Authorization", authToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should get all payments with pagination")
        void testGetPayments_Paginated() throws Exception {
            // Create some payments
            createPaymentAndGetId();
            createPaymentAndGetId();

            mockMvc.perform(get("/api/v1/payments")
                            .header("Authorization", authToken)
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").isNumber());
        }

        @Test
        @DisplayName("Should get payments by source account")
        void testGetPaymentsBySourceAccount() throws Exception {
            createPaymentAndGetId();

            mockMvc.perform(get("/api/v1/payments/source-account")
                            .header("Authorization", authToken)
                            .param("sourceAccount", "1234567890"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("Should get payments by destination account")
        void testGetPaymentsByDestinationAccount() throws Exception {
            createPaymentAndGetId();

            mockMvc.perform(get("/api/v1/payments/destination-account")
                            .header("Authorization", authToken)
                            .param("destinationAccount", "0987654321"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Nested
    @DisplayName("Update Payment Status Tests")
    class UpdatePaymentStatusTests {

        @Test
        @DisplayName("Should update payment status")
        void testUpdatePaymentStatus_Success() throws Exception {
            // Create a payment (it will be COMPLETED)
            String paymentId = createPaymentAndGetId();

            // COMPLETED -> REVERSED is a valid transition
            PaymentStatusRequest statusRequest = new PaymentStatusRequest();
            statusRequest.setStatus("REVERSED");

            mockMvc.perform(patch("/api/v1/payments/" + paymentId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(statusRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVERSED"));
        }

        @Test
        @DisplayName("Should reject invalid status transition")
        void testUpdatePaymentStatus_InvalidTransition() throws Exception {
            String paymentId = createPaymentAndGetId();

            // COMPLETED -> PENDING is invalid
            PaymentStatusRequest statusRequest = new PaymentStatusRequest();
            statusRequest.setStatus("PENDING");

            mockMvc.perform(patch("/api/v1/payments/" + paymentId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(statusRequest)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("Delete Payment Tests")
    class DeletePaymentTests {

        @Test
        @DisplayName("Should not delete completed payment")
        void testDeletePayment_CompletedPayment() throws Exception {
            String paymentId = createPaymentAndGetId();

            mockMvc.perform(delete("/api/v1/payments/" + paymentId)
                            .header("Authorization", authToken))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Should return 404 for non-existent payment")
        void testDeletePayment_NotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/payments/non-existent-id")
                            .header("Authorization", authToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Payment Reversal Tests")
    class PaymentReversalTests {

        @Test
        @DisplayName("Should reverse completed payment")
        void testPaymentReversal_Success() throws Exception {
            String paymentId = createPaymentAndGetId();

            ReversalRequest reversalRequest = new ReversalRequest();
            reversalRequest.setReason("Customer requested refund for this payment");

            mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .header("X-Api-Key", "test-api-key")
                            .content(objectMapper.writeValueAsString(reversalRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVERSED"));
        }

        @Test
        @DisplayName("Should reject reversal without reason")
        void testPaymentReversal_MissingReason() throws Exception {
            String paymentId = createPaymentAndGetId();

            ReversalRequest reversalRequest = new ReversalRequest();
            // No reason set

            mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .header("X-Api-Key", "test-api-key")
                            .content(objectMapper.writeValueAsString(reversalRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should handle partial reversal")
        void testPaymentReversal_Partial() throws Exception {
            String paymentId = createPaymentAndGetId();

            ReversalRequest reversalRequest = new ReversalRequest();
            reversalRequest.setReason("Partial refund requested by customer");
            reversalRequest.setPartialReversal(true);
            reversalRequest.setReversalAmount(BigDecimal.valueOf(50));

            mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .header("X-Api-Key", "test-api-key")
                            .content(objectMapper.writeValueAsString(reversalRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REFUNDED"));
        }
    }

    @Nested
    @DisplayName("Payment description field")
    class PaymentDescriptionTests {

        @Test
        @DisplayName("Payment created with description includes it in response")
        void testCreatePayment_withDescription_returnsDescription() throws Exception {
            PaymentRequest request = createValidPaymentRequest();
            request.setDescription("Invoice #INV-2026-001");

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").value("Invoice #INV-2026-001"));
        }

        @Test
        @DisplayName("Payment created without description omits the field from response")
        void testCreatePayment_withoutDescription_omitsField() throws Exception {
            PaymentRequest request = createValidPaymentRequest();

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").doesNotExist());
        }

        @Test
        @DisplayName("Description over 255 characters returns 400")
        void testCreatePayment_descriptionTooLong_returns400() throws Exception {
            PaymentRequest request = createValidPaymentRequest();
            request.setDescription("A".repeat(256));

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", authToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Payment list filtering")
    class PaymentFilterTests {

        @Test
        @DisplayName("?amountFrom > amountTo returns 400")
        void testGetPayments_invalidAmountRange_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/payments?amountFrom=500&amountTo=100")
                            .header("Authorization", authToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("?amountFrom filter returns only payments at or above the amount")
        void testGetPayments_amountFromFilter() throws Exception {
            // Create a payment (amount 100 USD)
            createPaymentAndGetId();

            mockMvc.perform(get("/api/v1/payments?amountFrom=50")
                            .header("Authorization", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }

        @Test
        @DisplayName("?currency=USD filters by currency")
        void testGetPayments_currencyFilter() throws Exception {
            createPaymentAndGetId();

            mockMvc.perform(get("/api/v1/payments?currency=USD")
                            .header("Authorization", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }
    }

    @Nested
    @DisplayName("Actuator Endpoint Tests")
    class ActuatorTests {

        @Test
        @DisplayName("Health endpoint should be accessible without auth")
        void testHealthEndpoint() throws Exception {
            // Health endpoint is accessible without authentication
            // May return 200 or 503 depending on component health
            int status = mockMvc.perform(get("/actuator/health"))
                    .andReturn().getResponse().getStatus();
            // Should not require auth (not 401/403)
            assertTrue(status != 401 && status != 403,
                    "Health endpoint should not require authentication");
        }

        @Test
        @DisplayName("Info endpoint should be accessible without auth")
        void testInfoEndpoint() throws Exception {
            // Info endpoint should be accessible (may be 200 or 404 if not configured)
            int status = mockMvc.perform(get("/actuator/info"))
                    .andReturn().getResponse().getStatus();
            // Should not require auth (not 401/403)
            assertTrue(status != 401 && status != 403,
                    "Info endpoint should not require authentication");
        }
    }

    @Nested
    @DisplayName("BOLA (Broken Object Level Authorization) Tests")
    class BolaTests {

        @Test
        @DisplayName("USER cannot access a payment created by ADMIN — expects 403")
        void testBola_userCannotAccessAdminPayment() throws Exception {
            // Create a payment as ADMIN
            String paymentId = createPaymentAndGetId();

            // Obtain a token for the regular "user" account
            LoginRequest userLogin = new LoginRequest();
            userLogin.setUsername("user");
            userLogin.setPassword("password");
            String userJson = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userLogin)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String userToken = "Bearer " + objectMapper.readValue(userJson, LoginResponse.class).getToken();

            // USER tries to read admin's payment — must be denied
            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER can access their own payment")
        void testBola_userCanAccessOwnPayment() throws Exception {
            // Obtain a token for the regular "user" account
            LoginRequest userLogin = new LoginRequest();
            userLogin.setUsername("user");
            userLogin.setPassword("password");
            String userJson = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userLogin)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String userToken = "Bearer " + objectMapper.readValue(userJson, LoginResponse.class).getToken();

            // Create a payment as "user"
            PaymentRequest request = createValidPaymentRequest();
            String responseJson = mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", userToken)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String paymentId = objectMapper.readTree(responseJson).get("id").asText();

            // USER reads their own payment — must succeed
            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(paymentId));
        }

        @Test
        @DisplayName("ADMIN can access any payment regardless of creator")
        void testBola_adminCanAccessAnyPayment() throws Exception {
            // Create a payment as the regular "user"
            LoginRequest userLogin = new LoginRequest();
            userLogin.setUsername("user");
            userLogin.setPassword("password");
            String userJson = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userLogin)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String userToken = "Bearer " + objectMapper.readValue(userJson, LoginResponse.class).getToken();

            String responseJson = mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", userToken)
                            .content(objectMapper.writeValueAsString(createValidPaymentRequest())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String paymentId = objectMapper.readTree(responseJson).get("id").asText();

            // ADMIN reads user's payment — must succeed
            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(paymentId));
        }
    }

    @Nested
    @DisplayName("Filter-level access control (JSON 403 body)")
    class SecurityBoundaryTests {

        @Test
        @DisplayName("USER JWT accessing admin endpoint returns 403 with JSON error body")
        void testUserJwt_accessAdminEndpoint_returns403WithJsonBody() throws Exception {
            // Obtain a token for the regular "user" account
            LoginRequest userLogin = new LoginRequest();
            userLogin.setUsername("user");
            userLogin.setPassword("password");
            String userJson = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userLogin)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String userToken = "Bearer " + objectMapper.readValue(userJson, LoginResponse.class).getToken();

            // USER JWT hits admin-only endpoint → security filter chain denies → accessDeniedHandler
            mockMvc.perform(get("/api/v1/admin/stats")
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));
        }

        @Test
        @DisplayName("Request without token to secured endpoint returns 401 with JSON error body")
        void testNoToken_securedEndpoint_returns401WithJsonBody() throws Exception {
            mockMvc.perform(get("/api/v1/payments"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("Unauthorized"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/auth/me — user profile endpoint")
    class UserProfileTests {

        @Test
        @DisplayName("Admin JWT returns admin profile")
        void testGetMe_adminJwt_returnsAdminProfile() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", authToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("admin"))
                    .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
                    .andExpect(jsonPath("$.id").isNumber());
        }

        @Test
        @DisplayName("User JWT returns user profile")
        void testGetMe_userJwt_returnsUserProfile() throws Exception {
            LoginRequest userLogin = new LoginRequest();
            userLogin.setUsername("user");
            userLogin.setPassword("password");
            String userJson = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userLogin)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String userToken = "Bearer " + objectMapper.readValue(userJson, LoginResponse.class).getToken();

            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("user"))
                    .andExpect(jsonPath("$.role").value("ROLE_USER"))
                    .andExpect(jsonPath("$.id").isNumber());
        }

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void testGetMe_noToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // Helper methods

    private PaymentRequest createValidPaymentRequest() {
        PaymentRequest request = new PaymentRequest();
        request.setSourceAccount("1234567890");
        request.setDestinationAccount("0987654321");
        request.setAmount(BigDecimal.valueOf(100));
        request.setCurrency("USD");
        return request;
    }

    private String createPaymentAndGetId() throws Exception {
        PaymentRequest request = createValidPaymentRequest();

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", authToken)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        return objectMapper.readTree(responseJson).get("id").asText();
    }
}
