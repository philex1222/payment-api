# Webhook Subscriptions — Design Spec
**Date:** 2026-04-09  
**Status:** Approved  
**Feature:** Configurable push-notification webhooks for payment events

---

## Overview

Allow authenticated users to register HTTP callback URLs (webhooks) that receive push notifications when their payments change state. Admins can additionally register system-wide webhooks that fire for all users' payment events. Each subscription specifies which event types it cares about. Deliveries are persisted in the database and retried with exponential backoff.

---

## 1. Data Model

### V10 — `webhook_subscriptions`

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID |
| `user_id` | BIGINT NOT NULL FK → users | Subscription owner |
| `target_url` | VARCHAR(512) NOT NULL | HTTPS endpoint to POST to |
| `bearer_token` | VARCHAR(512) NOT NULL | Sent as `Authorization: Bearer <token>` on every delivery |
| `event_types` | VARCHAR(512) NOT NULL | Comma-separated list of subscribed event type names |
| `admin_scope` | BOOLEAN NOT NULL DEFAULT FALSE | If TRUE, fires for all users' payments (ADMIN role required) |
| `active` | BOOLEAN NOT NULL DEFAULT TRUE | Soft-disable without deletion |
| `created_at` | DATETIME(6) NOT NULL | |

**Constraints:**
- `admin_scope = TRUE` is only valid for users with ADMIN role — enforced at service layer (403 otherwise)
- `event_types` must contain at least one valid event type — enforced via `@NotEmpty` validation

### V11 — `webhook_deliveries`

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID |
| `subscription_id` | VARCHAR(36) NOT NULL FK → webhook_subscriptions | |
| `payment_id` | VARCHAR(36) NOT NULL | Payment that triggered the event |
| `event_type` | VARCHAR(50) NOT NULL | e.g. `PAYMENT_COMPLETED` |
| `payload` | TEXT NOT NULL | JSON snapshot of PaymentResponse at event time |
| `status` | VARCHAR(20) NOT NULL | `PENDING` / `DELIVERED` / `FAILED` |
| `attempt_count` | INT NOT NULL DEFAULT 0 | Incremented on each delivery attempt |
| `last_attempt_at` | DATETIME(6) NULL | Timestamp of most recent attempt |
| `next_retry_at` | DATETIME(6) NOT NULL | Scheduler polls where `next_retry_at <= now` |
| `response_status` | INT NULL | HTTP status code from last delivery attempt |
| `created_at` | DATETIME(6) NOT NULL | |

**Indexes:** `(status, next_retry_at)` composite index for the scheduler's polling query.

---

## 2. Event Types

Valid event type strings (stored in `event_types` column and in `webhook_deliveries.event_type`):

| Constant | Triggered when |
|---|---|
| `PAYMENT_CREATED` | `createPayment()` succeeds |
| `PAYMENT_COMPLETED` | Status transitions to `COMPLETED` |
| `PAYMENT_FAILED` | Status transitions to `FAILED` |
| `PAYMENT_CANCELLED` | `cancelPayment()` succeeds |
| `PAYMENT_REVERSED` | Status transitions to `REVERSED` |
| `PAYMENT_REFUNDED` | Status transitions to `REFUNDED` |
| `PAYMENT_STATUS_CHANGED` | Any status transition (catch-all) |

---

## 3. REST API

**Base path:** `/api/v1/webhooks`  
**Auth:** All endpoints require JWT. Ownership enforced at service layer.

| Method | Path | Roles | Description |
|---|---|---|---|
| `POST` | `/api/v1/webhooks` | USER, ADMIN | Register a new subscription |
| `GET` | `/api/v1/webhooks` | USER, ADMIN | List caller's subscriptions (admins see all) |
| `GET` | `/api/v1/webhooks/{id}` | USER, ADMIN | Get subscription by ID (ownership enforced) |
| `PATCH` | `/api/v1/webhooks/{id}` | USER, ADMIN | Update targetUrl, bearerToken, eventTypes, or active flag |
| `DELETE` | `/api/v1/webhooks/{id}` | USER, ADMIN | Delete subscription (ownership enforced) |

### POST /api/v1/webhooks — Request body

```json
{
  "targetUrl": "https://example.com/payment-hook",
  "bearerToken": "secret-token",
  "eventTypes": ["PAYMENT_COMPLETED", "PAYMENT_FAILED"],
  "adminScope": false
}
```

**Validation:**
- `targetUrl`: `@NotBlank`, must be a valid URL (validated via `@URL`)
- `bearerToken`: `@NotBlank`
- `eventTypes`: `@NotEmpty`, each element must be a valid event type constant — return 400 with a message listing valid values if invalid
- `adminScope`: defaults to `false`; `true` accepted only for ADMIN callers (403 for USER callers)

### GET /api/v1/webhooks — Response

Returns a list of `WebhookSubscriptionResponse` objects. Users see only their own. Admins see all.

### Delivery payload (sent to `targetUrl`)

```json
{
  "eventType": "PAYMENT_COMPLETED",
  "paymentId": "abc-123",
  "timestamp": "2026-04-09T12:00:00",
  "payment": {
    "id": "abc-123",
    "amount": 150.00,
    "currency": "USD",
    "status": "COMPLETED",
    ...
  }
}
```

---

## 4. Event Flow

### 4a. Event publishing

`PaymentServiceImpl` calls `ApplicationEventPublisher.publishEvent(new PaymentEvent(payment, eventType))` at the following points:
- End of `createPayment()` → `PAYMENT_CREATED`
- Inside `updatePaymentStatus()` after each status change → mapped to the appropriate constant; always also publishes `PAYMENT_STATUS_CHANGED`
- End of `cancelPayment()` → `PAYMENT_CANCELLED`
- End of `initiatePaymentReversal()` → `PAYMENT_REVERSED` or `PAYMENT_REFUNDED` depending on outcome

