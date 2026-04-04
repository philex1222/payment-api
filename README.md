# Payment API

A production-grade RESTful microservice for payment processing built with Spring Boot 3. Supports the full payment lifecycle — create, track, retry, reverse, refund, and cancel — with stateless JWT authentication, per-user ownership enforcement, structured JSON logging, Prometheus metrics, distributed tracing, and Resilience4j circuit breaking.

---

## Table of Contents

1. [Features](#features)
2. [Tech Stack](#tech-stack)
3. [Quick Start — Local Dev](#quick-start--local-dev)
4. [Docker Deployment](#docker-deployment)
5. [API Reference](#api-reference)
6. [Security](#security)
7. [Configuration Reference](#configuration-reference)
8. [Testing](#testing)
9. [CI/CD](#cicd)
10. [Kubernetes / Helm](#kubernetes--helm)
11. [Observability](#observability)
12. [Database Migrations](#database-migrations)
13. [Project Structure](#project-structure)

---

## Features

- **Payment lifecycle** — PENDING -> PROCESSING -> COMPLETED / FAILED / CANCELLED / REVERSED / REFUNDED
- **Multi-currency** — ISO 4217 currency codes with automatic conversion
- **Retry** — automatic retry of FAILED payments via scheduler + manual retry endpoint (configurable max attempts)
- **Reversal & refund** — full reversal or partial refund of COMPLETED payments
- **Idempotency** — `Idempotency-Key` header prevents duplicate charges on network retries (Redis-backed, 24h TTL)
- **BOLA protection** — users can only read/modify their own payments; ROLE_ADMIN bypasses all ownership checks
- **JWT authentication** — HS512, configurable expiry, issuer-validated; logout invalidates tokens via blacklist
- **Rate limiting** — per-client buckets (100 req/60s general, 10 req/60s login) on all `/api/v1/**` routes
- **Circuit breaker** — Resilience4j on the downstream banking API with retry + time limiter
- **Scheduled jobs** — automatic retry of FAILED payments; nightly cleanup of stale records and old audit logs
- **Audit trail** — every payment event logged with actor identity (user or "system" for scheduled jobs)
- **Structured JSON logging** — Logstash encoder for ELK / Grafana Loki; human-readable output in local/test profiles
- **Prometheus metrics** — payment counters (created, completed, failed, cancelled, retried, reversed, refunded), latency histograms, HikariCP pool, JVM heap
- **Grafana dashboard** — pre-built dashboard provisioned automatically via docker-compose
- **Distributed tracing** — Micrometer Tracing + Zipkin with correlation ID propagation
- **OpenAPI docs** — Swagger UI at `/swagger-ui/index.html`
- **75% line coverage enforced** — JaCoCo gate blocks broken builds

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.5.13 |
| Security | Spring Security 6, jjwt 0.13.0 (HS512), BCrypt |
| Persistence | Spring Data JPA, Hibernate, MySQL 8.4 (prod), H2 (local/test) |
| Migrations | Flyway 10 (V1-V8) |
| Cache / Idempotency | Redis 7.4 (docker), Simple in-memory (local) |
| Resilience | Resilience4j 2.4.0 — circuit breaker, retry, rate limiter, time limiter |
| Metrics | Micrometer + Prometheus + Grafana |
| Tracing | Micrometer Tracing + Brave + Zipkin |
| Logging | Logback + Logstash encoder 8.1 |
| Docs | SpringDoc OpenAPI 2.8.9 |
| Build | Maven, JaCoCo 0.8.14 |
| Container | Docker (multi-stage, layered JAR), docker-compose |
| CI/CD | GitHub Actions (5-job CI + 3-job CD) |
| Deployment | Helm 3, Kubernetes |

---

## Quick Start -- Local Dev

No external services required. The `local` profile uses H2 in-memory database and simple in-memory cache.

### Prerequisites

- Java 17+
- Maven 3.8+

### Run

```bash
# Clone and build
git clone https://github.com/philex1222/payment-api.git
cd payment-api

# Start with local profile (H2, no Redis, no Zipkin, schedulers disabled)
mvn spring-boot:run
```

The `local` profile activates automatically for `mvn spring-boot:run`, `java -jar`, and IDE run configurations.

### Verify

```
GET  http://localhost:8080/actuator/health        -> { "status": "UP" }
GET  http://localhost:8080/swagger-ui/index.html   -> OpenAPI UI
GET  http://localhost:8080/h2-console              -> (JDBC URL: jdbc:h2:mem:localdb)
```

### Default users (seeded by `DataInitializer`)

| Username | Password | Role |
|---|---|---|
| `admin` | `password` | ROLE_ADMIN |
| `user` | `password` | ROLE_USER |

> These are for development only. Production deployments should disable `DataInitializer` or use strong passwords via environment variables.

---

## Docker Deployment

### Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin)
- Copy `.env.example` to `.env` and fill in all values

```bash
cp .env.example .env
# Edit .env -- set strong values for JWT_SECRET, DB_PASSWORD, REDIS_PASSWORD, etc.
# Generate JWT secret: openssl rand -hex 64
```

### Start the full stack

```bash
docker compose up -d
```

This starts: **app** (port 8080), **MySQL 8.4** (3306), **Redis 7.4** (6379), **Prometheus** (19090), **Grafana** (3000), **Zipkin** (9411).

### Verify

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:19090/-/healthy   # Prometheus
# Grafana: http://localhost:3000  (admin / $GF_ADMIN_PASSWORD)
# Zipkin:  http://localhost:9411
```

### Environment variables (`.env`)

| Variable | Description | Required |
|---|---|---|
| `JWT_SECRET` | HS512 signing key -- min 64 bytes (`openssl rand -hex 64`) | Yes |
| `DB_USERNAME` | MySQL application user | Yes |
| `DB_PASSWORD` | MySQL application password | Yes |
| `MYSQL_ROOT_PASSWORD` | MySQL root password (init only, not used at runtime) | Yes |
| `REDIS_PASSWORD` | Redis AUTH password | Yes |
| `GF_ADMIN_PASSWORD` | Grafana admin password | Yes |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins | Yes |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | Zipkin sample rate (default: 1.0, use 0.1 for prod) | No |

### Docker image

The Dockerfile uses a 3-stage build:
1. **Build** -- Maven compile + package (Alpine JDK 17)
2. **Layers** -- Extract Spring Boot layered JAR for optimal caching
3. **Runtime** -- Minimal Alpine JRE 17, non-root user (UID 1000), `apk upgrade` for OS-level CVE patches

```bash
# Build standalone
docker build -t payment-api .

# Run with environment variables
docker run -p 8080:8080 \
  -e JWT_SECRET=... \
  -e DB_URL=jdbc:mysql://host:3306/payment_db \
  -e DB_USERNAME=... \
  -e DB_PASSWORD=... \
  payment-api
```

---

## API Reference

Full interactive docs: **`http://localhost:8080/swagger-ui/index.html`**

All endpoints under `/api/v1/payments/**` and `/api/v1/admin/**` require a valid JWT Bearer token.

### Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/login` | None | Obtain a JWT token |
| POST | `/api/v1/auth/logout` | Bearer | Blacklist the current token |
| POST | `/api/v1/auth/change-password` | Bearer | Change the authenticated user's password |
| GET | `/api/v1/auth/me` | Bearer | Get the authenticated user's profile |

**Login:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}'
# -> {"token":"eyJ..."}
```

**Logout:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Authorization: Bearer eyJ..."
# -> 200 OK (token is now blacklisted)
```

### Payments

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/api/v1/payments` | USER, ADMIN | Create a payment (supports `Idempotency-Key` header) |
| GET | `/api/v1/payments` | USER, ADMIN | List payments (paginated + filtered; users see only own) |
| GET | `/api/v1/payments/{id}` | USER, ADMIN | Get payment by ID |
| GET | `/api/v1/payments/{id}/transactions` | USER, ADMIN | List transactions for a payment |
| GET | `/api/v1/payments/source-account?sourceAccount=` | USER, ADMIN | Filter by source account |
| GET | `/api/v1/payments/destination-account?destinationAccount=` | USER, ADMIN | Filter by destination account |
| PATCH | `/api/v1/payments/{id}/status` | USER, ADMIN | Update payment status |
| POST | `/api/v1/payments/{id}/cancel` | USER, ADMIN | Cancel a PENDING payment |
| POST | `/api/v1/payments/{id}/retry` | USER, ADMIN | Retry a FAILED payment (max 3 attempts) |
| POST | `/api/v1/payments/{id}/reversal` | USER, ADMIN | Reverse or partially refund a COMPLETED payment |
| DELETE | `/api/v1/payments/{id}` | USER, ADMIN | Delete a non-completed payment |

**Create payment:**
```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 250.00,
    "currency": "USD",
    "description": "Invoice #INV-2026-001"
  }'
```

**List with filters:**
```
GET /api/v1/payments?status=FAILED&currency=USD&amountFrom=100&amountTo=500&page=0&size=20
```

**Reversal (full):**
```bash
curl -X POST http://localhost:8080/api/v1/payments/{id}/reversal \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json" \
  -d '{"reason": "Customer requested full refund", "partialReversal": false}'
```

**Reversal (partial):**
```bash
curl -X POST http://localhost:8080/api/v1/payments/{id}/reversal \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json" \
  -d '{"reason": "Partial refund for returned item", "partialReversal": true, "reversalAmount": 50.00}'
```

### Admin (ROLE_ADMIN only)

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/admin/stats` | Payment counts by status, today's volume, user count |
| GET | `/api/v1/admin/users` | List all users (paginated) |
| GET | `/api/v1/admin/users/{id}` | Get a user by ID |
| PATCH | `/api/v1/admin/users/{id}/role` | Update a user's role (`ROLE_USER` or `ROLE_ADMIN`) |
| DELETE | `/api/v1/admin/users/{id}` | Delete a user (cannot delete own account) |

### Payment Request Validation

| Field | Constraint |
|---|---|
| `sourceAccount` | Exactly 10 digits, must differ from destination |
| `destinationAccount` | Exactly 10 digits |
| `amount` | 0.01 -- 1,000,000.00, max 4 decimal places |
| `currency` | 3 uppercase letters (ISO 4217) |
| `description` | Optional, max 255 characters |

### Payment Status Flow

```
PENDING -> PROCESSING -> COMPLETED -> REVERSED
                      |            -> REFUNDED
                      -> FAILED (auto-retry up to 3x)
        -> CANCELLED
```

---

## Security

### Authentication flow

1. `POST /api/v1/auth/login` -- returns a signed JWT (HS512, configurable expiry)
2. Include `Authorization: Bearer <token>` on all protected requests
3. `POST /api/v1/auth/logout` -- token is added to the blacklist (Redis-backed; in-memory fallback for local dev)

### Authorization

- **ROLE_USER** -- can create and manage only their own payments
- **ROLE_ADMIN** -- can access all payments plus `/api/v1/admin/**` and all actuator endpoints
- Ownership enforcement (`checkOwnership`) runs on every payment read/write operation

### Security headers

Every response includes:
- `Strict-Transport-Security` (max-age=31536000; includeSubDomains)
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'`

### Rate limiting

Per-client buckets (keyed by `X-Api-Key` header, or TCP remote address as fallback):

| Scope | Limit | Window | Purpose |
|---|---|---|---|
| General API | 100 req | 60s | All `/api/v1/**` routes |
| Login | 10 req | 60s | `/api/v1/auth/login` only -- brute-force protection |

Response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After` (on 429).

### CORS

Configured via `CORS_ALLOWED_ORIGINS` environment variable. Applied to `/api/**` routes only. Pre-flight OPTIONS requests are handled by Spring Security's CorsFilter before the JWT filter.

---

## Configuration Reference

Key properties across profiles:

| Property | Default | Description |
|---|---|---|
| `jwt.secret` | `${JWT_SECRET}` | HS512 key (min 64 bytes) -- no fallback in base profile |
| `jwt.expiration` | 86400000 (24h) | Token validity in ms |
| `cors.allowed-origins` | localhost:3000,8080 | Override via `CORS_ALLOWED_ORIGINS` |
| `rate-limit.limit` | 100 | Requests per window |
| `rate-limit.refreshPeriod` | 60000ms | Window duration |
| `rate-limit.login.limit` | 10 | Login attempts per window |
| `rate-limit.login.refreshPeriod` | 60000ms | Login window duration |
| `scheduler.enabled` | true (false in local) | Enable payment retry + cleanup jobs |
| `scheduler.retry.max-attempts` | 3 | Max retries for FAILED payments (scheduler + manual) |
| `scheduler.cleanup.audit-log-retention-days` | 90 | Days to keep audit logs |
| `scheduler.cleanup.payment-retention-days` | 30 | Days to keep CANCELLED payments |
| `management.tracing.sampling.probability` | 1.0 (0.0 in local) | Zipkin sample rate |

### Profiles

| Profile | Database | Redis | Flyway | Scheduler | Logging |
|---|---|---|---|---|---|
| `local` (default) | H2 in-memory | None (in-memory cache) | Disabled | Disabled | DEBUG, human-readable |
| `test` | H2 in-memory | Mocked | Disabled | Disabled | WARN, plain-text |
| `docker` | MySQL 8.4 | Redis 7.4 | Enabled | Enabled | INFO, structured JSON |

---

## Testing

```bash
# Run all tests with coverage enforcement
mvn verify

# Run tests only (skip coverage gate)
mvn test

# Run a single test class
mvn test -Dtest=PaymentServiceTest

# View coverage report
open target/site/jacoco/index.html
```

**371 tests** across 21 test classes:
- **Unit tests** -- service layer, DTOs, validators, security filter, metrics
- **Controller tests** -- `@WebMvcTest` slices with MockMvc (auth, payments, admin)
- **Integration tests** -- full Spring context with H2 (`@SpringBootTest`)
- **End-to-end tests** -- HTTP round-trips via `TestRestTemplate`

Coverage gate: **75% line coverage** enforced by JaCoCo on `mvn verify`. Current coverage: ~82%.

---

## CI/CD

### CI Pipeline (`ci.yml`)

Triggered on every push and pull request. Runs 5 jobs:

| Job | Purpose | Gate |
|---|---|---|
| **Build, Test & Coverage** | `mvn verify`, JaCoCo report, JUnit check run | 75% line coverage |
| **CodeQL SAST** | Static analysis with security-extended queries | Blocks on findings |
| **Trivy Filesystem & Secrets** | Hardcoded secret scan + CVE/misconfig scan | Blocks on secrets |
| **Docker Build & Trivy Scan** | Build image, scan for CRITICAL/HIGH CVEs (fixable) | Blocks on findings |
| **OWASP Dependency Check** | NVD-based dependency CVE scan | Blocks on CVSS >= 7.0 |

### CD Pipeline (`cd.yml`)

Triggered on push to `master`:

| Job | Purpose |
|---|---|
| **Publish Image** | Build and push to GHCR with SHA, `latest`, and `build-N` tags |
| **Deploy to Staging** | Helm upgrade with smoke test (health endpoint verification) |
| **Deploy to Production** | Manual trigger only, requires staging success + reviewer approval |

### Security Scan (`security.yml`)

Scheduled weekly (Monday 03:00 UTC):
- Full OWASP Dependency-Check (all severities)
- Trivy container scan on latest published image
- Trivy repository scan (vuln + secrets + misconfig)

### Supply Chain Security

- All GitHub Actions SHA-pinned (14 unique actions across 5 workflows)
- `persist-credentials: false` on all checkout steps
- GitHub expression injection mitigated (expressions in `env:` blocks, not `run:` steps)

---

## Kubernetes / Helm

A Helm chart is provided in `helm/payment-api/`.

### Deploy to a cluster

```bash
# Staging
helm upgrade --install payment-api ./helm/payment-api \
  -f helm/payment-api/values-staging.yaml \
  --set image.tag=sha-abc1234 \
  --namespace payment-staging \
  --create-namespace \
  --atomic --wait --timeout 5m

# Production
helm upgrade --install payment-api ./helm/payment-api \
  -f helm/payment-api/values-prod.yaml \
  --set image.tag=sha-abc1234 \
  --namespace payment-prod \
  --create-namespace \
  --atomic --wait --timeout 5m
```

### Required secrets

```bash
# GHCR pull secret (for private images)
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<github-user> \
  --docker-password=<github-pat> \
  --namespace=<namespace>
```

### Health probes

The Helm chart configures Kubernetes liveness and readiness probes against Spring Boot Actuator:
- **Liveness**: `/actuator/health/liveness`
- **Readiness**: `/actuator/health/readiness`

---

## Observability

### Metrics (Prometheus)

Prometheus scrapes `http://localhost:8080/actuator/prometheus`.

Custom application metrics:

| Metric | Type | Description |
|---|---|---|
| `payment.created.total` | Counter | Payments created |
| `payment.completed.total` | Counter | Payments completed successfully |
| `payment.failed.total` | Counter | Payments that failed |
| `payment.cancelled.total` | Counter | Payments cancelled |
| `payment.retried.total` | Counter | Retry attempts |
| `payment.retried.success.total` | Counter | Successful retries |
| `payment.reversed.total` | Counter | Full reversals |
| `payment.refunded.total` | Counter | Partial refunds |
| `payment.processing.duration` | Timer | End-to-end processing latency (p50/p95/p99) |

Plus standard Spring Boot metrics: HikariCP pool, JVM heap, HTTP server requests, circuit breaker state.

### Grafana

Dashboard auto-provisioned at `http://localhost:3000` (docker-compose only).

Panels: payment rates, latency percentiles, HTTP error rate, HikariCP pool utilisation, JVM heap.

### Distributed Tracing (Zipkin)

```
http://localhost:9411
```

Every request carries `traceId` + `spanId` in log output and a `correlationId` via the `X-Correlation-ID` header. Sampling rate is 100% in dev/staging -- set `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1` for high-traffic production.

### Structured Logging

In `docker`/production profiles, all log lines are emitted as JSON (Logstash-compatible) with fields:
```json
{
  "service": "payment-api",
  "environment": "docker",
  "traceId": "abc123",
  "spanId": "def456",
  "correlationId": "req-789",
  "level": "INFO",
  "logger_name": "c.e.p.service.PaymentServiceImpl",
  "message": "Payment pay-001 completed successfully"
}
```

In the `local` profile, logs are plain-text at DEBUG level for easy reading.

### Health Endpoint

```
GET /actuator/health
```
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "circuitBreakers": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "livenessState": { "status": "UP" },
    "readinessState": { "status": "UP" }
  }
}
```

---

## Database Migrations

Flyway manages the schema (`src/main/resources/db/migration/`):

| Version | Description |
|---|---|
| V1 | Initial schema -- `payments`, `transactions`, `audit_logs`, `users` |
| V2 | Indexes and constraints |
| V3 | `retry_count` column on `payments` |
| V4 | `created_at` index on `payments` |
| V5 | `created_by` column + index for BOLA ownership enforcement |
| V6 | `description` column on `payments` |
| V7 | `performed_by` column on `audit_logs` for actor tracking |
| V8 | `created_at` and `updated_at` timestamps on `transactions` |

Flyway runs automatically on startup in the `docker` profile. The `local` and `test` profiles use Hibernate `ddl-auto=create-drop` with H2 instead.

---

## Project Structure

```
payment-api/
  src/main/java/com/example/paymentapi/
    config/          # SecurityConfig, WebMvcConfig, RateLimitInterceptor, AccessLogFilter
    controller/      # AuthController, PaymentController, AdminController
    dto/             # Request/response DTOs with validation annotations
    exception/       # Custom exceptions + global PaymentExceptionHandler
    metrics/         # PaymentMetrics (Micrometer counters and timers)
    model/           # JPA entities (Payment, Transaction, User, AuditLog)
    repository/      # Spring Data JPA repositories + specifications
    security/        # JwtTokenProvider, JwtTokenFilter
    service/         # Business logic (payment, transaction, audit, scheduler, etc.)
  src/main/resources/
    application.properties           # Base config (all profiles)
    application-local.properties     # H2, no Redis, debug logging
    application-docker.properties    # MySQL, Redis, JSON logging
    application-test.properties      # Test-specific overrides
    db/migration/                    # Flyway SQL migrations (V1-V8)
    logback-spring.xml               # Structured logging config
  src/test/                          # 371 tests (unit, controller, integration, E2E)
  .github/workflows/                 # CI, CD, security scan, Claude Code workflows
  helm/payment-api/                  # Helm chart for Kubernetes deployment
  Dockerfile                         # Multi-stage layered JAR build
  docker-compose.yml                 # Full local stack (app, MySQL, Redis, Prometheus, Grafana, Zipkin)
  pom.xml                            # Maven build with JaCoCo 75% coverage gate
```
