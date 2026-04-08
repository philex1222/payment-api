package com.example.paymentapi.bdd.steps;

import com.example.paymentapi.bdd.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for authentication and session management scenarios.
 */
public class AuthSteps {

    @Autowired
    private ScenarioContext ctx;

    // ── Generic HTTP ───────────────────────────────────────────────────────────

    @Given("the API is available")
    public void theApiIsAvailable() {
        given().when().get("/actuator/health").then().statusCode(200);
    }

    @When("I GET {string} without authentication")
    public void getWithoutAuth(String path) {
        ctx.setLastResponse(given().when().get(path));
    }

    @When("I GET {string}")
    public void getAuthenticated(String path) {
        ctx.setLastResponse(
            given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get(path)
        );
    }

    @When("I POST to {string} with body:")
    public void postWithBody(String path, String body) {
        var spec = given().contentType(ContentType.JSON).body(body);
        if (ctx.getAuthToken() != null) {
            spec = spec.header("Authorization", "Bearer " + ctx.getAuthToken());
        }
        ctx.setLastResponse(spec.when().post(path));
    }

    // ── Login / logout ─────────────────────────────────────────────────────────

    @When("I login as {string} with password {string}")
    public void loginWith(String username, String password) {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
        );
    }

    @Given("I am authenticated as {string}")
    public void authenticatedAs(String username) {
        String password = "password";
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

    @Given("I am not authenticated")
    public void notAuthenticated() {
        ctx.setAuthToken(null);
    }

    @When("I logout")
    public void logout() {
        ctx.setLastResponse(
            given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .post("/api/v1/auth/logout")
        );
    }

    @When("I GET {string} with the invalidated token")
    public void getWithInvalidatedToken(String path) {
        // Token is still in context but has been blacklisted
        ctx.setLastResponse(
            given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get(path)
        );
    }

    // ── Profile ────────────────────────────────────────────────────────────────

    @When("I request my profile")
    public void requestProfile() {
        getAuthenticated("/api/v1/auth/me");
    }

    // ── Password ───────────────────────────────────────────────────────────────

    @When("I change my password from {string} to {string}")
    public void changePassword(String currentPw, String newPw) {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body("{\"currentPassword\":\"" + currentPw + "\",\"newPassword\":\"" + newPw + "\"}")
            .when()
                .post("/api/v1/auth/change-password")
        );
    }

    @Then("I can login with the new password {string}")
    public void canLoginWithNewPassword(String newPw) {
        String username = ctx.getFreshUsername();
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + newPw + "\"}")
        .when()
            .post("/api/v1/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue());
    }

    // ── Assertions ─────────────────────────────────────────────────────────────

    @Then("the response status code is {int}")
    public void responseStatusIs(int status) {
        assertEquals(status, ctx.getLastResponse().getStatusCode(),
                "Expected HTTP " + status + " but got " + ctx.getLastResponse().getStatusCode()
                + " — body: " + ctx.getLastResponse().asString());
    }

    @Then("the response contains a valid JWT token")
    public void responseContainsJwt() {
        String token = ctx.getLastResponse().path("token");
        assertNotNull(token, "token field must be present in response");
        assertTrue(token.length() > 10, "token must be a non-trivial JWT string");
    }

    @Then("the error message contains {string}")
    public void errorMessageContains(String expected) {
        String message = ctx.getLastResponse().path("message");
        assertThat(message, containsString(expected));
    }

    @Then("the error message does not contain {string}")
    public void errorMessageDoesNotContain(String text) {
        String message = ctx.getLastResponse().path("message");
        if (message != null) {
            assertThat(message, not(containsString(text)));
        }
    }

    @Then("the error is {string}")
    public void errorIs(String expected) {
        assertThat(ctx.getLastResponse().path("error").toString(), equalTo(expected));
    }

    @Then("the profile username is {string}")
    public void profileUsernameIs(String expected) {
        assertThat(ctx.getLastResponse().path("username").toString(), equalTo(expected));
    }

    @Then("the profile has a {string} field")
    public void profileHasField(String fieldName) {
        Object value = ctx.getLastResponse().path(fieldName);
        assertNotNull(value, "Expected field '" + fieldName + "' to be present and non-null");
    }
}
