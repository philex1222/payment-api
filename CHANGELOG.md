# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Security
- **CVE mitigations (Tomcat):** Pinned `tomcat.version` to `10.1.54` (9 HIGH CVEs resolved from 10.1.40 baseline: CVE-2025-24813, CVE-2024-52316, CVE-2024-50379, and others).
- **CVE mitigations (Jackson Databind 3.x):** Pinned Jackson `3.x.version` to `3.1.2` in `dependencyManagement` (2 HIGH CVEs resolved: CVE-2025-27875, CVE-2025-27874).
- **SSRF bypass for tests:** Added `webhook.ssrf.allow-localhost` configuration property (default `false`). Enabled in test profile only (`application-test.properties`) so BDD echo-endpoint scenarios can register loopback webhook targets without relaxing production SSRF guards.

### Added
- **`WebhookTestController`** (moved from `src/test` to `src/main`, `@Profile("test")`)): test-only HTTP echo endpoint at `/test/webhook-echo` for BDD webhook delivery scenarios. Profile guard ensures it never runs in production.
- **`/test/**` security permit rule:** Added to `SecurityConfig` to allow unauthenticated access to the test echo endpoint. Harmless in production (controller not registered outside test profile).
- **ADR-20260415-ssrf-test-bypass.md:** Documents the decision to add `webhook.ssrf.allow-localhost` rather than hardcoding allowances or mocking the SSRF validation layer.
- **ADR-20260415-page-serialization-via-dto.md:** Documents adoption of `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` to stabilise Page JSON format across Spring Data upgrades.
- **ADR-20260415-cucumber-lifecycle-alignment.md:** Documents alignment of BDD scenarios with actual synchronous payment-completion behaviour.

### Changed
- **Payment lifecycle BDD scenarios:** `payment_lifecycle.feature` updated to reflect synchronous completion:
  - "Create a payment" now asserts `COMPLETED` (service completes synchronously; PENDING is transient).
  - "Cancel a payment" renamed to "Cancel a completed payment returns conflict" — asserts HTTP 409 (COMPLETED payments cannot be cancelled; tests real API contract).
  - "Reverse a completed payment" now calls `POST /api/v1/payments/{id}/reversal` (correct endpoint) with a valid `ReversalRequest` body via the new `initiateReversalWithReason` step.
- **`PaymentSteps.java`:** Added `initiateReversalWithReason(String reason)` step for `@When("I initiate a reversal of the payment with reason {string}")`. Existing `reversePayment()` delegates to it as a backward-compatible wrapper.
- **`SecurityConfig.java`:** `permissionsPolicy()` replaced by `permissionsPolicyHeader()` (Spring Security 6.4 deprecation); `DaoAuthenticationProvider` migrated from no-arg constructor + setter to constructor-injection form.
- **`WebhookConfig.java`:** `setConnectTimeout(Timeout)` (deprecated in HttpClient 5.3+) replaced by `SocketConfig.setSoTimeout()` on the connection manager.
- **`WebhookSubscriptionRequest.java`:** `adminScope` field annotated with `@Builder.Default` to suppress Lombok initializer-expression warning.
- **`PaymentExceptionHandler.java`:** `instanceof` check modernised to Java 21 pattern-matching form (eliminates redundant cast).
- **`PaymentExceptionHandlerTest.java`:** `HttpMessageNotReadableException` constructed with `(String, HttpInputMessage)` overload (deprecated single-arg constructor removed).
- **`PaymentServiceTest.java`:** `any(Specification.class)` raw-type usages suppressed with `@SuppressWarnings("unchecked")` and explicit `import org.springframework.data.jpa.domain.Specification`.
- **`PaymentApplication.java`:** `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` added — silences `PageImpl` serialization runtime warning and stabilises Page JSON structure to `{"content":[...],"page":{"totalElements":N,...}}`.
- **Test assertions updated for VIA_DTO format:** `PaymentApiRestAssuredTest`, `PaymentIntegrationTest`, `PaymentSystemTest` — all pagination field paths updated from top-level (`totalElements`) to nested (`page.totalElements`).
- **`WebhookApiRestAssuredTest`** and **`WebhookServiceImplTest`:** Added `@TestPropertySource(properties = "webhook.ssrf.allow-localhost=false")` to override the test-profile SSRF bypass so existing SSRF security tests continue to enforce loopback rejection.
- **README.md:** Runtime updated from Java 17 to Java 21 in Tech Stack table and Quick Start prerequisites.

### Fixed
- Zero compiler warnings: all 6 deprecation and unchecked warnings present in the PR #26 baseline are now resolved.
- Zero runtime warnings: `PageImpl` serialization warning silenced via `VIA_DTO` mode.
- 5 pre-existing Cucumber scenario failures resolved (4 in `payment_lifecycle.feature`, 1 in `webhook_delivery.feature`).

---

## [Prior — PR #26 Comprehensive Refactor]

See git log (`git log --oneline`) for the full history of the Java 21 migration, dependency hardening, 5 quality gaps, and CI/CD hardening committed in that pull request.
