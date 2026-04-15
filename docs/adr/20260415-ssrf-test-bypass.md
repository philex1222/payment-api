# ADR-20260415: SSRF guard bypass for test profile

**Status:** Accepted  
**Date:** 2026-04-15  
**Deciders:** OMEGA Lifecycle Engineer (automated), PhilipW1222

---

## Context

The `WebhookServiceImpl.validateTargetUrl()` method enforces SSRF protection by rejecting any webhook target URL that resolves to a loopback, link-local, or RFC-1918 address. This is a correct production security control.

The BDD webhook delivery scenarios (`webhook_delivery.feature`) require registering a webhook subscription pointing to an in-process echo endpoint at `http://localhost:{port}/test/webhook-echo` so that the application can POST deliveries to itself during integration testing. With the SSRF guard active, this registration returns 400 and the subscription ID is never set, causing delivery assertion steps to receive 404 from `GET /api/v1/webhooks/null/deliveries`.

Three options were considered:

1. **Remove the SSRF guard for tests using `Mockito.when()`** — rejected: this violates the Real Infrastructure Mandate and means the SSRF code is never exercised with real HTTP clients.
2. **Use a public echo service (e.g., `https://webhook.site`)** — rejected: introduces external network dependency in CI, is flaky, and leaks payload data to a third party.
3. **Add a `webhook.ssrf.allow-localhost` property (default `false`)** — chosen: minimal production-code change, fully auditable, zero risk (default is false in all non-test profiles).

---

## Decision

Add a `@Value("${webhook.ssrf.allow-localhost:false}")` field to `WebhookServiceImpl`. When `true`, the loopback/private-IP branch of `validateTargetUrl()` is skipped. Set `webhook.ssrf.allow-localhost=true` in `application-test.properties` only.

Existing SSRF unit tests (`WebhookServiceImplTest`, `WebhookApiRestAssuredTest`) that assert loopback rejection are annotated with `@TestPropertySource(properties = "webhook.ssrf.allow-localhost=false")` to override the test-profile setting and maintain their enforcement.

---

## Consequences

**Positive:**
- All BDD webhook delivery scenarios pass without mocking or external dependencies.
- The SSRF guard remains active in all production and CI container builds.
- Existing SSRF security tests continue to enforce the production behaviour.

**Negative:**
- One line of production code carries a test-facing feature flag. Future maintainers must understand why this property exists.
- The guard is only bypassed when `webhook.ssrf.allow-localhost=true` is explicitly set; accidental bypass in production requires a deliberate configuration change.

**Mitigations:**
- Property defaults to `false` — safe by default.
- Inline Javadoc on the field explains the intent and warns against enabling in production.
