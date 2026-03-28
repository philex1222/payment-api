---
name: payment-api external service dependencies
description: MySQL and Redis connection details, JVM sizing rationale, and Spring Boot actuator probe paths
type: project
---

The app depends on MySQL 8.0 (datasource) and Redis 7 (session/cache) — both referenced via env vars injected from Secrets and ConfigMaps.

**Spring Boot Actuator probe paths:**
- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`
- Prometheus metrics: `/actuator/prometheus`
- Actuator port: 8080 (same as app port)

The `application.properties` already exposes `health,info,prometheus,metrics` endpoints.
Liveness/readiness state enablement (`management.health.livenessState.enabled=true`) must be confirmed in the active profile — if probes return 404, this is the first thing to check.

**JVM sizing:** Container limit 1Gi (staging), 1.5Gi (prod). JAVA_OPTS uses `-XX:MaxRAMPercentage=75` so heap = ~768Mi / ~1152Mi respectively. Off-heap (Metaspace, threads, code cache) consumes the remaining ~25%. Rule applied: `containerLimit >= maxHeap * 1.33`.

**Why:** Docker Compose used `-Xms256m -Xmx512m` fixed flags. In K8s the container limit varies per environment so percentage-based sizing was adopted to avoid OOMKilled across envs.

**How to apply:** When diagnosing OOMKilled pods, verify the limit vs MaxRAMPercentage ratio first. When the actuator probe path is in question, default to `/actuator/health/liveness` and `/actuator/health/readiness`.
