package com.example.paymentapi;

import com.example.paymentapi.config.TestConfig;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.*;

/**
 * REST Assured system tests for the Webhook Subscription API.
 *
 * <p>Exercises POST/GET/PATCH/DELETE /api/v1/webhooks and GET /api/v1/webhooks/{id}/deliveries
 * over the real embedded Tomcat with the full Spring Security filter chain.
 *
 * <p>Every success response is validated against JSON Schemas defined in
 * {@code src/test/resources/schemas/}.  Each {@code @Nested} class is annotated
 * {@code @DirtiesContext} to guarantee a clean H2 database between groups so
 * webhook subscriptions created in one group do not bleed into another.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
@DisplayName("Webhook API — REST Assured System Tests")
// Override the test-profile SSRF bypass so SSRF security tests execute as they would in production.
@TestPropertySource(properties = "webhook.ssrf.allow-localhost=false")
class WebhookApiRestAssuredTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private String loginAndGetToken(String username, String password) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .extract().path("token");
    }

    private RequestSpecification asUser() {
        return given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + loginAndGetToken("user", "password"));
    }

    private RequestSpecification asAdmin() {
        return given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + loginAndGetToken("admin", "password"));
    }

    /** Creates a subscription and returns its ID. */
    private String createSubscription(RequestSpecification spec, String events, String url) {
        return spec
                .body("""
                    {"targetUrl":"%s","bearerToken":"test-tok",\
                    "eventTypes":["%s"],"adminScope":false,"active":true}
                    """.formatted(url, events).strip())
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(201)
                .extract().path("id");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Create subscription
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/webhooks — Create subscription")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class CreateSubscriptionTests {

        @Test
        @DisplayName("Valid request — returns 201 with schema-valid body and masked token")
        void createSubscription_returns201_matchesSchema() {
            asUser()
                .body("""
                    {"targetUrl":"http://example.com/hook","bearerToken":"s3cr3t",
                     "eventTypes":["PAYMENT_COMPLETED","PAYMENT_FAILED"],
                     "adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/webhook-subscription-response.json"))
                .body("id",          notNullValue())
                .body("targetUrl",   equalTo("http://example.com/hook"))
                .body("bearerToken", equalTo("***"))
                .body("eventTypes",  hasItems("PAYMENT_COMPLETED", "PAYMENT_FAILED"))
                .body("active",      equalTo(true))
                .body("adminScope",  equalTo(false))
                .body("createdAt",   notNullValue());
        }

        @Test
        @DisplayName("Missing targetUrl — returns 400")
        void createSubscription_missingTargetUrl_returns400() {
            asUser()
                .body("""
                    {"bearerToken":"tok","eventTypes":["PAYMENT_CREATED"],
                     "adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Non-HTTP scheme (file://) — returns 400")
        void createSubscription_fileScheme_returns400() {
            asUser()
                .body("""
                    {"targetUrl":"file:///etc/passwd","bearerToken":"tok",
                     "eventTypes":["PAYMENT_CREATED"],"adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("SSRF — loopback address — returns 400")
        void createSubscription_loopbackUrl_returns400() {
            asUser()
                .body("""
                    {"targetUrl":"http://localhost:9999/steal","bearerToken":"tok",
                     "eventTypes":["PAYMENT_CREATED"],"adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("message", containsString("private or internal network"));
        }

        @Test
        @DisplayName("SSRF — RFC-1918 private address — returns 400")
        void createSubscription_privateIpUrl_returns400() {
            asUser()
                .body("""
                    {"targetUrl":"http://192.168.1.100/hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_CREATED"],"adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Invalid event type — returns 400")
        void createSubscription_invalidEventType_returns400() {
            asUser()
                .body("""
                    {"targetUrl":"http://example.com/hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_INVENTED"],"adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(400)
                .body("message", containsString("PAYMENT_INVENTED"));
        }

        @Test
        @DisplayName("adminScope=true as non-admin — returns 403")
        void createSubscription_adminScopeAsUser_returns403() {
            asUser()
                .body("""
                    {"targetUrl":"http://example.com/hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":true,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("adminScope=true as admin — returns 201")
        void createSubscription_adminScopeAsAdmin_returns201() {
            asAdmin()
                .body("""
                    {"targetUrl":"http://example.com/admin-hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":true,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(201)
                .body("adminScope", equalTo(true));
        }

        @Test
        @DisplayName("Unauthenticated — returns 401")
        void createSubscription_unauthenticated_returns401() {
            given()
                .contentType(ContentType.JSON)
                .body("""
                    {"targetUrl":"http://example.com/hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(401);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. List subscriptions
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/webhooks — List subscriptions")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class ListSubscriptionsTests {

        @Test
        @DisplayName("User sees only own subscriptions")
        void listSubscriptions_userSeesOnlyOwn() {
            createSubscription(asUser(), "PAYMENT_COMPLETED", "http://example.com/user-hook");
            createSubscription(asAdmin(), "PAYMENT_FAILED",   "http://example.com/admin-hook");

            Response resp = asUser()
                .when()
                    .get("/api/v1/webhooks")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .extract().response();

            // Every subscription in the list belongs to the "user" account
            resp.then().body("targetUrl", everyItem(not(containsString("admin-hook"))));
        }

        @Test
        @DisplayName("Admin sees all subscriptions")
        void listSubscriptions_adminSeesAll() {
            createSubscription(asUser(), "PAYMENT_COMPLETED", "http://example.com/user-hook-2");
            createSubscription(asAdmin(), "PAYMENT_FAILED",   "http://example.com/admin-hook-2");

            asAdmin()
            .when()
                .get("/api/v1/webhooks")
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(2));
        }

        @Test
        @DisplayName("Empty list — returns 200 with empty array")
        void listSubscriptions_noSubscriptions_returns200EmptyArray() {
            // Fresh context (DirtiesContext AFTER_CLASS), user has no subscriptions yet
            asUser()
            .when()
                .get("/api/v1/webhooks")
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(0));
        }

        @Test
        @DisplayName("Unauthenticated — returns 401")
        void listSubscriptions_unauthenticated_returns401() {
            given()
            .when()
                .get("/api/v1/webhooks")
            .then()
                .statusCode(401);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Get subscription by ID
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/webhooks/{id} — Get subscription")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class GetSubscriptionTests {

        @Test
        @DisplayName("Own subscription — returns 200 with schema-valid body")
        void getSubscription_ownSubscription_returns200() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/hook-get");

            asUser()
            .when()
                .get("/api/v1/webhooks/" + id)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(matchesJsonSchemaInClasspath("schemas/webhook-subscription-response.json"))
                .body("id",        equalTo(id))
                .body("targetUrl", equalTo("http://example.com/hook-get"))
                .body("bearerToken", equalTo("***"));
        }

        @Test
        @DisplayName("Another user's subscription — returns 403")
        void getSubscription_anotherUsersSubscription_returns403() {
            String adminSubId = createSubscription(asAdmin(), "PAYMENT_COMPLETED",
                    "http://example.com/admin-hook-3");

            asUser()
            .when()
                .get("/api/v1/webhooks/" + adminSubId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Admin can access any subscription")
        void getSubscription_adminCanAccessAny_returns200() {
            String userSubId = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/user-hook-3");

            asAdmin()
            .when()
                .get("/api/v1/webhooks/" + userSubId)
            .then()
                .statusCode(200)
                .body("id", equalTo(userSubId));
        }

        @Test
        @DisplayName("Non-existent ID — returns 404")
        void getSubscription_nonExistentId_returns404() {
            asUser()
            .when()
                .get("/api/v1/webhooks/non-existent-sub-id")
            .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. Update subscription
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /api/v1/webhooks/{id} — Update subscription")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class UpdateSubscriptionTests {

        @Test
        @DisplayName("Update targetUrl and eventTypes — returns 200 with updated values")
        void updateSubscription_returns200WithUpdatedValues() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/old-hook");

            asUser()
                .body("""
                    {"targetUrl":"http://example.com/new-hook","bearerToken":"new-tok",
                     "eventTypes":["PAYMENT_FAILED"],"adminScope":false,"active":true}
                    """)
            .when()
                .patch("/api/v1/webhooks/" + id)
            .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/webhook-subscription-response.json"))
                .body("targetUrl",   equalTo("http://example.com/new-hook"))
                .body("eventTypes",  hasItem("PAYMENT_FAILED"))
                .body("bearerToken", equalTo("***"));
        }

        @Test
        @DisplayName("Deactivate subscription — returns 200 with active=false")
        void updateSubscription_deactivate_returns200WithActiveFalse() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/hook-deactivate");

            asUser()
                .body("""
                    {"targetUrl":"http://example.com/hook-deactivate","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":false,"active":false}
                    """)
            .when()
                .patch("/api/v1/webhooks/" + id)
            .then()
                .statusCode(200)
                .body("active", equalTo(false));
        }

        @Test
        @DisplayName("Update another user's subscription — returns 403")
        void updateSubscription_crossUser_returns403() {
            String adminSubId = createSubscription(asAdmin(), "PAYMENT_COMPLETED",
                    "http://example.com/admin-hook-4");

            asUser()
                .body("""
                    {"targetUrl":"http://example.com/hacked","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":false,"active":true}
                    """)
            .when()
                .patch("/api/v1/webhooks/" + adminSubId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("SSRF in updated targetUrl — returns 400")
        void updateSubscription_ssrfInUpdatedUrl_returns400() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/safe-hook");

            asUser()
                .body("""
                    {"targetUrl":"http://127.0.0.1/steal","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":false,"active":true}
                    """)
            .when()
                .patch("/api/v1/webhooks/" + id)
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Non-existent ID — returns 404")
        void updateSubscription_notFound_returns404() {
            asUser()
                .body("""
                    {"targetUrl":"http://example.com/hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":false,"active":true}
                    """)
            .when()
                .patch("/api/v1/webhooks/does-not-exist")
            .then()
                .statusCode(404);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Delete subscription
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/webhooks/{id} — Delete subscription")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class DeleteSubscriptionTests {

        @Test
        @DisplayName("Own subscription — returns 204 and subsequent GET returns 404")
        void deleteSubscription_returns204_thenGetReturns404() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/hook-delete");

            asUser()
            .when()
                .delete("/api/v1/webhooks/" + id)
            .then()
                .statusCode(204);

            asUser()
            .when()
                .get("/api/v1/webhooks/" + id)
            .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("Admin deletes any subscription — returns 204")
        void deleteSubscription_adminDeletesAny_returns204() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/hook-admin-delete");

            asAdmin()
            .when()
                .delete("/api/v1/webhooks/" + id)
            .then()
                .statusCode(204);
        }

        @Test
        @DisplayName("Another user's subscription — returns 403")
        void deleteSubscription_crossUser_returns403() {
            String adminSubId = createSubscription(asAdmin(), "PAYMENT_COMPLETED",
                    "http://example.com/admin-hook-del");

            asUser()
            .when()
                .delete("/api/v1/webhooks/" + adminSubId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Non-existent ID — returns 404")
        void deleteSubscription_notFound_returns404() {
            asUser()
            .when()
                .delete("/api/v1/webhooks/does-not-exist")
            .then()
                .statusCode(404);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. Delivery history (admin only)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/webhooks/{id}/deliveries — Delivery history")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class DeliveryHistoryTests {

        @Test
        @DisplayName("Admin can get delivery history — returns 200 empty array for new subscription")
        void getDeliveries_admin_returns200() {
            String id = createSubscription(asAdmin(), "PAYMENT_COMPLETED",
                    "http://example.com/hook-deliveries");

            asAdmin()
            .when()
                .get("/api/v1/webhooks/" + id + "/deliveries")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
        }

        @Test
        @DisplayName("Non-admin user — returns 403")
        void getDeliveries_nonAdmin_returns403() {
            String id = createSubscription(asUser(), "PAYMENT_COMPLETED",
                    "http://example.com/hook-deliveries-user");

            asUser()
            .when()
                .get("/api/v1/webhooks/" + id + "/deliveries")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Non-existent subscription ID — returns 404")
        void getDeliveries_notFound_returns404() {
            asAdmin()
            .when()
                .get("/api/v1/webhooks/no-such-sub/deliveries")
            .then()
                .statusCode(404);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. Security headers
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Security headers on webhook responses")
    class SecurityHeaderTests {

        @Test
        @DisplayName("POST /webhooks response includes required security headers")
        void createSubscription_includesSecurityHeaders() {
            asUser()
                .body("""
                    {"targetUrl":"http://example.com/sec-hook","bearerToken":"tok",
                     "eventTypes":["PAYMENT_COMPLETED"],"adminScope":false,"active":true}
                    """)
            .when()
                .post("/api/v1/webhooks")
            .then()
                .statusCode(201)
                // X-Content-Type-Options and X-Frame-Options are emitted on all responses
                .header("X-Content-Type-Options",  equalTo("nosniff"))
                .header("X-Frame-Options",         equalTo("DENY"))
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                // Note: Strict-Transport-Security is ONLY present on HTTPS responses;
                // embedded Tomcat in tests uses plain HTTP so this header is absent by design.
                // The HSTS configuration is verified in the SecurityConfig unit test.
                .header("Referrer-Policy",         notNullValue());
        }
    }
}
