package com.example.paymentapi.bdd;

import io.restassured.response.Response;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Per-scenario mutable state shared across all step definition classes.
 *
 * <p>The {@code cucumber-glue} scope ensures a fresh instance is created for
 * each Cucumber scenario and discarded at the end of it, providing the same
 * isolation guarantee as JUnit's per-method instance lifecycle.
 */
@Component
@Scope("cucumber-glue")
public class ScenarioContext {

    /** Bearer token for the currently authenticated user. */
    private String authToken;

    /** The most recent HTTP response received. */
    private Response lastResponse;

    /** Payment ID created within the current scenario. */
    private String lastPaymentId;

    /** Username chosen for the current scenario's freshly registered user. */
    private String freshUsername;

    /** Idempotency key used in the current scenario. */
    private String idempotencyKey;

    // ── accessors ─────────────────────────────────────────────────────────────

    public String getAuthToken()                { return authToken; }
    public void   setAuthToken(String t)        { this.authToken = t; }

    public Response getLastResponse()           { return lastResponse; }
    public void     setLastResponse(Response r) { this.lastResponse = r; }

    public String getLastPaymentId()            { return lastPaymentId; }
    public void   setLastPaymentId(String id)   { this.lastPaymentId = id; }

    public String getFreshUsername()            { return freshUsername; }
    public void   setFreshUsername(String u)    { this.freshUsername = u; }

    public String getIdempotencyKey()           { return idempotencyKey; }
    public void   setIdempotencyKey(String k)   { this.idempotencyKey = k; }
}
