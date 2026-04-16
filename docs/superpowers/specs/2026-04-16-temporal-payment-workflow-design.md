# Temporal.io Payment Creation Workflow — Design Spec

**Date:** 2026-04-16  
**Status:** Approved  
**Stack:** Spring Boot 3.5.13 · Java 21 · Temporal SDK 1.26.1 · MySQL 8 · Redis

---

## 1. Goal

Replace the imperative, single-`@Transactional` payment creation flow
(`CreatePaymentHandler.handle()`) with a durable, replayable Temporal workflow.
The workflow orchestrates every step — validation, persistence, fund transfer,
completion, notification, and webhook event — with per-step retry policies and
automatic saga compensation on failure.

`POST /api/v1/payments` changes from synchronous (`201 Created` + full body) to
asynchronous (`202 Accepted` + workflowId). Clients poll the existing
`GET /api/v1/payments/{id}` endpoint until status leaves `PENDING`.

---

## 2. Scope

**In scope:**
- `CreatePaymentHandler` fully replaced by `PaymentCreationWorkflow` + Activities
- `PaymentController.createPayment()` updated to return `202` + `PaymentWorkflowResponse`
- Docker Compose: `temporal` + `temporal-ui` services added
- `TemporalConfig`, `TemporalProperties` Spring beans
- Four activity interface/impl pairs wrapping existing shared helpers
- Three-layer test coverage (workflow unit, activity unit, integration)
- Existing `PaymentSystemTest`, `PaymentControllerTest`, Cucumber tests updated for new contract
- `omega-lifecycle-engineer.md` updated to capture payment-api stack context

**Out of scope (future iterations):**
- `ReversalHandler`, `CancellationHandler`, `PaymentLifecycleHandler` migration
- Replacing `SchedulerService` retry loop with Temporal retry policy
- Temporal Cloud / remote namespace configuration
- Temporal metrics integration with Micrometer

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    PaymentController                         │
│  POST /api/v1/payments → 202 Accepted + workflowId          │
│  GET  /api/v1/payments/{id} → existing (polls DB status)    │
└──────────────────────┬──────────────────────────────────────┘
                       │ WorkflowClient.start(workflow::create)
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              Temporal Server (Docker Compose)                │
│  Image: temporalio/auto-setup:1.26.2                        │
│  Namespace: payment-api  │  Port: 7233                      │
│  UI:  temporalio/ui:2.32.0  │  Port: 8088                   │
└──────────────────────┬──────────────────────────────────────┘
                       │ long-polls task queue
                       ▼
┌─────────────────────────────────────────────────────────────┐
│          PaymentWorker (Spring @Bean, same JVM)              │
│  Task Queue: payment-creation-queue                          │
│  Runs: PaymentCreationWorkflowImpl                           │
└──────┬──────────┬──────────┬──────────┬────────────────────┘
       │          │          │          │
       ▼          ▼          ▼          ▼
  Validation  Persistence  Transfer  Notification
  Activities  Activities  Activities  Activities
       │          │          │          │
       ▼          ▼          ▼          ▼
  PaymentValidation  PaymentRepository  BankingAPIService  NotificationService
  Service            AuditService       (+ Resilience4j)   PaymentEventPublisher
  (unchanged)        (unchanged)        (unchanged)        (unchanged)
```

All existing shared helpers are called from Activity implementations — no logic
is duplicated or moved.

---

## 4. Package Structure

```
src/main/java/com/example/paymentapi/temporal/
├── workflow/
│   ├── PaymentCreationWorkflow.java            (@WorkflowInterface)
│   └── PaymentCreationWorkflowImpl.java        (Saga + @QueryMethod)
├── activity/
│   ├── PaymentValidationActivities.java        (@ActivityInterface)
│   ├── PaymentValidationActivitiesImpl.java    → PaymentValidationService
│   ├── PaymentPersistenceActivities.java       (@ActivityInterface)
│   ├── PaymentPersistenceActivitiesImpl.java   → PaymentRepository + AuditService
│   ├── PaymentTransferActivities.java          (@ActivityInterface)
│   ├── PaymentTransferActivitiesImpl.java      → BankingAPIService
│   ├── PaymentNotificationActivities.java      (@ActivityInterface)
│   └── PaymentNotificationActivitiesImpl.java  → NotificationService + PaymentEventPublisher
├── config/
│   ├── TemporalConfig.java                     (WorkflowClient, WorkerFactory, Worker beans)
│   └── TemporalProperties.java                 (@ConfigurationProperties prefix="temporal")
└── dto/
    └── PaymentCreationResult.java              (workflowId, paymentId, finalStatus)
