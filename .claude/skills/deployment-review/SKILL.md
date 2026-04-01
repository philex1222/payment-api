---
name: deployment-review
description: Review deployment readiness of the payment-api — Docker, docker-compose, Flyway migrations, environment config, health checks, resource limits, Prometheus/Grafana observability, and production hardening checklist.
argument-hint: [environment (optional, e.g. staging / prod)]
allowed-tools: Read, Grep, Glob, Bash
---

# Deployment Review — payment-api

You are reviewing deployment readiness for the **$ARGUMENTS** environment (default: production if blank).

Work through every section. For each item, mark it **PASS**, **WARN**, or **FAIL** with a short rationale and, where applicable, the exact fix.

---

## 1. Docker Image

- Read `Dockerfile`: confirm it uses a multi-stage / layered build (`jarmode=layertools`) so only the `application` layer is invalidated on code changes
- Base image: is it a minimal JRE image (e.g., `eclipse-temurin:21-jre-alpine`)? No full JDK in production
- Non-root user: confirm the container does NOT run as root (`USER` directive set)
- `.dockerignore`: verify `target/`, `.env`, `.git`, `*.md` are excluded
- No secrets baked into the image (ARG/ENV in Dockerfile vs. runtime env)

## 2. docker-compose.yml

- All services have explicit `restart` policies (`unless-stopped` or `on-failure`)
- Resource limits (`deploy.resources.limits.memory`, `cpus`) set for app, MySQL, Redis
- Health checks defined for: `mysql` (mysqladmin ping), `redis` (redis-cli ping), `app` (actuator/health)
- `app` service depends_on `mysql` and `redis` with `condition: service_healthy`
- No hardcoded passwords in `docker-compose.yml` — all secrets from `.env` file
- Prometheus port `19090` (Windows Hyper-V safe) vs `9090` — confirm if deploying to Linux
- Network isolation: services use a custom bridge network, not `network_mode: host`

## 3. Environment & Secrets

- `.env.example` exists with placeholder values documenting all required variables
- Required variables: `JWT_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD`, `CORS_ALLOWED_ORIGINS`
- `JWT_SECRET` entropy: must be ≥64 hex chars (256-bit)
- No Spring datasource password fallback in `application.properties` (`${DB_PASSWORD}` with no default)
- Production profile (`spring.profiles.active=prod`) disables H2 console and SQL logging

## 4. Database & Flyway Migrations

- All Flyway scripts in `src/main/resources/db/migration/` are versioned (V1, V2, ...) with no gaps
- No `DROP TABLE` or destructive DDL in non-rollback scripts
- Indexes on high-traffic columns: `payments.status`, `payments.source_account`, `payments.destination_account`, `payments.created_at`
- `retry_count` column present (V3) and `created_at`/source/destination indexes (V4)
- `flyway.baseline-on-migrate` configured for existing databases
- Connection pool: HikariCP `minimum-idle=5`, `maximum-pool-size=20`, `auto-commit=false`

## 5. Health Checks & Actuator

- `/actuator/health` returns UP with all sub-indicators (db, redis, diskSpace, circuitBreaker)
- `/actuator/health/liveness` and `/actuator/health/readiness` exposed for Kubernetes if applicable
- `PaymentCircuitBreakerHealthIndicator` registered and visible in health endpoint
- Actuator endpoints secured: only `health`, `info`, `prometheus`, `metrics` accessible without `ROLE_ADMIN`
- `management.endpoints.web.exposure.include` does NOT expose `env`, `beans`, `heapdump` publicly

## 6. Observability

- Prometheus scrape target `http://app:8080/actuator/prometheus` reachable from Prometheus container
- Grafana dashboard JSON auto-provisioned from `docker/grafana/dashboards/`
- Dashboard covers: payment created/completed/failed rates, p50/p95/p99 latency, HTTP error rate, HikariCP pool usage, JVM heap
- Zipkin distributed tracing: `http://zipkin:9411` reachable, `management.zipkin.tracing.endpoint` configured (Spring Boot 3 property — not the old Sleuth `spring.zipkin.base-url`)
- Access log filter active: method, URI, status, duration logged for every non-health request
- Log format is structured (JSON or logfmt) for log aggregation in production

## 7. Resilience

- Resilience4j circuit breaker configured for `bankingApi`: `slidingWindowSize`, `failureRateThreshold`, `waitDurationInOpenState`
- Retry policy: max 3 attempts with exponential backoff, retries on `ConnectException` / `TimeoutException`
- `@TimeLimiter` timeout ≤ 5s on external banking API calls
- Async thread pool bounded: `ThreadPoolTaskExecutor` max=10, queue=100, `CallerRunsPolicy` (no silent task drops)
- Cache TTLs: `payments`=10min, `users`=60min; cache eviction on user update

## 8. JVM & Performance Tuning

- Tomcat thread pool: `server.tomcat.threads.max` set (default 200 may be too high for containers)
- JVM heap: `-Xms` / `-Xmx` set in `JAVA_OPTS` environment variable (e.g., `512m`/`1g` for a 1.5GB container)
- G1GC or ZGC selected explicitly for low-latency payment processing
- `spring.jpa.open-in-view=false` to prevent lazy-load N+1 across HTTP thread

## 9. Security Pre-flight

- HTTPS enforced in production (reverse proxy or `server.ssl.*` config)
- CORS `allowedOriginPatterns` does not include `*` with `allowCredentials=true`
- Rate limiter active on payment creation endpoints
- No `spring.h2.console.enabled=true` in production profile
- `spring.mvc.throw-exception-if-no-handler-found=true` and `spring.web.resources.add-mappings=false` set (404 for unmapped routes)

## 10. Runbook Checklist (pre-deploy)

Walk through each step and verify it can be completed:

```
[ ] docker-compose pull  (verify all image tags are pinned, not :latest)
[ ] docker-compose up -d mysql redis  (wait for healthy)
[ ] Flyway migrations run cleanly on target DB (dry-run or validate)
[ ] docker-compose up -d app  (watch logs for startup errors)
[ ] curl http://localhost:8080/actuator/health  (verify UP)
[ ] curl http://localhost:8080/api/v1/auth/login  (verify auth endpoint live)
[ ] Prometheus target http://app:8080/actuator/prometheus shows health: up
[ ] Grafana dashboard loads and shows data
[ ] Send a test payment via Swagger UI or curl
[ ] Verify Zipkin trace appears for the test payment
```

---

## Output Format

For each section item:

```
[PASS/WARN/FAIL] Item title
File: path/to/file (if applicable)
Detail: What was found
Action: What needs to change before deploying (WARN/FAIL only)
```

End with a **Deployment Readiness Score** (percentage of items passing) and a prioritized list of blockers that must be resolved before going to production.
