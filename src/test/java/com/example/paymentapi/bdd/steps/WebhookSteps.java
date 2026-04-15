package com.example.paymentapi.bdd.steps;

import com.example.paymentapi.bdd.ScenarioContext;
import io.cucumber.java.en.*;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class WebhookSteps {

    @Autowired private ScenarioContext ctx;

    @Value("${local.server.port:8080}")
    private int port;

    // ── Authentication ──────────────────────────────────────────────────────────

    @Given("I am logged in as {string} with password {string}")
    public void loginAs(String username, String password) {
        String token = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .extract().path("token");
        ctx.setAuthToken(token);
    }

    // ── Subscription registration ───────────────────────────────────────────────

    @When("I register a webhook for events {string} pointing to {string} with token {string}")
    public void registerWebhook(String events, String url, String token) {
        if ("ECHO".equals(url)) url = "http://localhost:" + port + "/test/webhook-echo";
        String[] parts = events.split(",");
        StringBuilder types = new StringBuilder("[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) types.append(",");
            types.append("\"").append(parts[i].trim()).append("\"");
        }
        types.append("]");

        String body = String.format(
                "{\"targetUrl\":\"%s\",\"bearerToken\":\"%s\",\"eventTypes\":%s,\"adminScope\":false,\"active\":true}",
                url, token, types);

        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/webhooks");
        ctx.setLastResponse(resp);
        if (resp.getStatusCode() == 201) {
            ctx.setWebhookSubscriptionId(resp.path("id").toString());
        }
    }

    @Given("I register a webhook for events {string} pointing to {string} with token {string} and active false")
    public void registerInactiveWebhook(String events, String url, String token) {
        if ("ECHO".equals(url)) url = "http://localhost:" + port + "/test/webhook-echo";
        String body = String.format(
                "{\"targetUrl\":\"%s\",\"bearerToken\":\"%s\",\"eventTypes\":[\"%s\"],\"adminScope\":false,\"active\":false}",
                url, token, events);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/webhooks");
        ctx.setLastResponse(resp);
        if (resp.getStatusCode() == 201) {
            ctx.setWebhookSubscriptionId(resp.path("id").toString());
        }
    }

    @When("I try to register an adminScope webhook for events {string} pointing to {string}")
    public void registerAdminScopeWebhook(String events, String url) {
        String body = String.format(
                "{\"targetUrl\":\"%s\",\"bearerToken\":\"tok\",\"eventTypes\":[\"%s\"],\"adminScope\":true,\"active\":true}",
                url, events);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/webhooks");
        ctx.setLastResponse(resp);
    }

    @And("I save the subscription ID")
    public void saveSubscriptionId() {
        // Already saved in registerWebhook if status was 201
    }

    // ── List / Get / Update / Delete ────────────────────────────────────────────

    @When("I list my webhook subscriptions")
    public void listWebhookSubscriptions() {
        Response resp = given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/webhooks");
        ctx.setLastResponse(resp);
    }

    @When("I update the subscription with targetUrl {string} events {string} and active {string}")
    public void updateSubscription(String url, String events, String active) {
        String body = String.format(
                "{\"targetUrl\":\"%s\",\"bearerToken\":\"updated-tok\",\"eventTypes\":[\"%s\"],\"adminScope\":false,\"active\":%s}",
                url, events, active);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .patch("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId());
        ctx.setLastResponse(resp);
    }

    @When("I delete the webhook subscription")
    public void deleteWebhookSubscription() {
        Response resp = given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .delete("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId());
        ctx.setLastResponse(resp);
    }

    @When("I try to get the saved subscription")
    public void tryGetSavedSubscription() {
        Response resp = given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId());
        ctx.setLastResponse(resp);
    }

    // ── Response assertions ─────────────────────────────────────────────────────

    @Then("the response status is {int}")
    public void assertResponseStatus(int expectedStatus) {
        assertThat(ctx.getLastResponse().getStatusCode(), equalTo(expectedStatus));
    }

    @Then("the response contains a webhook subscription with targetUrl {string}")
    public void assertTargetUrl(String expectedUrl) {
        assertThat(ctx.getLastResponse().path("targetUrl"), equalTo(expectedUrl));
    }

    @Then("the response bearerToken is masked as {string}")
    public void assertBearerTokenMasked(String expected) {
        assertThat(ctx.getLastResponse().path("bearerToken"), equalTo(expected));
    }

    @Then("the response contains at least {int} subscriptions")
    public void assertAtLeastSubscriptions(int minCount) {
        int actual = ((java.util.List<?>) ctx.getLastResponse().path("")).size();
        assertThat(actual, greaterThanOrEqualTo(minCount));
    }

    // ── Delivery assertions ─────────────────────────────────────────────────────

    @And("I wait {int}ms for async processing")
    public void waitForAsync(int millis) {
        // Poll until a delivery row appears, exiting early when it does.
        // ConditionTimeoutException is swallowed for scenarios that assert zero deliveries —
        // timing out is the correct outcome when no delivery should be created.
        try {
            await().atMost(millis, TimeUnit.MILLISECONDS)
                   .pollInterval(50, TimeUnit.MILLISECONDS)
                   .until(this::hasAnyDelivery);
        } catch (ConditionTimeoutException ignored) {
            // Expected in scenarios asserting zero deliveries
        }
    }

    private boolean hasAnyDelivery() {
        if (ctx.getWebhookSubscriptionId() == null) return false;
        String adminToken = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}")
            .when()
                .post("/api/v1/auth/login")
            .then().statusCode(200).extract().path("token");
        List<?> deliveries = given()
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId() + "/deliveries")
            .then().statusCode(200).extract().path("");
        return deliveries != null && !deliveries.isEmpty();
    }

    @Then("the webhook subscription has at least {int} pending or delivered delivery")
    public void assertAtLeastOneDelivery(int minCount) {
        String adminToken = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}")
            .when()
                .post("/api/v1/auth/login")
            .then().statusCode(200).extract().path("token");

        Response resp = given()
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId() + "/deliveries");
        assertThat(resp.getStatusCode(), equalTo(200));
        int count = ((java.util.List<?>) resp.path("")).size();
        assertThat(count, greaterThanOrEqualTo(minCount));
    }

    @Then("the webhook subscription has {int} deliveries")
    public void assertDeliveryCount(int expectedCount) {
        String adminToken = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}")
            .when()
                .post("/api/v1/auth/login")
            .then().statusCode(200).extract().path("token");

        Response resp = given()
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId() + "/deliveries");
        assertThat(resp.getStatusCode(), equalTo(200));
        int count = ((java.util.List<?>) resp.path("")).size();
        assertThat(count, equalTo(expectedCount));
    }
}