```

---

## 5. Workflow Design

### 5.1 Interfaces

```java
@WorkflowInterface
public interface PaymentCreationWorkflow {
    @WorkflowMethod
    PaymentCreationResult create(PaymentRequest request, String initiatedBy);

    @QueryMethod
    String getCurrentStatus();
}
```

### 5.2 Execution Sequence with Saga Compensation

```
PaymentCreationWorkflowImpl.create(request, initiatedBy)
│
├─ currentStatus = "VALIDATING"
├─ 1. validateAccounts(source, destination)       retry 3×, 1s initial, no backoff
├─ 2. validateFunds(source, amount)               retry 3×, 1s initial, no backoff
│
├─ currentStatus = "PERSISTING"
├─ 3. persistPending(request, initiatedBy)        retry 3×, 1s initial
│      └─ saga.addCompensation(failPayment(id, reason))
│
├─ currentStatus = "TRANSFERRING"
├─ 4. transferFunds(source, dest, amount)         retry 5×, 500ms initial, 2× exponential
│      └─ on failure → saga.compensate() → failPayment() → throw ApplicationFailure
│
├─ currentStatus = "COMPLETING"
├─ 5. completePayment(paymentId)                  retry 3×, 1s initial
│
├─ currentStatus = "NOTIFYING"
├─ 6. sendNotification(paymentId, email, msg)     retry 3×, best-effort (no saga compensation)
├─ 7. publishWebhookEvent(paymentId, createdBy)   retry 3×, best-effort (no saga compensation)
│
└─ currentStatus = "COMPLETED"
   return PaymentCreationResult(workflowId, paymentId, "COMPLETED")
```

**Compensation rule:** Steps 6 and 7 (notifications, webhook event) are best-effort.
Failure does not trigger saga rollback — the payment is already completed in the DB.

### 5.3 Retry Policies

| Activity Group       | Max Attempts | Initial Interval | Backoff | Saga Compensation |
|----------------------|-------------|-----------------|---------|-------------------|
| Validation           | 3           | 1s              | 1×      | No                |
| Persistence          | 3           | 1s              | 1×      | Yes (on downstream failure) |
| Transfer (banking)   | 5           | 500ms           | 2× exp  | Yes               |
| Notification/Events  | 3           | 1s              | 1×      | No                |

---

## 6. Activity Contracts

### PaymentValidationActivities
```java
void validateAccounts(String sourceAccount, String destinationAccount);
void validateFunds(String sourceAccount, BigDecimal amount);
```
Implementations delegate directly to `PaymentValidationService` — no new logic.

### PaymentPersistenceActivities
```java
String persistPending(PaymentRequest request, String initiatedBy);   // returns paymentId
void completePayment(String paymentId);
void failPayment(String paymentId, String reason);
```
`persistPending` creates the `Payment` entity (status=PENDING), creates a
`Transaction` record, and logs `PAYMENT_CREATED` audit event.
`completePayment` sets status=COMPLETED and logs `PAYMENT_COMPLETED`.
`failPayment` sets status=FAILED and logs `PAYMENT_FAILED` — used as saga compensation.

### PaymentTransferActivities
```java
void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount);
```
Delegates to `BankingAPIService.transferFunds()`. The existing Resilience4j
circuit breaker on `BankingAPIService` remains — Temporal retry is the outer
retry layer, Resilience4j remains as circuit-breaking protection.

### PaymentNotificationActivities
```java
void sendNotification(String paymentId, String email, String message);
void publishWebhookEvent(String paymentId, String createdBy);
```
`sendNotification` delegates to `NotificationService`.
`publishWebhookEvent` delegates to `PaymentEventPublisher.publish()`.

---

## 7. API Contract Changes

### POST /api/v1/payments

**Before:**
```
201 Created
{ "id": "...", "status": "COMPLETED", ... }   ← full PaymentResponse
```

**After:**
```
202 Accepted
{
  "workflowId": "payment-<uuid>",
  "status": "PENDING",
  "statusUrl": "/api/v1/payments/<paymentId>"
}
```

`statusUrl` is populated once `persistPending` completes (paymentId is known).
Before persistence: `statusUrl` is null — clients may poll using `workflowId` via a
future status endpoint (out of scope for this iteration).

### GET /api/v1/payments/{id}  (unchanged)
Clients poll this endpoint. The response status field transitions:
`PENDING` → `PROCESSING` → `COMPLETED` | `FAILED`

### New DTO: PaymentWorkflowResponse
```java
public record PaymentWorkflowResponse(
    String workflowId,
    String status,
    @Nullable String statusUrl
) {}
```

---

## 8. Spring Configuration

### TemporalProperties
```
temporal.host          = ${TEMPORAL_HOST:localhost:7233}
temporal.namespace     = payment-api
temporal.task-queue    = payment-creation-queue
temporal.enabled       = true
temporal.worker.max-workflow-pollers  = 4
temporal.worker.max-activity-pollers  = 4
```

### application-test.properties additions
```
temporal.enabled=false    # worker disabled; TestWorkflowEnvironment registered instead
```

### TemporalConfig
```
@ConditionalOnProperty(name="temporal.enabled", havingValue="true", matchIfMissing=true)
WorkflowServiceStubs → WorkflowClient → WorkerFactory
                                           └── Worker (registers workflow + 4 activity impls)
                                                └── factory.start() on @PostConstruct
