# ADR-20260415: Spring Data Page serialization — VIA_DTO mode

**Status:** Accepted  
**Date:** 2026-04-15  
**Deciders:** OMEGA Lifecycle Engineer (automated), PhilipW1222

---

## Context

Spring Data's `PageImpl` class has historically been serialized by Jackson by reflecting on its fields, producing a flat JSON structure:

```json
{"content":[...],"totalElements":5,"number":0,"size":10,"totalPages":1,...}
```

Since Spring Boot 3.3, the framework logs a runtime `WARN` at startup:

```
Serializing PageImpl instances as-is is not supported, meaning the internal 
structure might change without notice.
```

This warning documents that the flat format is unstable — Spring may change or remove `PageImpl` fields in any future release. The recommended stable replacement is `PagedModel` (via `VIA_DTO` mode), which nests pagination metadata under a `"page"` key:

```json
{"content":[...],"page":{"totalElements":5,"number":0,"size":10,"totalPages":1}}
```

---

## Decision

Add `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` to `PaymentApplication`. This:

1. Silences the runtime warning.
2. Adopts the stable, contract-versioned format that Spring Data guarantees across future releases.
3. Changes the JSON structure of all paginated endpoint responses.

All affected test assertions in `PaymentApiRestAssuredTest`, `PaymentIntegrationTest`, and `PaymentSystemTest` are updated to use the new `page.totalElements` / `page.number` / `page.size` paths.

---

## Consequences

**Positive:**
- Zero runtime warnings in application startup logs.
- Page JSON format is now stable across Spring Data upgrades.
- Easier for API consumers to distinguish content from pagination metadata.

**Negative / Breaking:**
- Any existing API consumers that parse the flat format (`$.totalElements`, `$.number`, etc.) must migrate to `$.page.totalElements`, `$.page.number`, etc. This is a breaking API change for the pagination envelope.
- The OpenAPI spec (`openapi.yaml`) must be updated to reflect the new Page schema if it explicitly models the response structure.

**Mitigations:**
- The change is documented in CHANGELOG.md and this ADR.
- A future API versioning strategy (e.g., `/api/v2/payments`) can provide a migration path for consumers.
