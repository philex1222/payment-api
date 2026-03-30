# Payment API

A production-grade RESTful microservice for payment processing built with Spring Boot 3. Supports the full payment lifecycle — create, track, reverse, refund, and cancel — with stateless JWT authentication, per-user ownership enforcement, structured JSON logging, Prometheus metrics, distributed tracing, and Resilience4j circuit breaking.

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
9. [Observability](#observability)
10. [Database Migrations](#database-migrations)

---

## Features

- **Payment lifecycle** — PENDING → PROCESSING → COMPLETED / FAILED / CANCELLED / REVERSED / REFUNDED
- **Multi-currency** — ISO 4217 currency codes with automatic conversion
- **Idempotency** — `Idempotency-Key` header prevents duplicate charges on network retries (Redis-backed, 24h TTL)
- **BOLA protection** — users can only read/modify their own payments; ROLE_ADMIN bypasses all ownership checks
- **JWT authentication** — HS512, 24h expiry, issuer-validated; logout invalidates tokens via blacklist
- **Rate limiting** — per-client buckets (Resilience4j + Caffeine, max 10k clients) on all `/api/v1/**` routes
- **Circuit breaker** — Resilience4j on the downstream banking API with retry + time limiter
- **Scheduled jobs** — automatic retry of FAILED payments; cleanup of stale records
- **Structured JSON logging** — Logstash encoder for ELK / Grafana Loki; human-readable output in local/test profiles
- **Prometheus metrics** — payment counters, latency histograms, HikariCP pool, JVM heap
- **Grafana dashboard** — pre-built dashboard provisioned automatically via docker-compose
- **Distributed tracing** — Micrometer Tracing + Zipkin
- **OpenAPI docs** — Swagger UI at `/swagger-ui/index.html`
- **70% line coverage enforced** — JaCoCo gate blocks broken builds

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.4.3 |
| Security | Spring Security 6, jjwt 0.13.0 (HS512) |
| Persistence | Spring Data JPA, Hibernate, MySQL 8 (prod), H2 (local/test) |
| Migrations | Flyway (V1–V5) |
| Cache / Idempotency | Redis (docker), Simple in-memory (local) |
| Resilience | Resilience4j 2.4.0 — circuit breaker, retry, rate limiter, time limiter |
| Metrics | Micrometer + Prometheus + Grafana |
| Tracing | Micrometer Tracing + Zipkin |
| Logging | Logback + Logstash encoder |
| Docs | SpringDoc OpenAPI 2.8.6 |
| Build | Maven, JaCoCo 0.8.14 |
| Container | Docker, docker-compose |

---

## Quick Start — Local Dev

No external services required. The `local` profile uses H2 in-memory database and simple in-memory cache.

### Prerequisites

- Java 17+
- Maven 3.8+

### Run

```bash
# Clone and build
git clone <repo-url>
cd payment-api

# Start with local profile (H2, no Redis, no Zipkin, schedulers disabled)
mvn spring-boot:run
```

The `local` profile activates automatically for `mvn spring-boot:run`, `java -jar`, and IDE run configurations.

### Verify

```
GET  http://localhost:8080/actuator/health   → { "status": "UP" }
GET  http://localhost:8080/swagger-ui/index.html
GET  http://localhost:8080/h2-console        (JDBC URL: jdbc:h2:mem:localdb)
```

### Default users (seeded by `DataInitializer`)

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | ROLE_ADMIN |
| `user` | `user123` | ROLE_USER |

---

## Docker Deployment

### Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin)
- Copy `.env.example` to `.env` and fill in all values

```bash
cp .env.example .env
# Edit .env — set strong values for JWT_SECRET, DB_PASSWORD, REDIS_PASSWORD, etc.
```

### Start the full stack

```bash
docker-compose up -d
```

This starts: **app** (port 8080), **MySQL** (3306), **Redis** (6379), **Prometheus** (19090), **Grafana** (3000), **Zipkin** (9411).

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
| `JWT_SECRET` | HS512 signing key — min 64 hex chars (`openssl rand -hex 64`) | Yes |
| `DB_USERNAME` | MySQL application user | Yes |
| `DB_PASSWORD` | MySQL application password | Yes |
| `MYSQL_ROOT_PASSWORD` | MySQL root password | Yes |
| `REDIS_PASSWORD` | Redis AUTH password | Yes |
| `GF_ADMIN_PASSWORD` | Grafana admin password | Yes |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins, e.g. `https://app.example.com` | Yes |

---

## API Reference

Full interactive docs: **`http://localhost:8080/swagger-ui/index.html`**

All endpoints under `/api/v1/payments/**` and `/api/v1/admin/**` require a valid JWT Bearer token.

### Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/login` | None | Obtain a JWT token |
| POST | `/api/v1/auth/logout` | Bearer | Blacklist the current token |

**Login example:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"user123"}'
# → {"token":"eyJ..."}
```

**Logout example:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Authorization: Bearer eyJ..."
# → 200 OK (token is now blacklisted)
```

### Payments

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/api/v1/payments` | USER, ADMIN | Create a payment (supports `Idempotency-Key` header) |
| GET | `/api/v1/payments` | USER, ADMIN | List payments (paginated; users see only their own) |
| GET | `/api/v1/payments/{id}` | USER, ADMIN | Get payment by ID |
| GET | `/api/v1/payments/source-account?sourceAccount=` | USER, ADMIN | Filter by source account |
| GET | `/api/v1/payments/destination-account?destinationAccount=` | USER, ADMIN | Filter by destination account |
| PATCH | `/api/v1/payments/{id}/status` | USER, ADMIN | Update status |
| POST | `/api/v1/payments/{id}/cancel` | USER, ADMIN | Cancel a PENDING payment |
| POST | `/api/v1/payments/{id}/reversal` | USER, ADMIN | Reverse or partially refund a COMPLETED payment |
| DELETE | `/api/v1/payments/{id}` | USER, ADMIN | Delete a non-completed payment |

**Create payment example:**
```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "sourceAccount": "1234567890",
    "destinationAccount": "0987654321",
    "amount": 250.00,
    "currency": "USD"
  }'
