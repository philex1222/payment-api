# payment-api — Comprehensive Refactor Design Spec
**Date:** 2026-04-13
**Status:** Approved
**Branch:** `paymentapi/refactor-v2` (starts from `paymentapi/hotfix-1`)
**Approach:** Single branch, four phased commit groups, green tests required before each phase advances

---

## Overview

Full production-quality pass across the entire payment-api codebase: commit an in-progress hotfix, upgrade Java and dependencies to latest stable, fix five documented code quality gaps, and harden the CI/CD pipelines (GitHub Actions + Jenkins). No new features. No speculative abstractions.

---

## Phase 1 — Hotfix: `@EnableScheduling` move

### Problem
`PaymentApplication.java` had `@EnableScheduling` at the top level, registering `ScheduledAnnotationBeanPostProcessor` unconditionally for every application context — including tests. `SchedulerServiceImpl` has two `@Scheduled` methods (`retryFailedPayments` every 60 s, `cleanupOldRecords` on cron) that were firing in tests despite `application-test.properties` setting `scheduler.enabled=false`. The `@ConditionalOnProperty` on `SchedulingConfig` only gated that config class; `@EnableScheduling` on `PaymentApplication` ran regardless.

### Fix
Remove `@EnableScheduling` from `PaymentApplication.java`. `SchedulingConfig.java` already carries it with `@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)` — that guard now works correctly.

### Files changed
| File | Change |
|------|--------|
| `PaymentApplication.java` | Remove `@EnableScheduling` + fix missing newline (already staged) |
| `PaymentApplicationTests.java` | Add `testProfileDisablesSchedulingInfrastructure` — asserts `ScheduledAnnotationBeanPostProcessor` beans are empty in test profile (already staged) |

### Commit
`fix(scheduling): move @EnableScheduling to SchedulingConfig, disable in test profile`

---

## Phase 2 — Dependency & Java upgrade

### Java 17 → 21

Spring Boot 3.5.x supports Java 21. The Jenkins agent already runs Java 21 (`eclipse-temurin:21-jdk-jammy`). Upgrading the application target eliminates the mismatch and unlocks Java 21 language features.

| File | Change |
|------|--------|
| `pom.xml` | `<java.version>17</java.version>` → `21` |
| `Dockerfile` stage 1 | `maven:3.9-eclipse-temurin-17-alpine` → `maven:3.9-eclipse-temurin-21-alpine` |
| `Dockerfile` stages 2 & 3 | `eclipse-temurin:17-jre-jammy` → `eclipse-temurin:21-jre-jammy` |

### Dependency changes (verified via `mvn versions:display-dependency-updates`)

**Already at latest stable — no change:**

| Library | Version | Reason to hold |
|---------|---------|----------------|
| Spring Boot | 3.5.13 | Latest 3.x; 4.1.0-M4 is milestone only |
| jjwt | 0.13.0 | No newer version reported |
| Resilience4j | 2.4.0 | No newer version reported |
| Tomcat | 10.1.53 | Latest 10.1.x; only 11.x beyond (requires Spring Boot 4.x) |
| Flyway | 11.9.2 | Latest 11.x; 12.x is a major requiring separate assessment |
| httpclient5 | 5.6 | Latest GA (5.5-alpha1 exists, skip) |
| JaCoCo | 0.8.14 | Latest (plugin shows only older versions as alternatives) |

**Safe upgrades:**

| Library | Current | Target | Scope |
|---------|---------|--------|-------|
| H2 | 2.3.232 | 2.4.240 | Test only |

**Major version bumps — try each individually; commit if `mvn verify` is green, revert and add a hold comment in `pom.xml` if red:**

| Library | Current | Available | Concern |
|---------|---------|-----------|---------|
| REST Assured | 5.5.7 | 6.0.0 | Major; all 30 REST Assured tests must pass after bump |
| logstash-logback | 8.1 | 9.0 | Major; logback appender config in `logback-spring.xml` may need adjustment |

**Pinned — do not touch:**

| Library | Version | Reason |
|---------|---------|--------|
| Cucumber | 7.22.1 | 7.23+ requires JUnit Platform 1.13; Spring Boot 3.5.x ships Platform 1.12.x — upgrade would break test discovery at runtime |
| springdoc | 2.8.9 | 3.0.3 requires Spring Boot 4.x |

### Commit
`chore(deps): upgrade Java 17→21, bump H2 to 2.4.240, assess REST Assured 6 and logstash 9`