`PaymentEvent` is a plain POJO carrying the `Payment` entity and the `WebhookEventType` enum value.

### 4b. Fan-out listener

`WebhookEventListener` annotated with:
- `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` — fires only after the triggering transaction commits successfully
- `@Async` — does not block the payment request thread

On each event:
1. Query `webhook_subscriptions` for active subscriptions where `event_types` contains the event type OR `PAYMENT_STATUS_CHANGED` (for all status events).
2. Filter: user-scoped subscriptions match only if `subscription.user_id == payment.created_by`; admin-scoped subscriptions (`admin_scope = TRUE`) match all payments.
3. For each matching subscription, insert a `WebhookDelivery` row with `status = PENDING`, `attempt_count = 0`, `next_retry_at = now`, `payload = JSON(PaymentResponse)`.

### 4c. Dispatcher scheduler

`WebhookDispatcherService` runs every 30 seconds via `@Scheduled(fixedDelay = 30_000)`.

1. Fetch all `WebhookDelivery` rows where `status = PENDING` AND `next_retry_at <= now` AND `attempt_count < 5`.
2. For each delivery:
   - POST to `targetUrl` with `Authorization: Bearer <token>` and `Content-Type: application/json`.
   - Use Spring `RestClient` with a 10-second connect+read timeout.
   - **On HTTP 2xx:** set `status = DELIVERED`, `response_status = <code>`, `last_attempt_at = now`.
   - **On any other response or exception:** increment `attempt_count`, set `last_attempt_at = now`, set `response_status = <code or null>`.
     - If `attempt_count < 5`: set `next_retry_at = now + (30s × 2^attempt_count)`.
     - If `attempt_count == 5`: set `status = FAILED`.

**Backoff schedule:**
| Attempt | Delay before next retry |
|---|---|
| 1 | 30s |
| 2 | 60s |
| 3 | 120s |
| 4 | 240s |
| 5 | → FAILED (no more retries) |

**Dispatcher safety:** Each delivery is updated in its own transaction. A failure to deliver one does not affect others.

---

## 5. Component List

| Layer | Class | Notes |
|---|---|---|
| Model | `WebhookSubscription` | JPA entity, V10 |
| Model | `WebhookDelivery` | JPA entity, V11 |
| Model | `WebhookEventType` | Enum of valid event type constants |
| Repository | `WebhookSubscriptionRepository` | JPA repo |
| Repository | `WebhookDeliveryRepository` | JPA repo, custom query for dispatcher polling |
| DTO | `WebhookSubscriptionRequest` | POST/PATCH request body with validation |
| DTO | `WebhookSubscriptionResponse` | Response (omits bearerToken for security) |
| DTO | `WebhookDeliveryPayload` | JSON shape sent to targetUrl |
| Service (iface) | `WebhookService` | CRUD for subscriptions |
| Service (impl) | `WebhookServiceImpl` | Ownership checks, adminScope enforcement |
| Event | `PaymentEvent` | POJO: Payment + WebhookEventType |
| Listener | `WebhookEventListener` | `@TransactionalEventListener` + `@Async` fan-out |
| Scheduler | `WebhookDispatcherService` | `@Scheduled` dispatcher with RestClient |
| Controller | `WebhookController` | `/api/v1/webhooks` CRUD endpoints |
| Migration | `V10__add_webhook_subscriptions.sql` | |
| Migration | `V11__add_webhook_deliveries.sql` | |

---

## 6. Security Considerations

- `bearerToken` is stored as plain text in the DB (it's a secret the *caller* provides to authenticate with their own system — not our secret). Document that callers should use a strong random token.
- Response bodies for `GET /api/v1/webhooks` and `GET /api/v1/webhooks/{id}` **omit** `bearerToken` (masked as `"***"`) to avoid leaking it in API responses after registration.
- Ownership checks mirror the existing `checkOwnership()` pattern in `PaymentServiceImpl`: non-admin users may only read/update/delete their own subscriptions.
- `adminScope = true` is rejected with 403 for non-admin callers at the service layer.
- `RestClient` calls to external URLs are made server-side. No SSRF allowlist is added (out of scope), but `targetUrl` validation ensures it is a well-formed URL.

---

## 7. Testing Strategy

### Unit tests
- `WebhookServiceImplTest`: ownership enforcement, `adminScope` blocked for USER role, invalid event type rejected, CRUD operations
- `WebhookEventListenerTest`: correct subscriptions matched per event type, user-scoped vs admin-scoped selection, inactive subscriptions skipped, PAYMENT_STATUS_CHANGED catch-all behavior
- `WebhookDispatcherServiceTest`: 2xx → DELIVERED, 4xx/5xx → increment attempt + backoff calculation, 5th failure → FAILED, delivery isolation (one failure doesn't affect others)

### Integration tests (MockMvc)
- `WebhookControllerTest`: full CRUD lifecycle, 403 on cross-user access, 403 on non-admin `adminScope: true`, 400 on invalid event types, 400 on invalid URL

### BDD acceptance tests (`src/test/resources/features/webhooks/`)
- `webhook_registration.feature`: register, list, update, delete subscription
- `webhook_delivery.feature`:
  - User registers webhook → payment completes → delivery row created and dispatched
  - Admin registers system-wide webhook → receives events for other users' payments
  - Failed delivery retried up to 5 times then marked FAILED
  - Inactive subscription receives no deliveries

---

## 8. Out of Scope

- SSRF allowlist / URL blocklist for targetUrl
- Webhook delivery history endpoint (deliveries are internal-only; no API to query them)
- HMAC-SHA256 signing (Bearer token chosen instead)
- Webhook test/ping endpoint (can be added later)
