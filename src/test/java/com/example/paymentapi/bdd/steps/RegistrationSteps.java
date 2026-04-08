package com.example.paymentapi.bdd.steps;

import com.example.paymentapi.bdd.ScenarioContext;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for user registration scenarios.
 */
public class RegistrationSteps {

    @Autowired
    private ScenarioContext ctx;

    @Given("a unique registration username")
    public void uniqueRegistrationUsername() {
        // Unique per scenario run to avoid collisions in the shared H2 db
        ctx.setFreshUsername("bdd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }

    @When("I register with that username and password {string}")
    public void registerWithFreshUsername(String password) {
        String username = ctx.getFreshUsername();
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/register")
        );
    }

    @When("I register with username {string} and password {string}")
    public void registerWith(String username, String password) {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/register")
        );
    }

    @Given("I have registered a fresh user")
    public void registerFreshUser() {
        String username = "pw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String password = "SecurePass1";
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when()
            .post("/api/v1/auth/register")
        .then()
            .statusCode(201);

        // Store username and log in
        ctx.setFreshUsername(username);
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

    @Then("the response username matches the registered username")
    public void responseUsernameMatchesFresh() {
        assertThat(ctx.getLastResponse().path("username").toString(),
                equalTo(ctx.getFreshUsername()));
    }

    @Then("the response role is {string}")
    public void responseRoleIs(String role) {
        assertThat(ctx.getLastResponse().path("role").toString(), equalTo(role));
    }

    @Then("the response has a {string} timestamp")
    public void responseHasTimestamp(String fieldName) {
        Object value = ctx.getLastResponse().path(fieldName);
        assertNotNull(value, "Expected '" + fieldName + "' to be present and non-null in response");
    }

    @And("the new user can login immediately")
    public void newUserCanLoginImmediately() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\":\"" + ctx.getFreshUsername() + "\",\"password\":\"SecurePass1\"}")
        .when()
            .post("/api/v1/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue());
    }
}