```

**Payment list filters:**
```
GET /api/v1/payments?status=FAILED&dateFrom=2026-01-01T00:00:00&page=0&size=20
```

### Admin (ROLE_ADMIN only)

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/admin/stats` | Payment counts and today's total volume |
| GET | `/api/v1/admin/users` | List all users |
| PATCH | `/api/v1/admin/users/{username}/role` | Update a user's role |

### Payment Request Validation

| Field | Constraint |
|---|---|
| `sourceAccount` | Exactly 10 digits |
| `destinationAccount` | Exactly 10 digits, must differ from source |
| `amount` | 0.01–1,000,000.00, max 4 decimal places |
| `currency` | 3 uppercase letters (ISO 4217) |

---

## Security

### Authentication flow

1. `POST /api/v1/auth/login` → returns a signed JWT (HS512, 24h expiry)
2. Include `Authorization: Bearer <token>` on all protected requests
3. `POST /api/v1/auth/logout` → token is added to the blacklist (Redis-backed; in-memory fallback for local dev)

### Authorization

- **ROLE_USER** — can create and manage only their own payments
- **ROLE_ADMIN** — can access all payments plus `/api/v1/admin/**` and all actuator endpoints

### Security headers

Every response includes:
- `Strict-Transport-Security` (max-age=31536000; includeSubDomains)
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'`

### Rate limiting

Per-client buckets (keyed by `X-Api-Key` header, or TCP remote address as fallback):
- 100 requests per 60-second window (configurable via `rate-limit.*` properties)
- Applied to `/api/v1/payments/**`, `/api/v1/auth/**`, `/api/v1/admin/**`
- Response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

---

## Configuration Reference

Key properties across profiles:

