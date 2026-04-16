package com.example.paymentapi;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.ChangePasswordRequest;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.dto.RoleUpdateRequest;
import com.example.paymentapi.service.BankingAPIServiceImpl;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive system tests covering end-to-end user journeys,
 * security enforcement, error consistency, and concurrency safety.
 *
 * <p>These tests boot the full Spring context with H2 and exercise the
 * application through its HTTP API exactly as a real client would.
 *
 * <p>{@code @Transactional} rolls back changes made in the test thread (e.g. auth
 * state changes).  Temporal activity commits in separate threads are not rolled
 * back; count-based payment assertions therefore use ID-level checks rather than
 * relying on a clean-slate payment count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
@DisplayName("Payment API System Tests")
public class PaymentSystemTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CacheManager cacheManager;
    @Autowired private BankingAPIServiceImpl bankingService;
    @Autowired private TestWorkflowEnvironment testEnv;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        // Reset simulated account balances — they live in a ConcurrentHashMap on a
        // singleton bean and are NOT rolled back by @Transactional.
        bankingService.resetBalances();
        adminToken = loginAs("admin", "password");
        userToken = loginAs("user", "password");
    }

    /**
     * Spring cache operations (e.g. {@code @CacheEvict}, {@code @Cacheable}) are NOT
     * transactional — they survive {@code @Transactional} rollback.  If a test changes
     * a user's password the {@code UserDetailsService} cache retains the stale hash,
     * causing subsequent login attempts to fail.  Clearing caches after each test
     * guarantees the next test always loads fresh data from the (rolled-back) database.
     */
    @AfterEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String loginAs(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest(username, password);
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readValue(json, LoginResponse.class).getToken();
    }

    /**
     * Creates a payment via the Temporal workflow endpoint and awaits workflow
     * completion in the {@link TestWorkflowEnvironment}.  Returns the persisted
     * payment ID (not the workflow ID).
     */
    private String createPayment(String token) throws Exception {
        return createPayment(token, BigDecimal.valueOf(100), "USD");
    }

    private String createPayment(String token, BigDecimal amount, String currency) throws Exception {
        PaymentRequest req = new PaymentRequest(
                "1234567890", "0987654321", amount, currency, null);
        String json = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String workflowId = objectMapper.readTree(json).get("workflowId").asText();
        PaymentCreationResult result = testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class);
        return result.getPaymentId();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Auth lifecycle: login → use → logout → rejected → change-password
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Auth Lifecycle Journeys")
    class AuthLifecycleTests {

        @Test
        @DisplayName("Logout invalidates the JWT — subsequent requests get 401")
        void logoutInvalidatesToken() throws Exception {
            String token = loginAs("admin", "password");

            // Token works before logout
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                    .andExpect(status().isOk());

            // Logout
            mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", token))
                    .andExpect(status().isOk());

            // Same token is now rejected
            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", token))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Change password → old password fails, new password works")
        void changePasswordFlow() throws Exception {
            String token = loginAs("user", "password");

            // Change password
            ChangePasswordRequest req = new ChangePasswordRequest(
                    "password", "newSecurePass123");
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", token)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            // Old password fails
            LoginRequest oldLogin = new LoginRequest("user", "password");
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(oldLogin)))
                    .andExpect(status().isUnauthorized());

            // New password works
            LoginRequest newLogin = new LoginRequest("user", "newSecurePass123");
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newLogin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty());
        }

        @Test
        @DisplayName("Change password with wrong current password returns 401")
        void changePasswordWrongCurrent() throws Exception {
            ChangePasswordRequest req = new ChangePasswordRequest(
                    "wrongPassword", "newSecurePass123");
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", userToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Change password with short new password returns 400")
        void changePasswordTooShort() throws Exception {
            ChangePasswordRequest req = new ChangePasswordRequest("password", "short");
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", userToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. Admin endpoint journeys
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Admin Endpoint Journeys")
    class AdminEndpointTests {

        @Test
        @DisplayName("Admin can view platform stats with correct structure")
        void adminViewsStats() throws Exception {
            // Create a payment so stats are non-zero
            createPayment(adminToken);

            mockMvc.perform(get("/api/v1/admin/stats").header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalPayments").isNumber())
                    .andExpect(jsonPath("$.paymentsByStatus").isMap())
                    .andExpect(jsonPath("$.todayPaymentCount").isNumber())
                    .andExpect(jsonPath("$.todayVolume").isNumber())
                    .andExpect(jsonPath("$.totalUsers").isNumber())
                    .andExpect(jsonPath("$.generatedAt").exists());
        }

        @Test
        @DisplayName("Admin can list users (paginated, no passwords exposed)")
        void adminListsUsers() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", adminToken)
                            .param("page", "0").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").isNumber())
                    .andExpect(jsonPath("$.content[0].username").isString())
                    .andExpect(jsonPath("$.content[0].role").isString())
                    .andExpect(jsonPath("$.content[0].password").doesNotExist());
        }

        @Test
        @DisplayName("Admin can get a user by ID")
        void adminGetsUserById() throws Exception {
            // Get user list to find an ID
            String json = mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long userId = objectMapper.readTree(json).get("content").get(0).get("id").asLong();

            mockMvc.perform(get("/api/v1/admin/users/" + userId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(userId))
                    .andExpect(jsonPath("$.username").isString())
                    .andExpect(jsonPath("$.password").doesNotExist());
        }

        @Test
        @DisplayName("Admin can update a user's role")
        void adminUpdatesUserRole() throws Exception {
            // Find the 'user' account ID
            String json = mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            long userId = -1;
            for (JsonNode node : objectMapper.readTree(json).get("content")) {
                if ("user".equals(node.get("username").asText())) {
                    userId = node.get("id").asLong();
                    break;
                }
            }
            assertTrue(userId > 0, "user account must exist");

            // Promote to ADMIN
            RoleUpdateRequest roleReq = new RoleUpdateRequest("ROLE_ADMIN");
            mockMvc.perform(patch("/api/v1/admin/users/" + userId + "/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(roleReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));

            // Demote back to USER (cleanup)
            roleReq = new RoleUpdateRequest("ROLE_USER");
            mockMvc.perform(patch("/api/v1/admin/users/" + userId + "/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(roleReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ROLE_USER"));
        }

        @Test
        @DisplayName("Admin cannot delete their own account")
        void adminCannotDeleteSelf() throws Exception {
            // Find admin's own ID
            String json = mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long adminId = objectMapper.readTree(json).get("id").asLong();

            mockMvc.perform(delete("/api/v1/admin/users/" + adminId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Regular user cannot access admin endpoints")
        void userCannotAccessAdminEndpoints() throws Exception {
            mockMvc.perform(get("/api/v1/admin/stats")
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Invalid role value returns 400")
        void invalidRoleReturns400() throws Exception {
            String json = mockMvc.perform(get("/api/v1/admin/users")
                            .header("Authorization", adminToken))
                    .andReturn().getResponse().getContentAsString();
            long userId = objectMapper.readTree(json).get("content").get(0).get("id").asLong();

            RoleUpdateRequest badRole = new RoleUpdateRequest("ROLE_SUPERADMIN");
            mockMvc.perform(patch("/api/v1/admin/users/" + userId + "/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(badRole)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Payment retry journey
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Payment Retry Journey")
    class PaymentRetryTests {

        @Test
        @DisplayName("Cannot retry a COMPLETED payment — returns 409")
        void retryCompletedPaymentReturns409() throws Exception {
            String paymentId = createPayment(adminToken);

            mockMvc.perform(post("/api/v1/payments/" + paymentId + "/retry")
                            .header("Authorization", adminToken))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Retry non-existent payment returns 404")
        void retryNonExistentPaymentReturns404() throws Exception {
            mockMvc.perform(post("/api/v1/payments/non-existent-id/retry")
                            .header("Authorization", adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Transaction history endpoint
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Transaction History Endpoint")
    class TransactionHistoryTests {

        @Test
        @DisplayName("Transactions list returns array for a valid payment")
        void getTransactionsForPayment() throws Exception {
            String paymentId = createPayment(adminToken);

            mockMvc.perform(get("/api/v1/payments/" + paymentId + "/transactions")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").isString())
                    .andExpect(jsonPath("$[0].paymentId").value(paymentId))
                    .andExpect(jsonPath("$[0].status").isString());
        }

        @Test
        @DisplayName("Transactions for non-existent payment returns 404")
        void getTransactionsForMissingPayment() throws Exception {
            mockMvc.perform(get("/api/v1/payments/non-existent-id/transactions")
                            .header("Authorization", adminToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("USER cannot see transactions for another user's payment")
        void transactionsEnforceOwnership() throws Exception {
            String paymentId = createPayment(adminToken);

            mockMvc.perform(get("/api/v1/payments/" + paymentId + "/transactions")
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER can see transactions for their own payment")
        void transactionsAllowOwner() throws Exception {
            String paymentId = createPayment(userToken);

            mockMvc.perform(get("/api/v1/payments/" + paymentId + "/transactions")
                            .header("Authorization", userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Idempotency
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Idempotency Key Handling")
    class IdempotencyTests {

        @Test
        @DisplayName("Idempotency key is accepted on payment creation")
        void idempotencyKeyAccepted() throws Exception {
            // In test profile Redis is a no-op, so idempotency replay is not exercisable.
            // This test verifies that the Idempotency-Key header is accepted without error
            // and payment creation returns 202 Accepted with a workflowId.
            String idempotencyKey = UUID.randomUUID().toString();
            PaymentRequest req = new PaymentRequest(
                    "1234567890", "0987654321", BigDecimal.valueOf(75), "USD", null);
            String body = objectMapper.writeValueAsString(req);

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .header("Idempotency-Key", idempotencyKey)
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.workflowId").isString());
        }

        @Test
        @DisplayName("Different idempotency keys create separate workflows")
        void differentKeysCreateSeparatePayments() throws Exception {
            PaymentRequest req = new PaymentRequest(
                    "1234567890", "0987654321", BigDecimal.valueOf(50), "USD", null);
            String body = objectMapper.writeValueAsString(req);

            String wfId1 = objectMapper.readTree(mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString()).get("workflowId").asText();

            String wfId2 = objectMapper.readTree(mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(body))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString()).get("workflowId").asText();

            assertNotEquals(wfId1, wfId2, "Different idempotency keys must create separate workflows");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. Security headers
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Security Header Verification")
    class SecurityHeaderTests {

        @Test
        @DisplayName("Responses include all required security headers")
        void responsesIncludeSecurityHeaders() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/payments")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn();

            assertNotNull(result.getResponse().getHeader("X-Content-Type-Options"),
                    "X-Content-Type-Options must be present");
            assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));

            assertNotNull(result.getResponse().getHeader("X-Frame-Options"),
                    "X-Frame-Options must be present");
            assertEquals("DENY", result.getResponse().getHeader("X-Frame-Options"));

            assertNotNull(result.getResponse().getHeader("Cache-Control"),
                    "Cache-Control must be present");

            String csp = result.getResponse().getHeader("Content-Security-Policy");
            assertNotNull(csp, "Content-Security-Policy must be present");
            assertTrue(csp.contains("default-src 'self'"), "CSP must include default-src");
            assertTrue(csp.contains("frame-ancestors 'none'"), "CSP must deny frame-ancestors");

            String referrer = result.getResponse().getHeader("Referrer-Policy");
            assertNotNull(referrer, "Referrer-Policy must be present");
            assertEquals("strict-origin-when-cross-origin", referrer);
        }

        @Test
        @DisplayName("Public endpoints also include security headers")
        void publicEndpointsIncludeHeaders() throws Exception {
            MvcResult result = mockMvc.perform(get("/actuator/health"))
                    .andReturn();

            assertNotNull(result.getResponse().getHeader("X-Content-Type-Options"));
            assertNotNull(result.getResponse().getHeader("X-Frame-Options"));
        }

        @Test
        @DisplayName("Rate limit headers are present on API responses")
        void rateLimitHeadersPresent() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/payments")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn();

            assertNotNull(result.getResponse().getHeader("X-RateLimit-Limit"),
                    "X-RateLimit-Limit must be present");
            assertNotNull(result.getResponse().getHeader("X-RateLimit-Remaining"),
                    "X-RateLimit-Remaining must be present");
            assertNotNull(result.getResponse().getHeader("X-RateLimit-Reset"),
                    "X-RateLimit-Reset must be present");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. Error response consistency
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error Response Format Consistency")
    class ErrorConsistencyTests {

        @Test
        @DisplayName("404 error has standard structure (status, error, message, path, timestamp)")
        void notFoundErrorStructure() throws Exception {
            mockMvc.perform(get("/api/v1/payments/does-not-exist")
                            .header("Authorization", adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").isString())
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.path").isString())
                    .andExpect(jsonPath("$.timestamp").isString());
        }

        @Test
        @DisplayName("400 validation error includes fieldErrors array")
        void validationErrorIncludesFieldErrors() throws Exception {
            PaymentRequest req = new PaymentRequest();  // all fields null

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors[0].field").isString())
                    .andExpect(jsonPath("$.fieldErrors[0].message").isString());
        }

        @Test
        @DisplayName("409 conflict error has standard structure")
        void conflictErrorStructure() throws Exception {
            String paymentId = createPayment(adminToken);  // COMPLETED

            // Try invalid transition COMPLETED -> PENDING
            String body = objectMapper.writeValueAsString(
                    new com.example.paymentapi.dto.PaymentStatusRequest() {{
                        setStatus("PENDING");
                    }});

            mockMvc.perform(patch("/api/v1/payments/" + paymentId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").isString())
                    .andExpect(jsonPath("$.message").isString());
        }

        @Test
        @DisplayName("401 unauthenticated error has JSON body")
        void unauthenticatedErrorStructure() throws Exception {
            mockMvc.perform(get("/api/v1/payments"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("403 forbidden error has JSON body")
        void forbiddenErrorStructure() throws Exception {
            mockMvc.perform(get("/api/v1/admin/stats")
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Malformed JSON returns 400 with clear message")
        void malformedJsonReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content("{invalid json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").isString());
        }

        @Test
        @DisplayName("Unsupported HTTP method returns 405")
        void unsupportedMethodReturns405() throws Exception {
            mockMvc.perform(put("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 8. Payment amount boundary tests
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Payment Amount Boundary Tests")
    class AmountBoundaryTests {

        @Test
        @DisplayName("Minimum valid amount (0.01) is accepted")
        void minimumAmount() throws Exception {
            createPayment(adminToken, new BigDecimal("0.01"), "USD");
        }

        @Test
        @DisplayName("Large amount within simulated balance (9999.99) is accepted")
        void largeAmount() throws Exception {
            createPayment(adminToken, new BigDecimal("9999.99"), "USD");
        }

        @Test
        @DisplayName("Amount exceeding maximum (1000000.01) is rejected")
        void exceedMaximumAmount() throws Exception {
            PaymentRequest req = new PaymentRequest(
                    "1234567890", "0987654321", new BigDecimal("1000000.01"), "USD", null);

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Zero amount is rejected")
        void zeroAmount() throws Exception {
            PaymentRequest req = new PaymentRequest(
                    "1234567890", "0987654321", BigDecimal.ZERO, "USD", null);

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Amount with 4 decimal places is accepted")
        void fourDecimalPlaces() throws Exception {
            createPayment(adminToken, new BigDecimal("99.9999"), "USD");
        }

        @Test
        @DisplayName("Lowercase currency code is rejected")
        void lowercaseCurrencyRejected() throws Exception {
            PaymentRequest req = new PaymentRequest(
                    "1234567890", "0987654321", BigDecimal.TEN, "usd", null);

            mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Multi-currency payments (EUR, GBP) are accepted")
        void multiCurrencyPayments() throws Exception {
            assertNotNull(createPayment(adminToken, BigDecimal.valueOf(50), "EUR"));
            assertNotNull(createPayment(adminToken, BigDecimal.valueOf(75), "GBP"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 9. Multi-user ownership journeys (E2E)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-User Ownership Journeys")
    class MultiUserOwnershipTests {

        @Test
        @DisplayName("USER's payment list shows only their payments, not admin's")
        void userSeesOnlyOwnPayments() throws Exception {
            // Admin creates 2 payments; user creates 1 payment
            String adminPayment1 = createPayment(adminToken);
            String adminPayment2 = createPayment(adminToken);
            String userPayment   = createPayment(userToken);

            String json = mockMvc.perform(get("/api/v1/payments?page=0&size=100")
                            .header("Authorization", userToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> ids = new ArrayList<>();
            objectMapper.readTree(json).get("content")
                    .forEach(node -> ids.add(node.get("id").asText()));

            // User must see their own payment
            assertTrue(ids.contains(userPayment), "User must see their own payment");
            // User must NOT see admin's payments
            assertFalse(ids.contains(adminPayment1), "User must not see admin payment 1");
            assertFalse(ids.contains(adminPayment2), "User must not see admin payment 2");
        }

        @Test
        @DisplayName("ADMIN sees all payments from all users")
        void adminSeesAllPayments() throws Exception {
            createPayment(adminToken);
            createPayment(userToken);

            String json = mockMvc.perform(get("/api/v1/payments?page=0&size=100")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // VIA_DTO page serialization: pagination fields nested under "page" object
            int totalElements = objectMapper.readTree(json).get("page").get("totalElements").asInt();
            assertTrue(totalElements >= 2, "Admin should see all payments");
        }

        @Test
        @DisplayName("USER cannot cancel another user's payment")
        void userCannotCancelOthersPayment() throws Exception {
            String adminPaymentId = createPayment(adminToken);

            mockMvc.perform(post("/api/v1/payments/" + adminPaymentId + "/cancel")
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER cannot reverse another user's payment")
        void userCannotReverseOthersPayment() throws Exception {
            String adminPaymentId = createPayment(adminToken);

            ReversalRequest req = new ReversalRequest();
            req.setReason("Unauthorized reversal attempt");

            mockMvc.perform(post("/api/v1/payments/" + adminPaymentId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", userToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER cannot delete another user's payment")
        void userCannotDeleteOthersPayment() throws Exception {
            String adminPaymentId = createPayment(adminToken);

            mockMvc.perform(delete("/api/v1/payments/" + adminPaymentId)
                            .header("Authorization", userToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 10. Complete payment journey (happy path + edge cases)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Complete Payment Journeys")
    class CompletePaymentJourneyTests {

        @Test
        @DisplayName("Create → view → view transactions → reverse → verify reversed")
        void fullLifecycleWithTransactions() throws Exception {
            // Step 1: POST → 202 Accepted; await workflow completion to get paymentId
            PaymentRequest req = new PaymentRequest(
                    "1234567890", "0987654321", BigDecimal.valueOf(200), "USD",
                    "Integration test payment");
            String json202 = mockMvc.perform(post("/api/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.workflowId").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();

            String workflowId = objectMapper.readTree(json202).get("workflowId").asText();
            PaymentCreationResult result = testEnv.getWorkflowClient()
                    .newUntypedWorkflowStub(workflowId)
                    .getResult(PaymentCreationResult.class);
            String paymentId = result.getPaymentId();

            // Verify persisted payment has correct details
            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.description").value("Integration test payment"));

            // Step 2: View payment — account masking and amount
            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(paymentId))
                    .andExpect(jsonPath("$.sourceAccount").value(startsWith("******")))
                    .andExpect(jsonPath("$.amount").value(200));

            // Step 3: View transactions
            mockMvc.perform(get("/api/v1/payments/" + paymentId + "/transactions")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));

            // Step 4: Reverse
            ReversalRequest reversal = new ReversalRequest();
            reversal.setReason("Full refund for integration test");
            mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(reversal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVERSED"));

            // Step 5: Verify final state
            mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVERSED"));
        }

        @Test
        @DisplayName("Create → partial refund → verify REFUNDED")
        void partialRefundJourney() throws Exception {
            String paymentId = createPayment(adminToken, BigDecimal.valueOf(500), "EUR");

            ReversalRequest partial = new ReversalRequest();
            partial.setReason("Partial refund for returned product");
            partial.setPartialReversal(true);
            partial.setReversalAmount(BigDecimal.valueOf(150));

            mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reversal")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", adminToken)
                            .content(objectMapper.writeValueAsString(partial)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REFUNDED"));
        }

        @Test
        @DisplayName("Account masking: response never exposes full account numbers")
        void accountNumbersMasked() throws Exception {
            String paymentId = createPayment(adminToken);

            String json = mockMvc.perform(get("/api/v1/payments/" + paymentId)
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode body = objectMapper.readTree(json);
            String source = body.get("sourceAccount").asText();
            String dest = body.get("destinationAccount").asText();

            assertTrue(source.startsWith("******"), "Source account must be masked");
            assertTrue(dest.startsWith("******"), "Destination account must be masked");
            assertFalse(source.contains("1234567890"), "Full source account must not appear");
            assertFalse(dest.contains("0987654321"), "Full destination account must not appear");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 11. Concurrent payment creation safety
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Concurrent Payment Safety")
    class ConcurrentPaymentTests {

        @Test
        @DisplayName("Multiple concurrent payment creations all succeed without errors")
        void concurrentPaymentsDoNotFail() throws Exception {
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await(); // all threads start together
                        PaymentRequest req = new PaymentRequest(
                                "1234567890", "0987654321",
                                BigDecimal.valueOf(10 + idx), "USD", null);

                        int status = mockMvc.perform(post("/api/v1/payments")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Authorization", adminToken)
                                        .content(objectMapper.writeValueAsString(req)))
                                .andReturn().getResponse().getStatus();

                        if (status == 202) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }));
            }

            latch.countDown(); // release all threads
            for (Future<?> f : futures) f.get();
            executor.shutdown();

            assertEquals(threadCount, successCount.get(),
                    "All concurrent payments should succeed");
            assertEquals(0, errorCount.get(),
                    "No concurrent payment should fail");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 12. Swagger / OpenAPI availability
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OpenAPI Documentation Availability")
    class OpenApiTests {

        @Test
        @DisplayName("OpenAPI JSON spec is accessible without auth")
        void openApiSpecAccessible() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.openapi").exists())
                    .andExpect(jsonPath("$.paths").isMap());
        }

        @Test
        @DisplayName("Swagger UI page is accessible without auth")
        void swaggerUiAccessible() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html"))
                    .andExpect(status().isOk());
        }
    }
}