---

## Phase 3 — Code quality gaps

Five items carried forward from the 2026-04-11 OMEGA lifecycle run, now verified by reading the actual source.

### Gap 1 — `Thread.sleep` in `WebhookSteps.waitForAsync` (line 171)

**Problem:** Fixed 500 ms sleep for async delivery assertions. Awaitility 4.2.2 is already on the test classpath via `spring-boot-starter-test` — no new dependency needed.

**Fix:** Replace `Thread.sleep(millis)` with an Awaitility poll that calls the delivery REST endpoint until at least one delivery row appears, exiting early when the condition is met. Wrap in try/catch for `ConditionTimeoutException` — required for the "inactive subscription → 0 deliveries" scenario, where no delivery ever appears and the timeout expiring is correct behaviour. Note: the scheduler is disabled in the test profile (`scheduler.enabled=false`), so deliveries stay PENDING — the step only needs to confirm the delivery row was created by `WebhookEventListener`, not that it was dispatched.

```java
@And("I wait {int}ms for async processing")
public void waitForAsync(int millis) {
    try {
        await().atMost(millis, MILLISECONDS)
               .pollInterval(50, MILLISECONDS)
               .until(this::hasAnyDelivery);
    } catch (ConditionTimeoutException ignored) {
        // Expected in scenarios that assert zero deliveries
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
```

Step signature unchanged — no feature file edits.

### Gap 2 — Log injection in `RequestCorrelationFilter` (line 35)

**Problem:** Incoming `X-Correlation-ID` header value is placed directly into MDC without sanitization. An attacker can inject `\n` to forge log lines.

**Fix:** Strip all CR, LF, and control characters (`\x00–\x1f`, `\x7f`) from the header value; cap at 64 characters; re-generate a UUID if the sanitized value is blank.

```java
correlationId = correlationId
    .replaceAll("[\\r\\n\\t\\x00-\\x1f\\x7f]", "")
    .strip();
if (correlationId.isBlank() || correlationId.length() > 64) {
    correlationId = UUID.randomUUID().toString();
}
```

New unit test: verifies that a header value containing `\n` is sanitized before MDC insertion and the response header reflects the sanitized value.

### Gap 3 — Raw `String` status in `WebhookDelivery`

**Problem:** `WebhookDelivery.status` stores `"PENDING"`, `"DELIVERED"`, `"FAILED"` as raw strings. Four callsites use string literals. `WebhookDeliveryRepository` JPQL at line 16 hardcodes `WHERE d.status = 'PENDING'`.

**Fix:** Create `WebhookDeliveryStatus` enum `{PENDING, DELIVERED, FAILED}`. Annotate `WebhookDelivery.status` with `@Enumerated(EnumType.STRING)`. Update all four callsites (`WebhookEventListener` line 80, `WebhookDeliveryExecutor` lines 50/63/66) to use the enum. Replace the JPQL string literal with a named parameter bound to `WebhookDeliveryStatus.PENDING`.

No Flyway migration needed — DB string values already match enum names exactly.

### Gap 4 — `WebhookDeliveryExecutor.executePost` has no unit tests

**Problem:** `executePost` (package-private) has three code paths not covered by `WebhookDispatcherServiceTest`: `HttpStatusCodeException` (4xx), `HttpStatusCodeException` (5xx), and the URL query-string sanitization in the catch block. The dispatcher test only mocks a generic `RuntimeException`.

**Fix:** New `WebhookDeliveryExecutorTest` using `@ExtendWith(MockitoExtension.class)` (plain unit test, not `@SpringBootTest`). Mock `WebhookDeliveryRepository`, `WebhookSubscriptionRepository`, and `RestClient` using the same Mockito chain-mock pattern already established in `WebhookDispatcherServiceTest`. Five test scenarios:

1. `executePost` 200 OK → returns 200
2. `executePost` 201 Created → returns 201
3. `executePost` throws `HttpClientErrorException(400)` → returns 400
4. `executePost` throws `HttpServerErrorException(500)` → returns 500
5. `executePost` throws generic `RuntimeException` with URL containing `?secret=token` → returns 0 and the logged URL has no query string

### Gap 5 — Bearer token stored plaintext

**Problem:** `WebhookSubscription.bearerToken` is stored as plaintext VARCHAR(512). The token is sent outbound on every delivery, so hashing is not viable — the plaintext is required at delivery time. Risk: DB compromise exposes all webhook bearer tokens.