| Property | Default | Description |
|---|---|---|
| `jwt.secret` | dev fallback | HS512 key — override via `JWT_SECRET` env var in production |
| `jwt.expiration` | 86400000 (24h) | Token validity in ms |
| `cors.allowed-origins` | localhost:3000,8080 | Override via `CORS_ALLOWED_ORIGINS` |
| `rate-limit.limit` | 100 | Requests per window |
| `rate-limit.refreshPeriod` | 60000ms | Window duration |
| `scheduler.enabled` | true (false in local) | Enable payment retry + cleanup jobs |
| `scheduler.retry.max-attempts` | 3 | Max automatic retries for FAILED payments |
| `scheduler.cleanup.audit-log-retention-days` | 90 | Days to keep audit logs |
| `management.tracing.sampling.probability` | 1.0 (0.0 in local) | Zipkin sample rate |

### Profiles

| Profile | Database | Redis | Flyway | Scheduler | Logging |
|---|---|---|---|---|---|
| `local` (default) | H2 in-memory | None (in-memory cache) | Disabled | Disabled | DEBUG, human-readable |
| `test` | H2 in-memory | Mocked | Disabled | Disabled | WARN, plain-text |
| `docker` | MySQL | Redis | Enabled | Enabled | INFO, JSON |

---

## Testing

```bash
# Run all tests with coverage report
mvn verify

# Run tests only (skip coverage gate)
mvn test

# View coverage report
open target/site/jacoco/index.html
```

Test suite (~316 tests):
- Unit tests — service layer, DTOs, validators, security filter
- Controller tests — `@WebMvcTest` slices with MockMvc
- Integration tests — full Spring context with H2 (`@SpringBootTest`)
- End-to-end tests — HTTP round-trips via `TestRestTemplate`

Coverage gate: **70% line coverage** enforced by JaCoCo on `mvn verify`.

---

## Observability

### Metrics (Prometheus)

Prometheus scrapes `http://localhost:8080/actuator/prometheus`.

Custom application metrics:
- `payment.created.total` — counter
- `payment.completed.total` — counter
- `payment.failed.total` — counter
- `payment.cancelled.total` — counter
- `payment.retried.total` / `payment.retried.success.total`
- `payment.processing.duration` — timer (p50/p95/p99 latency)

Plus standard Spring Boot metrics: HikariCP pool, JVM heap, HTTP server requests.

### Grafana

Dashboard auto-provisioned at `http://localhost:3000` (docker-compose only).

Panels: payment rates, latency percentiles, HTTP error rate, HikariCP pool utilisation, JVM heap.

### Distributed Tracing (Zipkin)

```
http://localhost:9411
```

Every request carries `traceId` + `spanId` in response logs and in structured JSON log output. Sampling rate is 100% in dev/staging (`management.tracing.sampling.probability=1.0`) — lower to `0.1` for high-traffic production.

### Structured Logging

In `docker`/production profiles, all log lines are emitted as JSON (Logstash-compatible) with fields:
```json
{
  "service": "payment-api",
  "environment": "docker",
  "traceId": "...",
  "spanId": "...",
  "correlationId": "...",
  "level": "INFO",
  "logger_name": "...",
  "message": "..."
}
```

In the `local` profile, logs are plain-text at DEBUG level for easy reading.

### Health endpoint

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
    "diskSpace": { "status": "UP" }
  }
}
```

---

## Database Migrations

Flyway manages the schema (`src/main/resources/db/migration/`):

| Version | Description |
|---|---|
| V1 | Initial schema — `payments`, `transactions`, `audit_logs`, `users` |
| V2 | Indexes and constraints |
| V3 | `retry_count` column on `payments` |
| V4 | `created_at` index on `payments` |
| V5 | `created_by` column + index for BOLA ownership enforcement |

Flyway runs automatically on startup in the `docker` profile. Set `spring.flyway.baseline-on-migrate=true` when applying to a pre-existing database.
