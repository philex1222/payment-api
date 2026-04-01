---
name: security-review
description: Run a comprehensive security audit of the payment-api codebase covering auth, input validation, CORS, secrets, dependencies, and OWASP Top 10. Optionally scope to a specific file or area.
argument-hint: [file-or-area (optional)]
allowed-tools: Read, Grep, Glob, Bash
---

# Security Review — payment-api

You are conducting a security audit of this Spring Boot payment microservice. The scope is: **$ARGUMENTS** (if blank, audit the entire codebase).

Work through every section below. For each finding, rate severity as **CRITICAL / HIGH / MEDIUM / LOW / INFO** and provide a concrete fix.

---

## 1. Authentication & JWT

- Verify `JwtTokenProvider`: algorithm is RS256, HS256, or HS512 with a strong secret (≥256-bit; HS512 requires a 64-byte key via `Keys.hmacShaKeyFor`), expiry is reasonable, claims are validated (iss, aud if set, exp, nbf)
- Check `JwtTokenFilter`: token extraction, null handling, exception safety
- Confirm `/api/v1/auth/**` is `permitAll()` and all other protected routes require a valid token
- Verify `eraseCredentialsAfterAuthentication` doesn't break cached `UserDetails`

## 2. Authorization (RBAC)

- Audit `SecurityConfig.filterChain()`: every route pattern has an explicit rule; no catch-all `permitAll()`
- Check `@PreAuthorize` / `@Secured` annotations on controllers are consistent with `SecurityConfig` rules
- Confirm admin endpoints (`/api/v1/admin/**`) require `ROLE_ADMIN`; note that `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, and `/actuator/metrics` are intentionally public — only the remaining `/actuator/**` catch-all should require `ROLE_ADMIN`
- Look for privilege escalation paths: can a `ROLE_USER` reach admin functionality?

## 3. Input Validation

- Check all `@RequestBody` DTOs have `@Valid` and meaningful `@NotNull`/`@Size`/`@Pattern` constraints
- Review `PaymentRequest`: amount (positive, max), currency (ISO 4217), account numbers (regex)
- Look for direct object references — are payment IDs validated to belong to the authenticated user?
- Check for mass-assignment vulnerabilities in DTOs exposed to update endpoints

## 4. Injection & Data Safety

- Grep for raw JPQL/SQL string concatenation — all queries must use named parameters or Spring Data derived queries
- Check for command injection in any `Runtime.exec()` or `ProcessBuilder` usage
- Verify Flyway migration scripts don't contain hardcoded secrets or insecure grants

## 5. CORS & Security Headers

- Review `CorsConfigurationSource`: `allowedOriginPatterns` should not be `*` when `allowCredentials=true`
- Confirm CSP, HSTS, X-Frame-Options, Referrer-Policy headers are set in `SecurityConfig.headers()`
- Check that pre-flight OPTIONS requests are handled correctly (no 401/403)

## 6. Secrets & Environment

- Search for hardcoded credentials, API keys, or JWT secrets in `application.properties`, `docker-compose.yml`, or source files
- Verify `.env` is in `.gitignore`
- Confirm all secrets are injected via environment variables (`${ENV_VAR}`) with no fallback plaintext defaults

## 7. Sensitive Data Logging

- Check `PaymentServiceImpl` and filters for logging of account numbers, amounts, or JWT tokens
- Confirm PII is masked (e.g., last-4 of account, no full card numbers)
- Verify `AccessLogFilter` does not log request bodies containing credentials

## 8. Error Handling & Information Leakage

- Confirm `PaymentExceptionHandler` never exposes stack traces, internal class names, or DB schema details in API responses
- Check that 500-level errors return a generic message with only a trace ID
- Verify Spring Boot's default error page (`/error`) is not leaking information

## 9. Rate Limiting & Abuse Prevention

- Confirm `RateLimitInterceptor` is applied to `/api/v1/payments/**` (not the old `/api/payments/**` path)
- Check idempotency key implementation — can it be abused to replay or suppress payments?
- Look for missing rate limits on `/api/v1/auth/login` (brute-force risk)

## 10. Dependency Vulnerabilities

Run `mvn dependency:tree` mentally and flag any known-vulnerable library patterns:
- Check Spring Boot version for known CVEs (target: 3.4.x is current)
- Check if `spring-security` version has patches available
- Flag any transitive dependencies pulling in old Jackson, Netty, or Bouncy Castle versions

---

## Output Format

For each finding:

```
[SEVERITY] Category — Short title
File: path/to/file.java:line
Issue: What the problem is
Risk: What an attacker could do
Fix: Exact code or config change needed
```

End with a **Summary table** of all findings grouped by severity, and an overall security posture score (1–10).