**Fix:** AES-256-GCM encryption at rest via JPA `AttributeConverter`.

**Components:**
- `AesGcmAttributeConverter implements AttributeConverter<String, String>`: generates a random 12-byte IV per encryption, stores as `base64(iv || ciphertext)`. Decryption extracts the IV prefix before deciphering.
- Config property: `webhook.encryption.secret-key` (base64-encoded 32-byte key). Required in staging/prod; defaults to a fixed dev key when blank (H2 tests).
- `@Convert(converter = AesGcmAttributeConverter.class)` on `WebhookSubscription.bearerToken`.
- Flyway V12: Spring-managed `@Component` Java migration (Spring Boot passes `@Component`-annotated `JavaMigration` beans into Flyway automatically, enabling `@Value` injection of the secret key). V12 reads every existing `bearer_token` row and writes the encrypted value back in-place. H2 test DB has no rows at migration time — no data to migrate.

**No API change:** `WebhookSubscriptionResponse` already masks the token as `"***"` — unchanged.

### Commits (one per gap)
- `fix(webhook): replace Thread.sleep with Awaitility in WebhookSteps`
- `fix(security): sanitize X-Correlation-ID header to prevent log injection`
- `refactor(webhook): replace raw String status with WebhookDeliveryStatus enum`
- `test(webhook): add WebhookDeliveryExecutorTest covering all executePost paths`
- `security(webhook): encrypt bearer_token at rest with AES-256-GCM (V12 migration)`

---

## Phase 4 — CI/CD hardening

### `ci.yml` — Java version update
`JAVA_VERSION: "17"` (line 23) → `"21"`. Applied after Phase 2 Java upgrade is confirmed green. `cd.yml` and `security.yml` have no Java version references — no change needed there.

### `ci.yml` — Weekly no-cache Docker build
The `docker/build-push-action` step uses `cache-from: type=gha` / `cache-to: type=gha,mode=max`. The `apt-get upgrade` layer in the runtime Dockerfile can be served stale from the GHA cache indefinitely, preventing new upstream security patches from landing. Fix: add a `schedule: cron: '0 3 * * 1'` (Monday 03:00 UTC) trigger to `ci.yml` that sets `no-cache: true` on the build step. Daily CI runs keep the warm cache; the weekly run forces a cold rebuild from base images.

### `Jenkinsfile` — Stale comment
Header comment at line 21 reads `JDK   : name "temurin-17"`. The `tools` block already uses `temurin-21` (updated during Jenkins stack fix). Fix: update the comment to match.

### GHA action SHA updates
All five workflow files pin actions to commit SHAs (correct security practice). SHAs for the following actions need verification and update via `gh api` during implementation:
- `actions/checkout` (v6 in all workflows)
- `actions/setup-java` (v5 in `ci.yml`)
- `actions/upload-artifact` (v7 in `ci.yml`)
- `docker/setup-buildx-action` (v4 in `ci.yml`)
- `docker/build-push-action` (v7 in `ci.yml`, `cd.yml`)
- `docker/login-action` (v4 in `cd.yml`, `security.yml`)
- `aquasecurity/trivy-action` (v0.35.0 in `ci.yml`, `security.yml`)
- `github/codeql-action` (v4 in `ci.yml`)
- `dorny/test-reporter` (v3 in `ci.yml`)
- `dependency-check/Dependency-Check_Action` (1.1.0 in `ci.yml`)

### Commit
`ci: update JAVA_VERSION to 21, add weekly no-cache build, fix stale Jenkinsfile comment, update GHA action SHAs`

---

## Success criteria

- All 539 tests pass (plus new tests added in Phase 3)
- JaCoCo ≥ 75% line coverage gate passes
- `mvn verify` clean on Java 21
- GHA CI workflow green end-to-end (Docker Trivy scan passes)
- Jenkins `docker compose up -d` starts controller + agent cleanly, agent connects automatically
- No CRITICAL/HIGH fixable CVEs in the container image
- `WebhookDelivery.status` is type-safe (no raw string literals)
- Log injection via `X-Correlation-ID` is mitigated
- Bearer tokens encrypted at rest in DB

---

## Out of scope

- Spring Boot 4.x upgrade
- Flyway 12.x upgrade
- Tomcat 11.x upgrade
- springdoc 3.x upgrade
- Cucumber 7.23+ upgrade (blocked by JUnit Platform 1.12.x constraint)
- New features or API endpoints
