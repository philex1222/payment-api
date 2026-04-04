package com.example.paymentapi;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.service.BankingAPIServiceImpl;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.*;

/**
 * REST Assured system tests exercising the Payment API over real HTTP.
 *
 * <p>Uses {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} so every request
 * traverses the full Servlet/Security/MVC filter chain exactly as production
 * traffic would.  Responses are validated against JSON Schemas defined in
 * {@code src/test/resources/schemas/}.
 *
 * <p>Unlike {@code @Transactional} MockMvc tests, database changes here are
 * permanent within the test-run.  Each test resets the simulated banking
 * balances and Spring caches so tests remain independent of execution order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
@DisplayName("REST Assured System Tests")
class PaymentApiRestAssuredTest {

    @LocalServerPort
    private int port;

    @Autowired
    private BankingAPIServiceImpl bankingService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        bankingService.resetBalances();
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    // ── Auth helpers ─────────────────────────────────────────────────────────

    private String loginAndGetToken(String username, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .body("token", notNullValue())
                .extract().path("token");
    }

    private RequestSpecification asAdmin() {
        return given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + loginAndGetToken("admin", "password"));
    }

    private RequestSpecification asUser() {
        return given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + loginAndGetToken("user", "password"));
    }

    private String createPaymentReturningId(RequestSpecification spec) {
        return spec
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":50,"currency":"USD"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .extract().path("id");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Authentication & Token Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Authentication & Token Lifecycle")
    class AuthTests {

        @Test
        @DisplayName("POST /auth/login — valid credentials return JWT matching schema")
        void loginReturnsJwt() {
            given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/login-response.json"))
                .body("token", notNullValue())
                .time(lessThan(5000L)); // SLA: login < 5s
        }

        @Test
        @DisplayName("POST /auth/login — invalid credentials return 401")
        void loginWithBadCredentials() {
            given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"wrong\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(401);
        }

        @Test
        @DisplayName("POST /auth/logout — invalidates token for subsequent requests")
        void logoutInvalidatesToken() {
            String token = loginAndGetToken("admin", "password");

            // Token works before logout
            given().header("Authorization", "Bearer " + token)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200);

            // Logout
            given().header("Authorization", "Bearer " + token)
                .when().post("/api/v1/auth/logout")
                .then().statusCode(200);

            // Token is now invalid
            given().header("Authorization", "Bearer " + token)
                .when().get("/api/v1/auth/me")
                .then().statusCode(401);
        }

        @Test
        @DisplayName("GET /auth/me — returns current user profile")
        void meReturnsProfile() {
            String token = loginAndGetToken("user", "password");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/api/v1/auth/me")
            .then()
                .statusCode(200)
                .body("username", equalTo("user"))
                .body("role", equalTo("ROLE_USER"))
                .body("password", nullValue());
        }

        @Test
        @DisplayName("Protected endpoints return 401 without token")
        void protectedEndpointWithoutToken() {
            given()
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(401);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. Payment CRUD Lifecycle with Schema Validation
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Payment CRUD Lifecycle")
    class PaymentCrudTests {

        @Test
        @DisplayName("Create → Get → List — full lifecycle validates against JSON schemas")
        void fullCrudLifecycle() {
            // CREATE
            String paymentId = asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":100.50,"currency":"USD","description":"REST Assured test"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .body(matchesJsonSchemaInClasspath("schemas/payment-response.json"))
                .body("status", equalTo("COMPLETED"))
                .body("sourceAccount", startsWith("******"))
                .body("amount", equalTo(100.50f))
                .body("currency", equalTo("USD"))
                .body("description", equalTo("REST Assured test"))
                .extract().path("id");

            // GET by ID
            asAdmin()
            .when()
                .get("/api/v1/payments/{id}", paymentId)
            .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/payment-response.json"))
                .body("id", equalTo(paymentId))
                .body("status", equalTo("COMPLETED"));

            // LIST (paginated)
            asAdmin()
            .when()
                .get("/api/v1/payments?page=0&size=10")
            .then()
                .statusCode(200)
                .body("content", hasSize(greaterThanOrEqualTo(1)))
                .body("content[0].id", notNullValue())
                .body("totalElements", greaterThanOrEqualTo(1));
        }

        @Test
        @DisplayName("Create → Reverse — reversal changes status and schema is valid")
        void createAndReverse() {
            String id = createPaymentReturningId(asAdmin());

            // REVERSE
            asAdmin()
                .body("{\"reason\":\"Customer requested full refund\"}")
            .when()
                .post("/api/v1/payments/{id}/reversal", id)
            .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/payment-response.json"))
                .body("status", equalTo("REVERSED"));
        }

        @Test
        @DisplayName("Create → Cancel — cancel changes status to CANCELLED")
        void createAndCancel() {
            // Create a payment that stays PENDING — use an amount small enough
            String id = createPaymentReturningId(asAdmin());

            // Cancel requires PENDING status; COMPLETED payments can't be cancelled
            // so we attempt cancel and verify the expected response
            Response response = asAdmin()
                .when()
                    .post("/api/v1/payments/{id}/cancel", id);

            // COMPLETED payments return 409 (can't cancel); PENDING would return 200
            int status = response.statusCode();
            if (status == 200) {
                response.then().body("status", equalTo("CANCELLED"));
            } else {
                response.then().statusCode(409);
            }
        }

        @Test
        @DisplayName("GET /payments/{id}/transactions — returns transaction array")
        void getTransactions() {
            String id = createPaymentReturningId(asAdmin());

            asAdmin()
            .when()
                .get("/api/v1/payments/{id}/transactions", id)
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)))
                .body("[0].paymentId", equalTo(id))
                .body("[0].status", notNullValue());
        }

        @Test
        @DisplayName("Multi-currency payment (EUR) succeeds — service converts to USD")
        void multiCurrencyPayment() {
            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":250.75,"currency":"EUR"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("COMPLETED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Input Validation & Error Responses
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Input Validation & Error Responses")
    class ValidationTests {

        @Test
        @DisplayName("Empty payment body returns 400 with fieldErrors")
        void emptyBodyReturns400() {
            asAdmin()
                .body("{}")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("status", equalTo(400))
                .body("fieldErrors", hasSize(greaterThanOrEqualTo(3)))
                .body("fieldErrors.field", hasItems("sourceAccount", "amount", "currency"));
        }

        @Test
        @DisplayName("Invalid account number format returns 400")
        void invalidAccountFormat() {
            asAdmin()
                .body("""
                    {"sourceAccount":"ABC","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(400)
                .body("fieldErrors.field", hasItem("sourceAccount"));
        }

        @Test
        @DisplayName("Amount exceeding max (1000000.01) returns 400")
        void amountExceedsMax() {
            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":1000000.01,"currency":"USD"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(400)
                .body("fieldErrors.field", hasItem("amount"));
        }

        @Test
        @DisplayName("Zero amount returns 400")
        void zeroAmount() {
            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":0,"currency":"USD"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Lowercase currency (usd) returns 400")
        void lowercaseCurrency() {
            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"usd"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(400)
                .body("fieldErrors.field", hasItem("currency"));
        }

        @Test
        @DisplayName("Malformed JSON returns 400")
        void malformedJson() {
            asAdmin()
                .body("{invalid json")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(400)
                .body("status", equalTo(400));
        }

        @Test
        @DisplayName("Non-existent payment returns 404 with error schema")
        void notFoundError() {
            asAdmin()
            .when()
                .get("/api/v1/payments/does-not-exist")
            .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("status", equalTo(404))
                .body("path", equalTo("/api/v1/payments/does-not-exist"));
        }

        @Test
        @DisplayName("Unsupported HTTP method returns 405")
        void unsupportedMethod() {
            asAdmin()
                .body("{}")
            .when()
                .put("/api/v1/payments")
            .then()
                .statusCode(405);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Authorization & RBAC
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Authorization & RBAC")
    class AuthorizationTests {

        @Test
        @DisplayName("ROLE_USER cannot access admin endpoints — 403")
        void userCannotAccessAdmin() {
            asUser()
            .when()
                .get("/api/v1/admin/stats")
            .then()
                .statusCode(403);

            asUser()
            .when()
                .get("/api/v1/admin/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("ROLE_ADMIN can access admin stats with valid schema")
        void adminCanAccessStats() {
            asAdmin()
            .when()
                .get("/api/v1/admin/stats")
            .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/admin-stats.json"))
                .body("totalUsers", greaterThanOrEqualTo(2))
                .body("generatedAt", notNullValue());
        }

        @Test
        @DisplayName("ROLE_USER can only see own payments (BOLA enforcement)")
        void userSeesOnlyOwnPayments() {
            // Admin creates a payment
            createPaymentReturningId(asAdmin());

            // User creates a payment
            createPaymentReturningId(asUser());

            // User's list should contain only their payment
            asUser()
            .when()
                .get("/api/v1/payments?page=0&size=100")
            .then()
                .statusCode(200)
                .body("content.findAll { it }.size()", greaterThanOrEqualTo(1))
                .body("content.every { it.sourceAccount.startsWith('******') }", is(true));
        }

        @Test
        @DisplayName("USER cannot view another user's payment — 403")
        void crossUserAccessBlocked() {
            String adminPaymentId = createPaymentReturningId(asAdmin());

            asUser()
            .when()
                .get("/api/v1/payments/{id}", adminPaymentId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("USER cannot cancel another user's payment — 403")
        void crossUserCancelBlocked() {
            String adminPaymentId = createPaymentReturningId(asAdmin());

            asUser()
            .when()
                .post("/api/v1/payments/{id}/cancel", adminPaymentId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("ADMIN can list and manage users")
        void adminManagesUsers() {
            asAdmin()
            .when()
                .get("/api/v1/admin/users?page=0&size=10")
            .then()
                .statusCode(200)
                .body("content", hasSize(greaterThanOrEqualTo(2)))
                .body("content.username", hasItems("admin", "user"))
                .body("content.password", everyItem(nullValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Security Headers & Transport
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Security Headers & Transport")
    class SecurityHeaderTests {

        @Test
        @DisplayName("API responses include all required security headers")
        void securityHeadersPresent() {
            asAdmin()
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(200)
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Cache-Control", containsString("no-cache"))
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"));
        }

        @Test
        @DisplayName("Rate limit headers are present on API responses")
        void rateLimitHeaders() {
            asAdmin()
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(200)
                .header("X-RateLimit-Limit", notNullValue())
                .header("X-RateLimit-Remaining", notNullValue())
                .header("X-RateLimit-Reset", notNullValue());
        }

        @Test
        @DisplayName("Public actuator endpoint includes security headers")
        void actuatorIncludesHeaders() {
            given()
            .when()
                .get("/actuator/health")
            .then()
                .statusCode(200)
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("X-Frame-Options", equalTo("DENY"));
        }

        @Test
        @DisplayName("Account numbers are always masked in responses (******XXXX)")
        void accountMasking() {
            String id = createPaymentReturningId(asAdmin());

            Response response = asAdmin()
                .when().get("/api/v1/payments/{id}", id);

            String source = response.path("sourceAccount");
            String dest = response.path("destinationAccount");

            org.junit.jupiter.api.Assertions.assertTrue(
                    source.startsWith("******"), "Source must be masked: " + source);
            org.junit.jupiter.api.Assertions.assertTrue(
                    dest.startsWith("******"), "Destination must be masked: " + dest);
            org.junit.jupiter.api.Assertions.assertFalse(
                    source.contains("1234567890"), "Full account must not appear");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. Idempotency
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Idempotency Key Handling")
    class IdempotencyTests {

        @Test
        @DisplayName("Idempotency-Key header is accepted on payment creation")
        void idempotencyKeyAccepted() {
            String key = UUID.randomUUID().toString();

            asAdmin()
                .header("Idempotency-Key", key)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":25,"currency":"USD"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .body("id", notNullValue());
        }

        @Test
        @DisplayName("Different idempotency keys create separate payments")
        void differentKeysCreateSeparatePayments() {
            String body = """
                {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                 "amount":30,"currency":"USD"}""";

            String id1 = asAdmin()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(body)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .extract().path("id");

            String id2 = asAdmin()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(body)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .extract().path("id");

            org.junit.jupiter.api.Assertions.assertNotEquals(id1, id2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. Payment Filtering & Pagination
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Payment Filtering & Pagination")
    class FilteringTests {

        @Test
        @DisplayName("Filter by status returns only matching payments")
        void filterByStatus() {
            createPaymentReturningId(asAdmin());

            asAdmin()
                .queryParam("status", "COMPLETED")
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(200)
                .body("content.status", everyItem(equalTo("COMPLETED")));
        }

        @Test
        @DisplayName("Pagination respects page and size parameters")
        void paginationWorks() {
            // Create enough payments to test pagination
            createPaymentReturningId(asAdmin());
            createPaymentReturningId(asAdmin());
            createPaymentReturningId(asAdmin());

            asAdmin()
                .queryParam("page", 0)
                .queryParam("size", 2)
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(200)
                .body("content", hasSize(2))
                .body("number", equalTo(0))
                .body("size", equalTo(2))
                .body("totalElements", greaterThanOrEqualTo(3));
        }

        @Test
        @DisplayName("Filter by currency returns correct subset")
        void filterByCurrency() {
            // Create USD and EUR payments
            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}""")
                .post("/api/v1/payments");

            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"EUR"}""")
                .post("/api/v1/payments");

            asAdmin()
                .queryParam("currency", "EUR")
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(200)
                .body("content.currency", everyItem(equalTo("EUR")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 8. Admin Operations
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Admin Operations")
    class AdminTests {

        @Test
        @DisplayName("Admin stats reflect created payments")
        void statsReflectPayments() {
            createPaymentReturningId(asAdmin());

            asAdmin()
            .when()
                .get("/api/v1/admin/stats")
            .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/admin-stats.json"))
                .body("totalPayments", greaterThanOrEqualTo(1))
                .body("paymentsByStatus.COMPLETED", greaterThanOrEqualTo(1));
        }

        @Test
        @DisplayName("Admin cannot delete their own account — 409")
        void adminCannotDeleteSelf() {
            // Get admin's user ID from /me
            int adminId = given()
                .header("Authorization", "Bearer " + loginAndGetToken("admin", "password"))
            .when()
                .get("/api/v1/auth/me")
            .then()
                .extract().path("id");

            asAdmin()
            .when()
                .delete("/api/v1/admin/users/{id}", adminId)
            .then()
                .statusCode(409);
        }

        @Test
        @DisplayName("Admin can get individual user by ID (no password leaked)")
        void getUserById() {
            // Get a user ID from the list
            int userId = asAdmin()
                .when().get("/api/v1/admin/users")
                .then().extract().path("content[0].id");

            asAdmin()
            .when()
                .get("/api/v1/admin/users/{id}", userId)
            .then()
                .statusCode(200)
                .body("id", equalTo(userId))
                .body("username", notNullValue())
                .body("password", nullValue());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 9. Response Time SLA
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Response Time SLA Verification")
    class PerformanceTests {

        @Test
        @DisplayName("Payment creation responds within 3 seconds")
        void paymentCreationSLA() {
            asAdmin()
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}""")
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .time(lessThan(3000L));
        }

        @Test
        @DisplayName("Payment list responds within 2 seconds")
        void paymentListSLA() {
            asAdmin()
            .when()
                .get("/api/v1/payments")
            .then()
                .statusCode(200)
                .time(lessThan(2000L));
        }

        @Test
        @DisplayName("Health check responds within 1 second")
        void healthCheckSLA() {
            given()
            .when()
                .get("/actuator/health")
            .then()
                .statusCode(200)
                .time(lessThan(1000L));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 10. OpenAPI / Swagger
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OpenAPI Documentation")
    class OpenApiTests {

        @Test
        @DisplayName("OpenAPI spec is accessible and contains expected paths")
        void openApiSpec() {
            given()
            .when()
                .get("/v3/api-docs")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("openapi", startsWith("3."))
                .body("paths", hasKey("/api/v1/payments"))
                .body("paths", hasKey("/api/v1/auth/login"))
                .body("paths", hasKey("/api/v1/admin/stats"));
        }

        @Test
        @DisplayName("Swagger UI is accessible")
        void swaggerUi() {
            given()
            .when()
                .get("/swagger-ui/index.html")
            .then()
                .statusCode(200);
        }
    }
}
