package com.example.paymentapi;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
@DisplayName("Payment API End-to-End Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaymentEndToEndTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;
    private String authToken;
    private static String createdPaymentId;

    @BeforeEach
    public void setUp() {
        baseUrl = "http://localhost:" + port;
        authToken = getAuthToken();
    }

    private String getAuthToken() {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("password");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> loginEntity = new HttpEntity<>(loginRequest, headers);

        ResponseEntity<LoginResponse> loginResponse = restTemplate.exchange(
                baseUrl + "/api/v1/auth/login",
                HttpMethod.POST,
                loginEntity,
                LoginResponse.class
        );

        assertNotNull(loginResponse.getBody());
        return "Bearer " + loginResponse.getBody().getToken();
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authToken);
        headers.set("X-Api-Key", "test-api-key");
        return headers;
    }

    @Nested
    @DisplayName("Authentication E2E Tests")
    class AuthenticationE2ETests {

        @Test
        @DisplayName("Should authenticate with valid credentials")
        void testAuthentication_Valid() {
            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setUsername("admin");
            loginRequest.setPassword("password");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<LoginResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/auth/login",
                    HttpMethod.POST,
                    new HttpEntity<>(loginRequest, headers),
                    LoginResponse.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertNotNull(response.getBody().getToken());
            assertFalse(response.getBody().getToken().isEmpty());
        }

        @Test
        @DisplayName("Should reject invalid credentials")
        void testAuthentication_Invalid() {
            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setUsername("admin");
            loginRequest.setPassword("wrongpassword");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/v1/auth/login",
                    HttpMethod.POST,
                    new HttpEntity<>(loginRequest, headers),
                    String.class
            );

            // Should not be successful - either 401, 403, or 500
            assertNotEquals(HttpStatus.OK, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Complete Payment Lifecycle E2E Tests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PaymentLifecycleTests {

        @Test
        @Order(1)
        @DisplayName("Step 1: Create a new payment")
        void testCreatePayment() {
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(100));
            request.setCurrency("USD");

            ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    PaymentResponse.class
            );

            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            assertNotNull(response.getBody());
            assertNotNull(response.getBody().getId());
            assertEquals("COMPLETED", response.getBody().getStatus());
            assertNotNull(response.getBody().getCreatedAt());

            createdPaymentId = response.getBody().getId();
        }

        @Test
        @Order(2)
        @DisplayName("Step 2: Retrieve the created payment")
        void testGetPayment() {
            assertNotNull(createdPaymentId, "Payment must be created first");

            ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/" + createdPaymentId,
                    HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()),
                    PaymentResponse.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(createdPaymentId, response.getBody().getId());
        }

        @Test
        @Order(3)
        @DisplayName("Step 3: Reverse the payment")
        void testReversePayment() {
            assertNotNull(createdPaymentId, "Payment must be created first");

            ReversalRequest reversalRequest = new ReversalRequest();
            reversalRequest.setReason("E2E test reversal for refund processing");

            ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/" + createdPaymentId + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(reversalRequest, createAuthHeaders()),
                    PaymentResponse.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("REVERSED", response.getBody().getStatus());
        }

        @Test
        @Order(4)
        @DisplayName("Step 4: Verify payment is reversed")
        void testVerifyReversedPayment() {
            assertNotNull(createdPaymentId, "Payment must be created first");

            ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/" + createdPaymentId,
                    HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()),
                    PaymentResponse.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("REVERSED", response.getBody().getStatus());
        }
    }

    @Nested
    @DisplayName("Payment Validation E2E Tests")
    class PaymentValidationTests {

        @Test
        @DisplayName("Should reject payment with invalid source account")
        void testCreatePayment_InvalidSourceAccount() {
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("invalid");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(100));
            request.setCurrency("USD");

            ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    ErrorResponse.class
            );

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        @DisplayName("Should reject payment without authorization")
        void testCreatePayment_Unauthorized() {
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(100));
            request.setCurrency("USD");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // No authorization header

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    String.class
            );

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        }

        @Test
        @DisplayName("Should reject self-transfer")
        void testCreatePayment_SelfTransfer() {
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("1234567890");
            request.setAmount(BigDecimal.valueOf(100));
            request.setCurrency("USD");

            ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    ErrorResponse.class
            );

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        @DisplayName("Should reject negative amount")
        void testCreatePayment_NegativeAmount() {
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(-100));
            request.setCurrency("USD");

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    String.class
            );

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Error Handling E2E Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return 404 for non-existent payment")
        void testGetPayment_NotFound() {
            ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/non-existent-id-12345",
                    HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()),
                    ErrorResponse.class
            );

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(404, response.getBody().getStatus());
        }

        @Test
        @DisplayName("Should return proper error for invalid status transition")
        void testUpdateStatus_InvalidTransition() {
            // First create a payment
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(50));
            request.setCurrency("USD");

            ResponseEntity<PaymentResponse> createResponse = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    PaymentResponse.class
            );

            String paymentId = createResponse.getBody().getId();

            // Try invalid transition: COMPLETED -> PENDING
            PaymentStatusRequest statusRequest = new PaymentStatusRequest();
            statusRequest.setStatus("PENDING");

            ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/" + paymentId + "/status",
                    HttpMethod.PATCH,
                    new HttpEntity<>(statusRequest, createAuthHeaders()),
                    ErrorResponse.class
            );

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Actuator Endpoints E2E Tests")
    class ActuatorE2ETests {

        @Test
        @DisplayName("Health endpoint should be accessible without auth")
        void testHealthEndpoint() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    baseUrl + "/actuator/health",
                    String.class
            );

            // Health endpoint should be accessible (not require auth)
            // May return 200 or 503 depending on component health
            assertTrue(response.getStatusCode() != HttpStatus.UNAUTHORIZED &&
                            response.getStatusCode() != HttpStatus.FORBIDDEN,
                    "Health endpoint should not require authentication");
        }

        @Test
        @DisplayName("Info endpoint should be accessible without auth")
        void testInfoEndpoint() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    baseUrl + "/actuator/info",
                    String.class
            );

            // Info endpoint should be accessible (may be 200 or 404 if not configured)
            assertTrue(response.getStatusCode() != HttpStatus.UNAUTHORIZED &&
                            response.getStatusCode() != HttpStatus.FORBIDDEN,
                    "Info endpoint should not require authentication");
        }
    }

    @Nested
    @DisplayName("Multiple Payments E2E Tests")
    class MultiplePaymentsTests {

        @Test
        @DisplayName("Should create and retrieve multiple payments")
        void testMultiplePayments() {
            // Create 3 payments
            for (int i = 0; i < 3; i++) {
                PaymentRequest request = new PaymentRequest();
                request.setSourceAccount("1234567890");
                request.setDestinationAccount("0987654321");
                request.setAmount(BigDecimal.valueOf(10 * (i + 1)));
                request.setCurrency("USD");

                ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                        baseUrl + "/api/v1/payments",
                        HttpMethod.POST,
                        new HttpEntity<>(request, createAuthHeaders()),
                        PaymentResponse.class
                );

                assertEquals(HttpStatus.CREATED, response.getStatusCode());
            }

            // Retrieve all payments
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments?page=0&size=10",
                    HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()),
                    String.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().contains("content"));
        }

        @Test
        @DisplayName("Should filter payments by source account")
        void testFilterBySourceAccount() {
            // Create a payment first
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(25));
            request.setCurrency("USD");

            restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    PaymentResponse.class
            );

            // Get payments by source account
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/source-account?sourceAccount=1234567890",
                    HttpMethod.GET,
                    new HttpEntity<>(createAuthHeaders()),
                    String.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Partial Reversal E2E Tests")
    class PartialReversalTests {

        @Test
        @DisplayName("Should process partial refund")
        void testPartialReversal() {
            // Create a payment
            PaymentRequest request = new PaymentRequest();
            request.setSourceAccount("1234567890");
            request.setDestinationAccount("0987654321");
            request.setAmount(BigDecimal.valueOf(100));
            request.setCurrency("USD");

            ResponseEntity<PaymentResponse> createResponse = restTemplate.exchange(
                    baseUrl + "/api/v1/payments",
                    HttpMethod.POST,
                    new HttpEntity<>(request, createAuthHeaders()),
                    PaymentResponse.class
            );

            String paymentId = createResponse.getBody().getId();

            // Request partial refund
            ReversalRequest reversalRequest = new ReversalRequest();
            reversalRequest.setReason("Partial refund for damaged item");
            reversalRequest.setPartialReversal(true);
            reversalRequest.setReversalAmount(BigDecimal.valueOf(30));

            ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                    baseUrl + "/api/v1/payments/" + paymentId + "/reversal",
                    HttpMethod.POST,
                    new HttpEntity<>(reversalRequest, createAuthHeaders()),
                    PaymentResponse.class
            );

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals("REFUNDED", response.getBody().getStatus());
        }
    }
}
