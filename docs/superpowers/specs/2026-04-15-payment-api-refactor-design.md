# Payment API — Refactor & Optimisation Design

**Date:** 2026-04-15
**Author:** PhilipW1222 + Claude Sonnet 4.6
**Status:** Approved

---

## Goals

Deliver both clean architecture and measurable performance gains without changing any external API contracts or breaking the existing 555-test suite.

---

## Approach: Phased Refactor (A)

Three focused, independently deployable phases executed on separate branches. Each phase leaves CI green and the service deployable.

---

## Phase 1 — Architecture

### 1.1 CQRS Split of `PaymentServiceImpl`

`PaymentServiceImpl` (620 LOC) is a God class responsible for payment CRUD, reversals, cancellations, validation, state transitions, retries, caching, metrics, and events. It is split into a command side, a query side, and a shared horizontal layer.

#### Command side — `service/command/`

| Class | Responsibility |
|---|---|
| `CreatePaymentHandler` | Validates input via `PaymentValidationService`, drives state machine to `PENDING`, persists, publishes `PaymentCreatedEvent` |
| `ReversalHandler` | Auth check → state check via `PaymentStateMachine` → banking API → persist → publish `PaymentReversedEvent` |
| `CancellationHandler` | State check → persist `CANCELLED` → publish `PaymentCancelledEvent` |

#### Query side — `service/query/`

| Class | Responsibility |
|---|---|
| `PaymentQueryService` | All reads: `findById`, `findByUser`, `findAll` (admin), paginated. Returns JPA projections for list queries; full entity (via `@EntityGraph`) for detail fetches only. |

#### Shared horizontal layer — `service/shared/`

| Class | Responsibility |
|---|---|
| `PaymentStateMachine` | Single authoritative source for all status transitions (`PENDING → PROCESSING → COMPLETED / FAILED / REVERSED / CANCELLED`). Throws `InvalidPaymentStateException` on illegal transitions. Eliminates all scattered `if`/`switch` state blocks. |
| `PaymentValidationService` | Account validation, duplicate detection, amount/currency checks. Consolidates logic currently duplicated between `PaymentServiceImpl` and `BankingAPIServiceImpl`. |
| `PaymentEventPublisher` | Thin wrapper over Spring's `ApplicationEventPublisher`. All command handlers call this — no direct publisher injection in handlers. |

`PaymentController` injects `CreatePaymentHandler`, `ReversalHandler`, `CancellationHandler`, and `PaymentQueryService` directly. No facade layer needed.

### 1.2 Rate Limiter Consolidation

`RateLimitInterceptor` (123 LOC) and `LoginRateLimitInterceptor` (110 LOC) share ~70% of their logic. They are merged into a single `RateLimitInterceptor` that accepts a `RateLimitStrategy` (general vs login). Login strategy uses a tighter configurable bucket. Both strategies remain property-driven — no hardcoded values. Existing properties keys are preserved for backwards compatibility.

### 1.3 Config Class Consolidation

16 config classes are reorganised into 4 functional groups plus 2 focused standalone classes:

| Class | Absorbs |
|---|---|
| `WebConfig` | `WebMvcConfig`, `SwaggerConfig`, `RequestCorrelationFilter`, `AccessLogFilter` |
| `SecurityConfig` | Already one class; absorbs `LoginRateLimitInterceptor` registration logic |
| `ResilienceConfig` | `CacheConfig`, `SchedulingConfig`, rate limit property bindings |
| `PersistenceConfig` | `JpaAuditingConfig`, `AesGcmAttributeConverter` |
| `WebhookConfig` | Unchanged — already focused |
| `SsrfSafeDnsResolver` | Unchanged — already focused |

### 1.4 Constraints

- No changes to any REST endpoint signatures, request/response DTOs, or HTTP status codes.
- All 555 existing tests must pass after Phase 1.
- `PaymentService` interface (if present) is replaced by the four handler/query classes. Any existing interface is deleted; there are no external consumers.

---

## Phase 2 — Performance

### 2.1 Database — JPA Projections & N+1 Elimination

**Projection interfaces for list queries:**
All paginated and list methods in `PaymentQueryService` return projection interfaces (e.g. `PaymentSummary`) exposing only the columns needed (`id`, `status`, `amount`, `currency`, `createdAt`). Full `Payment` entities are never loaded for list queries.

**`@EntityGraph` for detail fetches:**
Single-entity fetches (e.g. `findById` for the detail endpoint) use a named `@EntityGraph` on the repository method to load associations in one JOIN query, eliminating the N+1 currently triggered when the controller accesses lazy relations post-load.

