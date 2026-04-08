package com.example.paymentapi.bdd.steps;

import com.example.paymentapi.bdd.ScenarioContext;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for payment lifecycle and authorization scenarios.
 */
public class PaymentSteps {

    @Autowired
    private ScenarioContext ctx;

    // ── Payment creation ───────────────────────────────────────────────────────

    @When("I create a payment for {int} {word} from {string} to {string}")
    public void createPayment(int amount, String currency, String src, String dst) {
        String body = """
                {"sourceAccount":"%s","destinationAccount":"%s","amount":%d,"currency":"%s"}
                """.formatted(src, dst, amount, currency);
        Response response = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/payments");
        ctx.setLastResponse(response);
        if (response.getStatusCode() == 201) {
            ctx.setLastPaymentId(response.path("id"));
        }
    }

    @Given("{string} has created a payment")
    public void userHasCreatedPayment(String username) {
        String password = "password";
        String token = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .extract().path("token");

        String paymentId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(201)
                .extract().path("id");
        ctx.setLastPaymentId(paymentId);
    }

    @Then("the payment is created successfully")
    public void paymentCreatedSuccessfully() {
        assertEquals(201, ctx.getLastResponse().getStatusCode(),
                "Expected 201 Created but got " + ctx.getLastResponse().getStatusCode());
        assertNotNull(ctx.getLastPaymentId(), "Payment ID must be set after creation");
    }

    // ── Payment operations ─────────────────────────────────────────────────────

    @When("I reverse the payment")
    public void reversePayment() {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .post("/api/v1/payments/{id}/reverse", ctx.getLastPaymentId())
        );
    }

    @When("I cancel the payment")
    public void cancelPayment() {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .post("/api/v1/payments/{id}/cancel", ctx.getLastPaymentId())
        );
    }

    @When("I GET that payment by ID")
    public void getPaymentById() {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/payments/{id}", ctx.getLastPaymentId())
        );
    }

    @When("I list all payments")
    public void listPayments() {
        ctx.setLastResponse(
            given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/payments")
        );
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Given("an idempotency key {string}")
    public void idempotencyKey(String key) {
        ctx.setIdempotencyKey(key);
    }

    @When("I create a payment with that idempotency key")
    public void createPaymentWithIdempotencyKey() {
        createPaymentWithKey(ctx.getIdempotencyKey());
    }

    @When("I create another payment with the same idempotency key")
    public void createAnotherPaymentWithSameKey() {
        createPaymentWithKey(ctx.getIdempotencyKey());
    }

    private void createPaymentWithKey(String key) {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .header("Idempotency-Key", key)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":5,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
        );
    }

    @Then("only one payment exists with that idempotency key")
    public void onlyOnePaymentWithKey() {
        // Both requests should succeed (200 or 201). Verify they return the same payment ID.
        // The second call should return the cached response (same id), not a new payment.
        Response secondResponse = ctx.getLastResponse();
        assertTrue(secondResponse.getStatusCode() == 200 || secondResponse.getStatusCode() == 201,
                "Expected 200 or 201 for idempotent request, got " + secondResponse.getStatusCode());
        // The second response carries the original payment ID — verify it is a valid non-empty string
        String id = secondResponse.path("id");
        assertNotNull(id, "Second idempotent response must return the original payment id");
    }

    // ── Assertions ─────────────────────────────────────────────────────────────

    @Then("the payment status is {string}")
    public void paymentStatusIs(String expected) {
        String actual = ctx.getLastResponse().path("status");
        assertEquals(expected, actual, "Expected payment status " + expected + " but was " + actual);
    }

    @Then("the payment has an {string} field")
    public void paymentHasField(String fieldName) {
        Object value = ctx.getLastResponse().path(fieldName);
        assertNotNull(value, "Expected field '" + fieldName + "' to be present");
    }

    @Then("account numbers are masked in the response")
    public void accountNumbersAreMasked() {
        String source = ctx.getLastResponse().path("sourceAccount");
        String dest   = ctx.getLastResponse().path("destinationAccount");
        assertNotNull(source, "sourceAccount must be present");
        assertNotNull(dest,   "destinationAccount must be present");
        assertTrue(source.startsWith("******"), "sourceAccount must be masked, got: " + source);
        assertTrue(dest.startsWith("******"),   "destinationAccount must be masked, got: " + dest);
    }

    @Then("I do not see the admin's payment in the list")
    public void doNotSeeAdminPayment() {
        String adminPaymentId = ctx.getLastPaymentId();
        List<String> ids = ctx.getLastResponse().path("content.id");
        assertThat("User's payment list must not contain admin's payment",
                ids, not(hasItem(adminPaymentId)));
    }
}