```

---

## 9. Docker Compose

```yaml
temporal:
  image: temporalio/auto-setup:1.26.2
  ports:
    - "7233:7233"
  environment:
    - DB=mysql8
    - MYSQL_USER=${DB_USERNAME}
    - MYSQL_PWD=${DB_PASSWORD}
    - MYSQL_SEEDS=mysql
    - DYNAMIC_CONFIG_FILE_PATH=config/dynamicconfig/development.yaml
  depends_on:
    mysql:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "tctl", "--address", "temporal:7233", "cluster", "health"]
    interval: 10s
    timeout: 5s
    retries: 10

temporal-ui:
  image: temporalio/ui:2.32.0
  ports:
    - "8088:8080"
  environment:
    - TEMPORAL_ADDRESS=temporal:7233
  depends_on:
    - temporal
```

---

## 10. pom.xml Changes

```xml
<properties>
    <temporal.version>1.26.1</temporal.version>
</properties>

<!-- Main -->
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>${temporal.version}</version>
</dependency>

<!-- Test -->
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-testing</artifactId>
    <version>${temporal.version}</version>
    <scope>test</scope>
</dependency>
```

---

## 11. Testing Strategy

### Layer 1 — Workflow Unit Tests (fast, no Spring context)
`PaymentCreationWorkflowTest` using `TestWorkflowEnvironment` + Mockito-mocked activities.

Covers:
- Happy path: all activities succeed → result status = COMPLETED
- Transfer failure: saga.compensate() called → failPayment activity invoked
- Notification failure: payment stays COMPLETED (no compensation)
- `@QueryMethod getCurrentStatus()` returns correct intermediate values
- Retry exhaustion: ApplicationFailure thrown after max attempts

### Layer 2 — Activity Unit Tests (Spring context, mocked dependencies)
One test class per activity impl, `@SpringBootTest`, mocked shared helpers via `@MockitoBean`.

Covers:
- `PaymentValidationActivitiesImplTest`
- `PaymentPersistenceActivitiesImplTest`
- `PaymentTransferActivitiesImplTest`
- `PaymentNotificationActivitiesImplTest`

### Layer 3 — Integration Tests (Spring context + TestWorkflowEnvironment + H2)
`PaymentWorkflowIntegrationTest` — `TemporalTestConfig` registers `TestWorkflowEnvironment`
as `@Primary` bean, replacing real `WorkflowClient`. Activities run with real Spring beans
against H2.

Covers:
- Full round-trip: `POST /api/v1/payments` → 202 → poll `GET` → COMPLETED
- Banking API throws → payment lands in FAILED state in DB
- Same workflowId rejected (Temporal idempotency)
- Concurrent workflow executions

### Existing Tests
`PaymentSystemTest`, `PaymentControllerTest`, Cucumber BDD tests updated:
- `POST /api/v1/payments` assertions changed: `201 → 202`, `PaymentResponse → PaymentWorkflowResponse`
- Add follow-up `GET /api/v1/payments/{paymentId}` poll step until status = COMPLETED

---

## 12. Deleted Files

- `src/main/java/com/example/paymentapi/service/command/CreatePaymentHandler.java`

---

## 13. New Flyway Migration

None required. `PaymentCreationWorkflow` writes to existing `payments`, `transactions`,
and `audit_logs` tables via the unchanged persistence activities.

---

## 14. Non-Goals / Future Work

- Migrating `ReversalHandler`, `CancellationHandler`, `PaymentLifecycleHandler`
- Replacing `SchedulerService` cron with Temporal schedules
- Temporal Cloud namespace configuration
- Micrometer metrics bridge for Temporal worker metrics
- Signal-based payment cancellation mid-flight
