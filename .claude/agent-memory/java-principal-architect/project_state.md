---
name: payment-api project state
description: Spring Boot 3.4.3 stack, architectural decisions, completed tiers, test count, key constraints
type: project
---

## Stack
- Java 17, Spring Boot 3.4.3, MySQL 8 (H2 for tests), Redis, Flyway
- JWT HS512 (jjwt 0.13.0), BCrypt, SpringDoc 2.8.6
- Resilience4j 2.4.0 (circuitbreaker + retry + ratelimiter)
- Micrometer Tracing + Brave bridge + Zipkin reporter
- Prometheus + Grafana observability stack (Prometheus on host port 19090)
- Layered Docker image, Helm chart with HPA, full GitHub Actions CI pipeline

## Test count
- 297 tests passing as of 2026-03-29 (was 255 before Tier 4)
- JaCoCo minimum 70% line coverage enforced in CI

## Flyway migrations
- V1: init schema (users, payments, transactions, audit_logs)
- V2: column width fixes, FK constraints, idx_transactions_payment_id, idx_audit_logs_payment_id, idx_payments_status
- V3: add payments.retry_count INT NOT NULL DEFAULT 0
- V4: add idx_payments_created_at, composite idx_payments_status_created_at, idx_payments_source_account, idx_payments_destination_account

## Key architectural decisions
- All JPA entities use @Getter/@Setter + manual equals/hashCode on ID — NOT @Data (Pillar 3)
- AuditServiceImpl runs in @Transactional(propagation=REQUIRES_NEW) so audit failures never roll back payment ops
- PaymentExceptionHandler pulls traceId from MDC (Micrometer/Brave) not UUID.randomUUID()
- RateLimitInterceptor uses RemoteAddr only — X-Forwarded-For NOT trusted (IP spoofing risk)
- JwtTokenFilter catches exceptions from getAuthentication() and clears SecurityContext rather than propagating
- BankingAPIServiceImpl: no synchronized block; all three public methods have @CircuitBreaker + @Retry
- NotificationService in-memory store is capped at 1000 entries (LRU evict oldest)
- Dockerfile is 3-stage: build → layer extraction → runtime; layered JarLauncher entrypoint

## Constraints
- Do not change Prometheus host port (19090) or app port (8080) in docker-compose
- Do not change /api/v1/** URL paths
- SecurityConfig already permits /actuator/prometheus and /actuator/metrics without auth
- PaymentServiceTest uses lenient().when(paymentMetrics.startTimer()).thenReturn(Timer.start())
- Java 17 (not 21) — no virtual thread or structured concurrency APIs

## Completed tiers
- Tier 1: JSON logging, pagination+filtering, payment cancellation, scheduled retry
- Tier 2: API versioning (/api/v1/), HikariCP tuning, Prometheus+Grafana
- Tier 3: PaymentMetrics (Micrometer), AsyncConfig, AccessLogFilter, Grafana dashboard
- Tier 4 (2026-03-29): entity @Data removal, @Pattern validation, constructor injection, JWT filter hardening, synchronized removal, audit REQUIRES_NEW, MDC traceId, bounded notification store, Flyway V4 indexes, layered Dockerfile, 42 new tests

**Why:** Production-readiness audit across all 10 engineering pillars; designed for high-throughput payment processing with correctness guarantees.
**How to apply:** When adding new entities, always follow the @Getter/@Setter + ID-based equals/hashCode pattern. When adding new exception types, register them in PaymentExceptionHandler. New external calls must have @CircuitBreaker + @Retry + fallbackMethod.