**Hibernate batch writes:**
`spring.jpa.properties.hibernate.jdbc.batch_size=20` is already set. Batch inserts only activate when `@GeneratedValue` uses `SEQUENCE` (not `IDENTITY`). Verify all entities use `SEQUENCE` strategy and add:
```properties
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

### 2.2 Caching — Redis Expansion

Three new cache regions added using Spring's `@Cacheable` / `@CacheEvict` — no manual `RedisTemplate` calls:

| Cache name | Key pattern | TTL | Eviction trigger |
|---|---|---|---|
| `payment-status` | `payment:{id}:status` | 60s | Any command handler that transitions status |
| `idempotency` | `idempotency:{key}` | 24h | Existing — verify Redis-backed (not in-memory) |
| `user-payment-list` | `user:{id}:payments:page:{n}` | 30s | Any create, cancel, or reversal by that user |

Cache name constants are defined in `PaymentConstants` (see Phase 3) — raw strings never appear at `@Cacheable` call sites.

### 2.3 Async / Concurrency — Virtual Threads (Java 21)

**Enable globally** via a single property:
```properties
spring.threads.virtual.enabled=true
```
Spring Boot 3.2+ auto-configures Tomcat, `@Async`, and `@Scheduled` to use virtual threads when this property is set.

**`AsyncConfig` removed:** The `ThreadPoolTaskExecutor` bean in `AsyncConfig` is deleted. Spring's auto-configured virtual-thread executor replaces it. `WebConfig` (Phase 1) no longer absorbs `AsyncConfig` — `AsyncConfig` is simply deleted.

**Webhook delivery:** `WebhookDeliveryExecutor` retains its `@Async` annotation. The explicit `Executor` bean injected from `WebhookConfig` is removed — Spring's virtual-thread executor is used automatically. One virtual thread per delivery attempt; no pool sizing required.

**Banking API calls:** `BankingAPIServiceImpl` makes blocking HTTP calls wrapped in Resilience4j. With virtual threads these block a virtual thread (cheap) instead of a platform thread — zero code change. Existing `TimeLimiter` (5s) and `CircuitBreaker` remain in place.

**Scheduler:** `@Scheduled` tasks run on the virtual-thread executor automatically. Remove `corePoolSize` and related config from `SchedulingConfig` / `ResilienceConfig`.

---

## Phase 3 — Quality Gates

### 3.1 New Unit Tests

**`TokenBlacklistServiceTest`** — covering:
- Token added to blacklist
- Token found in blacklist returns `true`
- Token not found returns `false`
- Expired token is not returned as blacklisted
- Redis failure fallback behaviour

**`RateLimitInterceptorTest`** (post-consolidation) — covering:
- Request under limit passes through
- Request over limit returns HTTP 429
- Login strategy uses a tighter bucket than the general strategy
- Key generation includes IP address and (where available) username
- Bucket resets correctly after the time window expires

No existing tests are deleted or modified beyond what Phase 1 restructuring requires (import path updates only).

### 3.2 Constants Extraction

Two constants classes added:

**`PaymentConstants`** — covers:
- Error message strings (currently inline in exception constructors and `PaymentExceptionHandler`)
- Cache name strings (`CACHE_PAYMENT_STATUS`, `CACHE_USER_PAYMENT_LIST`, `CACHE_IDEMPOTENCY`)
- Timeout fallback values duplicated between properties and service code

**`WebhookConstants`** — covers:
- Webhook-specific error messages and retry-related magic numbers currently scattered in `WebhookServiceImpl` and `WebhookDeliveryExecutor`

### 3.3 JaCoCo Threshold: 75% → 80%

After Phase 3 tests land, update `pom.xml`:
```xml
<minimum>0.80</minimum>
```
The two new test classes plus the smaller, more focused CQRS classes (easier to reach full branch coverage) are expected to push actual coverage above 82%, providing a comfortable buffer above the gate.

---

## What Changes vs What Stays the Same

| Area | Changes | Stays the same |
|---|---|---|
| Service layer | `PaymentServiceImpl` → 4 handlers + 3 shared services | All other 21 service classes |
| Config | 16 classes → 4 groups + 2 standalone | `WebhookConfig`, `SsrfSafeDnsResolver` |
| Rate limiting | 2 interceptors → 1 configurable | Same runtime behaviour, same property keys |
| Async | `AsyncConfig` deleted, virtual threads enabled globally | All `@Async` / `@Scheduled` annotations |
| Caching | 3 new cache regions | Existing idempotency cache |
| Repositories | Projection interfaces + `@EntityGraph` added | All existing repo methods |
| Tests | 2 new test classes | All 555 existing tests |
| CI gates | JaCoCo 75% → 80% | All other gates |
| REST API | Nothing | All endpoints, DTOs, status codes |

---

## Success Criteria

- All 555 existing tests pass after each phase
- Zero compiler warnings after each phase
- All CI gates green after each phase (Build, CodeQL, Trivy, OWASP, Docker)
- JaCoCo ≥ 80% after Phase 3
- No external API contract changes (verified by existing REST Assured + BDD suite)
- `PaymentServiceImpl` deleted — no reference to it remains in the codebase
