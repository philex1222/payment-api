# Webhook Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement configurable push-notification webhooks that fire on payment events, with a persisted delivery queue and exponential-backoff retry dispatcher.

**Architecture:** `PaymentServiceImpl` publishes `PaymentEvent` via Spring's `ApplicationEventPublisher`. `WebhookEventListener` (annotated `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`) fans out to matching `WebhookSubscription` rows and inserts `WebhookDelivery` rows. `WebhookDispatcherService` polls PENDING deliveries every 30s via `@Scheduled` and POSTs to target URLs using Spring's `RestClient`. Both entities are backed by Flyway migrations (V10/V11). Existing `@EnableAsync` in `AsyncConfig` and `@EnableScheduling` (gated on `scheduler.enabled`) cover the async and scheduling needs.

**Tech Stack:** Spring Boot 3.5.13, JPA (H2 in tests / MySQL in prod), Flyway, Spring `ApplicationEventPublisher`, `RestClient` (Spring 6.1), Hibernate Validator `@URL`, JUnit 5, Mockito, Cucumber + RestAssured.

---

## File Map

**New (production):**
- `src/main/java/com/example/paymentapi/model/WebhookEventType.java` — enum of valid event type names
- `src/main/java/com/example/paymentapi/model/WebhookSubscription.java` — JPA entity (webhook_subscriptions)
- `src/main/java/com/example/paymentapi/model/WebhookDelivery.java` — JPA entity (webhook_deliveries)
- `src/main/resources/db/migration/V10__add_webhook_subscriptions.sql`
- `src/main/resources/db/migration/V11__add_webhook_deliveries.sql`
- `src/main/java/com/example/paymentapi/repository/WebhookSubscriptionRepository.java`
- `src/main/java/com/example/paymentapi/repository/WebhookDeliveryRepository.java`
- `src/main/java/com/example/paymentapi/dto/WebhookSubscriptionRequest.java`
- `src/main/java/com/example/paymentapi/dto/WebhookSubscriptionResponse.java`
- `src/main/java/com/example/paymentapi/dto/WebhookDeliveryResponse.java`
- `src/main/java/com/example/paymentapi/dto/WebhookDeliveryPayload.java`
- `src/main/java/com/example/paymentapi/service/WebhookService.java`
- `src/main/java/com/example/paymentapi/service/WebhookServiceImpl.java`
- `src/main/java/com/example/paymentapi/event/PaymentEvent.java`
- `src/main/java/com/example/paymentapi/event/WebhookEventListener.java`
- `src/main/java/com/example/paymentapi/service/WebhookDispatcherService.java`
- `src/main/java/com/example/paymentapi/controller/WebhookController.java`
- `src/main/java/com/example/paymentapi/config/WebhookConfig.java`

**Modified (production):**
- `src/main/java/com/example/paymentapi/service/PaymentServiceImpl.java` — inject `ApplicationEventPublisher`, call `publishEvent()` at key points
- `src/main/java/com/example/paymentapi/config/SecurityConfig.java` — add `/api/v1/webhooks/**` authorization rule
- `src/main/java/com/example/paymentapi/exception/PaymentExceptionHandler.java` — add `NoSuchElementException` → 404 handler

**New (tests):**
- `src/test/java/com/example/paymentapi/controller/WebhookControllerTest.java`
- `src/test/java/com/example/paymentapi/service/WebhookServiceImplTest.java`
- `src/test/java/com/example/paymentapi/event/WebhookEventListenerTest.java`
- `src/test/java/com/example/paymentapi/service/WebhookDispatcherServiceTest.java`
- `src/test/java/com/example/paymentapi/controller/WebhookTestController.java` — `@Profile("test")` echo endpoint for BDD
- `src/test/resources/features/webhooks/webhook_registration.feature`
- `src/test/resources/features/webhooks/webhook_delivery.feature`
- `src/test/java/com/example/paymentapi/bdd/steps/WebhookSteps.java`

---

## Task 1: Flyway Migrations (V10 + V11)

**Files:**
- Create: `src/main/resources/db/migration/V10__add_webhook_subscriptions.sql`
- Create: `src/main/resources/db/migration/V11__add_webhook_deliveries.sql`

Note: Tests use `spring.flyway.enabled=false` with `ddl-auto=create-drop`, so Hibernate creates the tables from JPA entities. Migrations only run in production (MySQL). They are still required for V10/V11 ordering in prod deployments.

- [ ] **Step 1: Write V10 migration**

```sql
-- V10: Create webhook_subscriptions table.
-- event_types stores comma-separated WebhookEventType names.
CREATE TABLE webhook_subscriptions (
    id           VARCHAR(36)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    target_url   VARCHAR(512) NOT NULL,
    bearer_token VARCHAR(512) NOT NULL,
    event_types  VARCHAR(512) NOT NULL COMMENT 'Comma-separated WebhookEventType names',
    admin_scope  BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ws_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_ws_user_id (user_id),
    INDEX idx_ws_active  (active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
```

- [ ] **Step 2: Write V11 migration**

```sql
-- V11: Create webhook_deliveries table.
-- Composite index on (status, next_retry_at) supports the dispatcher's polling query.
CREATE TABLE webhook_deliveries (
    id              VARCHAR(36) NOT NULL,
    subscription_id VARCHAR(36) NOT NULL,
    payment_id      VARCHAR(36) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload         TEXT        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT         NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(6)          NULL,
    next_retry_at   DATETIME(6) NOT NULL,
    response_status INT                  NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_wd_sub FOREIGN KEY (subscription_id) REFERENCES webhook_subscriptions (id) ON DELETE CASCADE,
    INDEX idx_wd_status_retry (status, next_retry_at),
    INDEX idx_wd_payment_id   (payment_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V10__add_webhook_subscriptions.sql \
        src/main/resources/db/migration/V11__add_webhook_deliveries.sql
git commit -m "feat: Flyway V10+V11 — webhook_subscriptions + webhook_deliveries tables"
```

---

## Task 2: WebhookEventType Enum

**Files:**
- Create: `src/main/java/com/example/paymentapi/model/WebhookEventType.java`
- Test: inline in Task 6 (listener test validates enum values)

- [ ] **Step 1: Write the enum**

```java
package com.example.paymentapi.model;

public enum WebhookEventType {
    PAYMENT_CREATED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    PAYMENT_CANCELLED,
    PAYMENT_REVERSED,
    PAYMENT_REFUNDED,
    PAYMENT_STATUS_CHANGED;

    public static boolean isValid(String value) {
        for (WebhookEventType type : values()) {
            if (type.name().equals(value)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/paymentapi/model/WebhookEventType.java
git commit -m "feat: add WebhookEventType enum"
```

---

## Task 3: JPA Entities — WebhookSubscription + WebhookDelivery

**Files:**
- Create: `src/main/java/com/example/paymentapi/model/WebhookSubscription.java`
- Create: `src/main/java/com/example/paymentapi/model/WebhookDelivery.java`

Follow the established pattern from `Payment` and `Transaction`: `@Getter @Setter @NoArgsConstructor @Entity @EntityListeners(AuditingEntityListener.class)`, `@CreatedDate`, manual `equals/hashCode` on id only.

- [ ] **Step 1: Write WebhookSubscription entity**

```java
package com.example.paymentapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "webhook_subscriptions")
@EntityListeners(AuditingEntityListener.class)
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "target_url", nullable = false, length = 512)
    private String targetUrl;

    @Column(name = "bearer_token", nullable = false, length = 512)
    private String bearerToken;

    /** Comma-separated WebhookEventType names, e.g. "PAYMENT_CREATED,PAYMENT_FAILED" */
    @Column(name = "event_types", nullable = false, length = 512)
    private String eventTypes;

    @Column(name = "admin_scope", nullable = false)
    private boolean adminScope = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookSubscription other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "WebhookSubscription{id='" + id + "', userId=" + userId + ", active=" + active + '}';
    }
}
```

- [ ] **Step 2: Write WebhookDelivery entity**

```java
package com.example.paymentapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "webhook_deliveries")
@EntityListeners(AuditingEntityListener.class)
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(name = "subscription_id", nullable = false, length = 36)
    private String subscriptionId;

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** Pre-serialized JSON payload sent to the target URL. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** PENDING, DELIVERED, or FAILED. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** Scheduler polls where next_retry_at <= now AND status = PENDING. */
    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookDelivery other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "WebhookDelivery{id='" + id + "', paymentId='" + paymentId + "', status='" + status + "'}";
    }
}
```

- [ ] **Step 3: Compile check**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS with no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/paymentapi/model/WebhookSubscription.java \
        src/main/java/com/example/paymentapi/model/WebhookDelivery.java
git commit -m "feat: add WebhookSubscription and WebhookDelivery JPA entities"
```

---

## Task 4: Repositories

**Files:**
- Create: `src/main/java/com/example/paymentapi/repository/WebhookSubscriptionRepository.java`
- Create: `src/main/java/com/example/paymentapi/repository/WebhookDeliveryRepository.java`

- [ ] **Step 1: Write WebhookSubscriptionRepository**

```java
package com.example.paymentapi.repository;

import com.example.paymentapi.model.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, String> {
    List<WebhookSubscription> findByUserId(Long userId);
    List<WebhookSubscription> findByUserIdAndActiveTrue(Long userId);
    List<WebhookSubscription> findByAdminScopeTrueAndActiveTrue();
}
```

- [ ] **Step 2: Write WebhookDeliveryRepository**

The `findPendingDeliveries` query is the dispatcher's hot path. It selects PENDING rows where `nextRetryAt <= now` and `attemptCount < maxAttempts`, ordered by `nextRetryAt` so oldest retries go first.

```java
package com.example.paymentapi.repository;

import com.example.paymentapi.model.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    @Query("SELECT d FROM WebhookDelivery d " +
           "WHERE d.status = 'PENDING' " +
           "AND d.nextRetryAt <= :now " +
           "AND d.attemptCount < :maxAttempts " +
           "ORDER BY d.nextRetryAt ASC")
    List<WebhookDelivery> findPendingDeliveries(
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts);

    List<WebhookDelivery> findBySubscriptionId(String subscriptionId);
}
```

- [ ] **Step 3: Compile check**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/paymentapi/repository/WebhookSubscriptionRepository.java \
        src/main/java/com/example/paymentapi/repository/WebhookDeliveryRepository.java
git commit -m "feat: add WebhookSubscriptionRepository and WebhookDeliveryRepository"
```

---

## Task 5: DTOs

**Files:**
- Create: `src/main/java/com/example/paymentapi/dto/WebhookSubscriptionRequest.java`
- Create: `src/main/java/com/example/paymentapi/dto/WebhookSubscriptionResponse.java`
- Create: `src/main/java/com/example/paymentapi/dto/WebhookDeliveryResponse.java`
- Create: `src/main/java/com/example/paymentapi/dto/WebhookDeliveryPayload.java`

- [ ] **Step 1: Write WebhookSubscriptionRequest**

`@URL` is from Hibernate Validator (`org.hibernate.validator.constraints.URL`) — already on the classpath via `spring-boot-starter-validation`. The `active` field defaults to `true` for POST but can be set to `false` for PATCH to deactivate.

```java
package com.example.paymentapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookSubscriptionRequest {

    @NotBlank(message = "targetUrl is required")
    @URL(message = "targetUrl must be a valid URL")
    private String targetUrl;

    @NotBlank(message = "bearerToken is required")
    private String bearerToken;

    @NotEmpty(message = "eventTypes must not be empty")
    private List<String> eventTypes;

    private boolean adminScope = false;

    @Builder.Default
    private boolean active = true;
}
```

- [ ] **Step 2: Write WebhookSubscriptionResponse**

`bearerToken` is always returned as `"***"` — the real token is never exposed after registration.

```java
package com.example.paymentapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookSubscriptionResponse {
    private String id;
    private Long userId;
    private String targetUrl;
    private String bearerToken; // always "***"
    private List<String> eventTypes;
    private boolean adminScope;
    private boolean active;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Write WebhookDeliveryResponse**

Used by the admin delivery-history endpoint `GET /api/v1/webhooks/{id}/deliveries`.

```java
package com.example.paymentapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookDeliveryResponse {
    private String id;
    private String subscriptionId;
    private String paymentId;
    private String eventType;
    private String status;
    private int attemptCount;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastAttemptAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime nextRetryAt;
    private Integer responseStatus;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
```

- [ ] **Step 4: Write WebhookDeliveryPayload**

This is the JSON body POSTed to the subscriber's `targetUrl`.

```java
package com.example.paymentapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDeliveryPayload {
    private String eventType;
    private String paymentId;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    private PaymentResponse payment;
}
```

- [ ] **Step 5: Compile check**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/paymentapi/dto/WebhookSubscriptionRequest.java \
        src/main/java/com/example/paymentapi/dto/WebhookSubscriptionResponse.java \
        src/main/java/com/example/paymentapi/dto/WebhookDeliveryResponse.java \
        src/main/java/com/example/paymentapi/dto/WebhookDeliveryPayload.java
git commit -m "feat: add webhook DTOs (request, response, delivery payload)"
```

---

## Task 6: WebhookService Interface + WebhookServiceImpl (TDD)

**Files:**
- Create: `src/main/java/com/example/paymentapi/service/WebhookService.java`
- Create: `src/main/java/com/example/paymentapi/service/WebhookServiceImpl.java`
- Create: `src/test/java/com/example/paymentapi/service/WebhookServiceImplTest.java`
- Modify: `src/main/java/com/example/paymentapi/exception/PaymentExceptionHandler.java`

- [ ] **Step 1: Write WebhookService interface**

```java
package com.example.paymentapi.service;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;

import java.util.List;

public interface WebhookService {
    WebhookSubscriptionResponse createSubscription(WebhookSubscriptionRequest request, String username);
    List<WebhookSubscriptionResponse> listSubscriptions(String username, boolean isAdmin);
    WebhookSubscriptionResponse getSubscription(String id, String username, boolean isAdmin);
    WebhookSubscriptionResponse updateSubscription(String id, WebhookSubscriptionRequest request, String username, boolean isAdmin);
    void deleteSubscription(String id, String username, boolean isAdmin);
    List<WebhookDeliveryResponse> getDeliveries(String subscriptionId);
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.example.paymentapi.service;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class WebhookServiceImplTest {

    @Autowired private WebhookService webhookService;
    @Autowired private UserRepository userRepository;
    @Autowired private WebhookSubscriptionRepository subscriptionRepository;

    private User regularUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        regularUser = userRepository.findByUsername("user").orElseThrow();
        adminUser   = userRepository.findByUsername("admin").orElseThrow();
    }

    @Test
    void createSubscription_savesAndReturnsMaskedToken() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("secret-token")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        WebhookSubscriptionResponse resp = webhookService.createSubscription(req, "user");

        assertThat(resp.getId()).isNotNull();
        assertThat(resp.getBearerToken()).isEqualTo("***");
        assertThat(resp.getEventTypes()).containsExactly("PAYMENT_COMPLETED");
        assertThat(resp.isActive()).isTrue();
        assertThat(resp.isAdminScope()).isFalse();
    }

    @Test
    void createSubscription_adminScope_rejectedForNonAdmin() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_CREATED"))
                .adminScope(true)
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createSubscription_invalidEventType_throwsIllegalArgument() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_INVENTED"))
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAYMENT_INVENTED");
    }

    @Test
    void listSubscriptions_userSeesOnlyOwn() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");
        webhookService.createSubscription(req, "admin");

        List<WebhookSubscriptionResponse> userList = webhookService.listSubscriptions("user", false);
        assertThat(userList).allMatch(s -> s.getUserId().equals(regularUser.getId()));
    }

    @Test
    void listSubscriptions_adminSeesAll() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");
        webhookService.createSubscription(req, "admin");

        List<WebhookSubscriptionResponse> adminList = webhookService.listSubscriptions("admin", true);
        assertThat(adminList.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getSubscription_crossUser_throwsAccessDenied() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        WebhookSubscriptionResponse created = webhookService.createSubscription(req, "admin");

        assertThatThrownBy(() -> webhookService.getSubscription(created.getId(), "user", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteSubscription_removesFromDb() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        WebhookSubscriptionResponse created = webhookService.createSubscription(req, "user");

        webhookService.deleteSubscription(created.getId(), "user", false);

        assertThat(subscriptionRepository.findById(created.getId())).isEmpty();
    }

    @Test
    void getSubscription_notFound_throwsNoSuchElement() {
        assertThatThrownBy(() -> webhookService.getSubscription("non-existent-id", "user", false))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateSubscription_deactivatesWithActiveFalse() {
        WebhookSubscriptionRequest createReq = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        WebhookSubscriptionResponse created = webhookService.createSubscription(createReq, "user");

        WebhookSubscriptionRequest updateReq = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .active(false)
                .build();
        WebhookSubscriptionResponse updated = webhookService.updateSubscription(created.getId(), updateReq, "user", false);

        assertThat(updated.isActive()).isFalse();
    }
}
```

- [ ] **Step 3: Run test — verify it fails**

```bash
./mvnw test -pl . -Dtest=WebhookServiceImplTest -q 2>&1 | tail -20
```
Expected: compilation error (WebhookService not found) or test failures.

- [ ] **Step 4: Write WebhookServiceImpl**

```java
package com.example.paymentapi.service;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.model.User;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class WebhookServiceImpl implements WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookServiceImpl.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;

    public WebhookServiceImpl(WebhookSubscriptionRepository subscriptionRepository,
                               WebhookDeliveryRepository deliveryRepository,
                               UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
    }

    @Override
    public WebhookSubscriptionResponse createSubscription(WebhookSubscriptionRequest request, String username) {
        User user = findUser(username);
        if (request.isAdminScope() && !user.getRole().equals("ROLE_ADMIN")) {
            throw new AccessDeniedException("adminScope requires ROLE_ADMIN");
        }
        validateEventTypes(request.getEventTypes());

        WebhookSubscription sub = new WebhookSubscription();
        sub.setUserId(user.getId());
        sub.setTargetUrl(request.getTargetUrl());
        sub.setBearerToken(request.getBearerToken());
        sub.setEventTypes(String.join(",", request.getEventTypes()));
        sub.setAdminScope(request.isAdminScope());
        sub.setActive(request.isActive());

        WebhookSubscription saved = subscriptionRepository.save(sub);
        logger.info("Webhook subscription {} created for user {}", saved.getId(), username);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookSubscriptionResponse> listSubscriptions(String username, boolean isAdmin) {
        if (isAdmin) {
            return subscriptionRepository.findAll().stream().map(this::toResponse).toList();
        }
        User user = findUser(username);
        return subscriptionRepository.findByUserId(user.getId()).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookSubscriptionResponse getSubscription(String id, String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        return toResponse(sub);
    }

    @Override
    public WebhookSubscriptionResponse updateSubscription(String id, WebhookSubscriptionRequest request,
                                                           String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        if (request.isAdminScope()) {
            User user = findUser(username);
            if (!user.getRole().equals("ROLE_ADMIN")) {
                throw new AccessDeniedException("adminScope requires ROLE_ADMIN");
            }
        }
        validateEventTypes(request.getEventTypes());
        sub.setTargetUrl(request.getTargetUrl());
        sub.setBearerToken(request.getBearerToken());
        sub.setEventTypes(String.join(",", request.getEventTypes()));
        sub.setAdminScope(request.isAdminScope());
        sub.setActive(request.isActive());
        return toResponse(subscriptionRepository.save(sub));
    }

    @Override
    public void deleteSubscription(String id, String username, boolean isAdmin) {
        WebhookSubscription sub = findSubscription(id);
        checkOwnership(sub, username, isAdmin);
        subscriptionRepository.delete(sub);
        logger.info("Webhook subscription {} deleted by {}", id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> getDeliveries(String subscriptionId) {
        return deliveryRepository.findBySubscriptionId(subscriptionId).stream()
                .map(this::toDeliveryResponse)
                .toList();
    }

    private WebhookSubscription findSubscription(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Webhook subscription not found: " + id));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    private void checkOwnership(WebhookSubscription sub, String username, boolean isAdmin) {
        if (isAdmin) return;
        User user = findUser(username);
        if (!sub.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("Access denied: webhook subscription belongs to another user");
        }
    }

    private void validateEventTypes(List<String> eventTypes) {
        List<String> invalid = eventTypes.stream()
                .filter(t -> !WebhookEventType.isValid(t))
                .toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid event types: " + invalid + ". Valid values: " +
                Arrays.toString(WebhookEventType.values()));
        }
    }

    private WebhookSubscriptionResponse toResponse(WebhookSubscription sub) {
        return WebhookSubscriptionResponse.builder()
                .id(sub.getId())
                .userId(sub.getUserId())
                .targetUrl(sub.getTargetUrl())
                .bearerToken("***")
                .eventTypes(List.of(sub.getEventTypes().split(",")))
                .adminScope(sub.isAdminScope())
                .active(sub.isActive())
                .createdAt(sub.getCreatedAt())
                .build();
    }

    private WebhookDeliveryResponse toDeliveryResponse(WebhookDelivery d) {
        return WebhookDeliveryResponse.builder()
                .id(d.getId())
                .subscriptionId(d.getSubscriptionId())
                .paymentId(d.getPaymentId())
                .eventType(d.getEventType())
                .status(d.getStatus())
                .attemptCount(d.getAttemptCount())
                .lastAttemptAt(d.getLastAttemptAt())
                .nextRetryAt(d.getNextRetryAt())
                .responseStatus(d.getResponseStatus())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 5: Add NoSuchElementException handler to PaymentExceptionHandler**

In `src/main/java/com/example/paymentapi/exception/PaymentExceptionHandler.java`, add this handler after the `handleUserNotFoundException` method (around line 50):

```java
    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElementException(
            java.util.NoSuchElementException ex, WebRequest request) {
        logger.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = createErrorResponse(
                HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }
```

Also add `import java.util.NoSuchElementException;` at the top of the file.

- [ ] **Step 6: Run tests — verify they pass**

```bash
./mvnw test -pl . -Dtest=WebhookServiceImplTest -q 2>&1 | tail -20
```
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/paymentapi/service/WebhookService.java \
        src/main/java/com/example/paymentapi/service/WebhookServiceImpl.java \
        src/main/java/com/example/paymentapi/exception/PaymentExceptionHandler.java \
        src/test/java/com/example/paymentapi/service/WebhookServiceImplTest.java
git commit -m "feat: WebhookService + WebhookServiceImpl with ownership checks and delivery history"
```

---

## Task 7: PaymentEvent + WebhookEventListener (TDD)

**Files:**
- Create: `src/main/java/com/example/paymentapi/event/PaymentEvent.java`
- Create: `src/main/java/com/example/paymentapi/event/WebhookEventListener.java`
- Create: `src/test/java/com/example/paymentapi/event/WebhookEventListenerTest.java`
- Modify: `src/main/java/com/example/paymentapi/service/PaymentServiceImpl.java`

`PaymentEvent` carries the event type, the payment owner (username), and the pre-serialized `PaymentResponse` snapshot. The listener is called in a separate thread after commit via `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`, so each `deliveryRepository.save()` starts its own transaction (JPA repos are `@Transactional` by default).

- [ ] **Step 1: Write PaymentEvent**

```java
package com.example.paymentapi.event;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.WebhookEventType;

public class PaymentEvent {

    private final WebhookEventType eventType;
    private final String paymentOwner; // payment.getCreatedBy()
    private final PaymentResponse paymentSnapshot;

    public PaymentEvent(WebhookEventType eventType, String paymentOwner, PaymentResponse paymentSnapshot) {
        this.eventType = eventType;
        this.paymentOwner = paymentOwner;
        this.paymentSnapshot = paymentSnapshot;
    }

    public WebhookEventType getEventType() { return eventType; }
    public String getPaymentOwner() { return paymentOwner; }
    public PaymentResponse getPaymentSnapshot() { return paymentSnapshot; }
}
```

- [ ] **Step 2: Write the failing test for WebhookEventListener**

```java
package com.example.paymentapi.event;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class WebhookEventListenerTest {

    @Autowired private WebhookEventListener webhookEventListener;
    @Autowired private WebhookService webhookService;
    @Autowired private WebhookDeliveryRepository deliveryRepository;

    private PaymentResponse samplePayment;

    @BeforeEach
    void setUp() {
        samplePayment = PaymentResponse.builder()
                .id("pay-001")
                .amount(BigDecimal.valueOf(100))
                .currency("USD")
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void handlePaymentEvent_createsDeliveryForMatchingSubscription() {
        // Register a subscription for PAYMENT_COMPLETED on behalf of "user"
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        var deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(deliveries.get(0).getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(deliveries.get(0).getPaymentId()).isEqualTo("pay-001");
    }

    @Test
    void handlePaymentEvent_inactiveSubscription_noDeliveryCreated() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .active(false)
                .build();
        webhookService.createSubscription(req, "user");

        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void handlePaymentEvent_differentEventType_noDeliveryCreated() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");

        // PAYMENT_FAILED does not match PAYMENT_COMPLETED subscription
        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_FAILED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).isEmpty();
    }

    @Test
    void handlePaymentEvent_statusChangedCatchAll_matchesAnyStatusEvent() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_STATUS_CHANGED"))
                .build();
        webhookService.createSubscription(req, "user");

        // PAYMENT_FAILED should match PAYMENT_STATUS_CHANGED catch-all
        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_FAILED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).hasSize(1);
    }

    @Test
    void handlePaymentEvent_adminScope_receivesOtherUsersEvents() {
        // Admin registers a system-wide subscription
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .adminScope(true)
                .build();
        webhookService.createSubscription(req, "admin");

        // Event is for "user", not "admin"
        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).hasSize(1);
    }

    @Test
    void handlePaymentEvent_wrongOwner_userScopedSubscriptionSkipped() {
        // Subscription belongs to "admin"
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "admin");

        // Event is for "user" — admin's user-scoped sub should NOT match
        PaymentEvent event = new PaymentEvent(WebhookEventType.PAYMENT_COMPLETED, "user", samplePayment);
        webhookEventListener.handlePaymentEvent(event);

        assertThat(deliveryRepository.findAll()).isEmpty();
    }
}
```

- [ ] **Step 3: Run test — verify it fails**

```bash
./mvnw test -pl . -Dtest=WebhookEventListenerTest -q 2>&1 | tail -20
```
Expected: compilation error (`WebhookEventListener` not found).

- [ ] **Step 4: Write WebhookEventListener**

```java
package com.example.paymentapi.event;

import com.example.paymentapi.dto.WebhookDeliveryPayload;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class WebhookEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebhookEventListener.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventListener(WebhookSubscriptionRepository subscriptionRepository,
                                 WebhookDeliveryRepository deliveryRepository,
                                 UserRepository userRepository,
                                 ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional
    public void handlePaymentEvent(PaymentEvent event) {
        logger.debug("Handling PaymentEvent: type={}, owner={}", event.getEventType(), event.getPaymentOwner());

        String payloadJson;
        try {
            WebhookDeliveryPayload payload = WebhookDeliveryPayload.builder()
                    .eventType(event.getEventType().name())
                    .paymentId(event.getPaymentSnapshot().getId())
                    .timestamp(LocalDateTime.now())
                    .payment(event.getPaymentSnapshot())
                    .build();
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize webhook payload for event {}: {}", event.getEventType(), e.getMessage());
            return;
        }

        List<WebhookSubscription> matches = findMatchingSubscriptions(event);
        if (matches.isEmpty()) {
            logger.debug("No active subscriptions matched for event {}", event.getEventType());
            return;
        }

        String paymentId = event.getPaymentSnapshot().getId();
        String eventTypeName = event.getEventType().name();

        for (WebhookSubscription sub : matches) {
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setSubscriptionId(sub.getId());
            delivery.setPaymentId(paymentId);
            delivery.setEventType(eventTypeName);
            delivery.setPayload(payloadJson);
            delivery.setStatus("PENDING");
            delivery.setAttemptCount(0);
            delivery.setNextRetryAt(LocalDateTime.now());
            deliveryRepository.save(delivery);
            logger.debug("Queued delivery for subscription {} on payment {}", sub.getId(), paymentId);
        }
    }

    private List<WebhookSubscription> findMatchingSubscriptions(PaymentEvent event) {
        List<WebhookSubscription> result = new ArrayList<>();

        // Admin-scoped: fire for all payments regardless of owner
        for (WebhookSubscription sub : subscriptionRepository.findByAdminScopeTrueAndActiveTrue()) {
            if (subscribedTo(sub, event.getEventType())) {
                result.add(sub);
            }
        }

        // User-scoped: fire only for the payment owner's own subscriptions
        if (event.getPaymentOwner() != null) {
            userRepository.findByUsername(event.getPaymentOwner()).ifPresent(user -> {
                for (WebhookSubscription sub : subscriptionRepository.findByUserIdAndActiveTrue(user.getId())) {
                    if (!sub.isAdminScope() && subscribedTo(sub, event.getEventType())) {
                        result.add(sub);
                    }
                }
            });
        }

        return result;
    }

    /** Returns true if the subscription includes the given event type, or the PAYMENT_STATUS_CHANGED catch-all. */
    private boolean subscribedTo(WebhookSubscription sub, WebhookEventType eventType) {
        for (String t : sub.getEventTypes().split(",")) {
            String trimmed = t.trim();
            if (trimmed.equals(eventType.name()) || trimmed.equals(WebhookEventType.PAYMENT_STATUS_CHANGED.name())) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw test -pl . -Dtest=WebhookEventListenerTest -q 2>&1 | tail -20
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 6: Modify PaymentServiceImpl — inject ApplicationEventPublisher and publish events**

Add the `ApplicationEventPublisher` field to `PaymentServiceImpl`. This requires modifying the constructor and adding publish calls at four points. All other `PaymentServiceImpl` logic stays unchanged.

**6a. Add import and field:**
After the existing imports, add:
```java
import com.example.paymentapi.event.PaymentEvent;
import com.example.paymentapi.model.WebhookEventType;
import org.springframework.context.ApplicationEventPublisher;
```

**6b. Add field declaration** (after `private final PaymentMetrics paymentMetrics;`):
```java
    private final ApplicationEventPublisher eventPublisher;
```

**6c. Update constructor** to include `ApplicationEventPublisher eventPublisher` as the last parameter and set `this.eventPublisher = eventPublisher;`

**6d. Publish PAYMENT_CREATED after createPayment builds its response** (at the end of the try block, before return, after the `notificationService.sendPaymentNotification` call):
```java
            eventPublisher.publishEvent(new PaymentEvent(
                    WebhookEventType.PAYMENT_CREATED, createdPayment.getCreatedBy(), paymentResponse));
```
Note: `paymentResponse` is the `PaymentResponse` object built at the end of `createPayment`. Move the `return` statement after the `publishEvent` call. Restructure so `paymentResponse` is built before the event is published:

Replace the final return block of the try block with:
```java
            PaymentResponse paymentResponse = PaymentResponse.builder()
                    .id(createdPayment.getId())
                    .sourceAccount(maskAccount(createdPayment.getSourceAccount()))
                    .destinationAccount(maskAccount(createdPayment.getDestinationAccount()))
                    .amount(createdPayment.getAmount())
                    .currency(createdPayment.getCurrency())
                    .description(createdPayment.getDescription())
                    .status(createdPayment.getStatus())
                    .statusDescription(PaymentStatus.fromString(createdPayment.getStatus()).getDescription())
                    .createdAt(createdPayment.getCreatedAt())
                    .transactionId(transactionId)
                    .message("Payment processed successfully")
                    .build();
            eventPublisher.publishEvent(new PaymentEvent(
                    WebhookEventType.PAYMENT_CREATED, createdPayment.getCreatedBy(), paymentResponse));
            return paymentResponse;
```

**6e. Publish after updatePaymentStatus saves** (after the `auditService.logPaymentEvent` call, before the return statement):
```java
        PaymentResponse updatedResponse = mapToResponse(updatedPayment);
        eventPublisher.publishEvent(new PaymentEvent(
                resolveEventType(newStatus), updatedPayment.getCreatedBy(), updatedResponse));
        return updatedResponse;
```
Replace the existing `return mapToResponse(updatedPayment);` with the above.

**6f. Add resolveEventType helper method** to `PaymentServiceImpl` (private):
```java
    private WebhookEventType resolveEventType(PaymentStatus status) {
        return switch (status) {
            case COMPLETED -> WebhookEventType.PAYMENT_COMPLETED;
            case FAILED    -> WebhookEventType.PAYMENT_FAILED;
            case CANCELLED -> WebhookEventType.PAYMENT_CANCELLED;
            case REVERSED  -> WebhookEventType.PAYMENT_REVERSED;
            case REFUNDED  -> WebhookEventType.PAYMENT_REFUNDED;
            default        -> WebhookEventType.PAYMENT_STATUS_CHANGED;
        };
    }
```

**6g. Publish after cancelPayment** — replace `return mapToResponse(updated);` with:
```java
        PaymentResponse cancelResponse = mapToResponse(updated);
        eventPublisher.publishEvent(new PaymentEvent(
                WebhookEventType.PAYMENT_CANCELLED, payment.getCreatedBy(), cancelResponse));
        return cancelResponse;
```

**6h. Publish after initiatePaymentReversal** — in the success path, after `paymentMetrics.incrementReversed()` / `paymentMetrics.incrementRefunded()`, add before the return:
```java
            eventPublisher.publishEvent(new PaymentEvent(
                    newStatus == PaymentStatus.REVERSED
                        ? WebhookEventType.PAYMENT_REVERSED
                        : WebhookEventType.PAYMENT_REFUNDED,
                    payment.getCreatedBy(),
                    PaymentResponse.builder()
                        .id(updatedPayment.getId())
                        .sourceAccount(maskAccount(updatedPayment.getSourceAccount()))
                        .destinationAccount(maskAccount(updatedPayment.getDestinationAccount()))
                        .amount(updatedPayment.getAmount())
                        .currency(updatedPayment.getCurrency())
                        .status(updatedPayment.getStatus())
                        .statusDescription(newStatus.getDescription())
                        .createdAt(updatedPayment.getCreatedAt())
                        .updatedAt(updatedPayment.getUpdatedAt())
                        .build()));
```

- [ ] **Step 7: Compile check**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Run full existing tests to catch regressions**

```bash
./mvnw test -pl . -Dtest="PaymentControllerTest,PaymentApplicationTests" -q 2>&1 | tail -20
```
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/paymentapi/event/PaymentEvent.java \
        src/main/java/com/example/paymentapi/event/WebhookEventListener.java \
        src/main/java/com/example/paymentapi/service/PaymentServiceImpl.java \
        src/test/java/com/example/paymentapi/event/WebhookEventListenerTest.java
git commit -m "feat: PaymentEvent + WebhookEventListener + event publishing in PaymentServiceImpl"
```

---

## Task 8: WebhookDispatcherService + WebhookConfig (TDD)

**Files:**
- Create: `src/main/java/com/example/paymentapi/config/WebhookConfig.java`
- Create: `src/main/java/com/example/paymentapi/service/WebhookDispatcherService.java`
- Create: `src/test/java/com/example/paymentapi/service/WebhookDispatcherServiceTest.java`

The dispatcher is gated on `scheduler.enabled=true` (same `@ConditionalOnProperty` mechanism already on `SchedulingConfig`). Since `scheduler.enabled=false` in tests, `@Scheduled` methods never run — but tests invoke `dispatchPendingDeliveries()` directly.

The `executePost()` method is package-private to enable `@MockitoBean RestClient webhookRestClient` mocking in tests.

- [ ] **Step 1: Write WebhookConfig**

```java
package com.example.paymentapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WebhookConfig {

    /**
     * Dedicated RestClient for webhook deliveries.
     * Uses Spring Boot's auto-configured RestClient.Builder (prototype scope)
     * to get a fresh builder with default message converters.
     * The bean name "webhookRestClient" allows @MockitoBean replacement in tests.
     */
    @Bean
    public RestClient webhookRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.example.paymentapi.service;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class WebhookDispatcherServiceTest {

    @Autowired private WebhookDispatcherService dispatcherService;
    @Autowired private WebhookDeliveryRepository deliveryRepository;
    @Autowired private WebhookSubscriptionRepository subscriptionRepository;

    @MockitoBean
    private RestClient webhookRestClient;

    private WebhookSubscription savedSub;

    @BeforeEach
    void setUp() {
        // Create a subscription row directly (no user FK needed for dispatcher logic)
        WebhookSubscription sub = new WebhookSubscription();
        sub.setUserId(1L);
        sub.setTargetUrl("http://example.com/hook");
        sub.setBearerToken("secret-token");
        sub.setEventTypes("PAYMENT_COMPLETED");
        sub.setActive(true);
        savedSub = subscriptionRepository.save(sub);
    }

    private WebhookDelivery pendingDelivery() {
        WebhookDelivery d = new WebhookDelivery();
        d.setSubscriptionId(savedSub.getId());
        d.setPaymentId("pay-001");
        d.setEventType("PAYMENT_COMPLETED");
        d.setPayload("{\"eventType\":\"PAYMENT_COMPLETED\"}");
        d.setStatus("PENDING");
        d.setAttemptCount(0);
        d.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return deliveryRepository.save(d);
    }

    private void mockRestClient2xx() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any())).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    private void mockRestClientFailure() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any())).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenThrow(new RuntimeException("Connection refused"));
    }

    @Test
    void dispatchPendingDeliveries_on2xx_marksDelivered() {
        mockRestClient2xx();
        WebhookDelivery delivery = pendingDelivery();

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DELIVERED");
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getLastAttemptAt()).isNotNull();
    }

    @Test
    void dispatchPendingDeliveries_onFailure_incrementsAttemptAndSchedulesRetry() {
        mockRestClientFailure();
        WebhookDelivery delivery = pendingDelivery();

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PENDING");
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void dispatchPendingDeliveries_afterMaxAttempts_marksAsFailed() {
        mockRestClientFailure();
        WebhookDelivery delivery = pendingDelivery();
        delivery.setAttemptCount(4); // one more attempt will reach 5 = MAX_ATTEMPTS
        deliveryRepository.save(delivery);

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getAttemptCount()).isEqualTo(5);
    }

    @Test
    void dispatchPendingDeliveries_futureRetryAt_skipped() {
        mockRestClient2xx();
        WebhookDelivery delivery = pendingDelivery();
        delivery.setNextRetryAt(LocalDateTime.now().plusMinutes(10));
        deliveryRepository.save(delivery);

        dispatcherService.dispatchPendingDeliveries();

        // next_retry_at is in the future, so dispatcher should not process it
        WebhookDelivery unchanged = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("PENDING");
        assertThat(unchanged.getAttemptCount()).isEqualTo(0);
    }
}
```

- [ ] **Step 3: Run test — verify it fails**

```bash
./mvnw test -pl . -Dtest=WebhookDispatcherServiceTest -q 2>&1 | tail -20
```
Expected: compilation error (`WebhookDispatcherService` not found).

- [ ] **Step 4: Write WebhookDispatcherService**

```java
package com.example.paymentapi.service;

import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class WebhookDispatcherService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDispatcherService.class);
    static final int MAX_ATTEMPTS = 5;
    static final long BASE_BACKOFF_SECONDS = 30L;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final RestClient webhookRestClient;

    public WebhookDispatcherService(WebhookDeliveryRepository deliveryRepository,
                                     WebhookSubscriptionRepository subscriptionRepository,
                                     RestClient webhookRestClient) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.webhookRestClient = webhookRestClient;
    }

    @Scheduled(fixedDelayString = "${webhook.dispatcher.fixed-delay-ms:30000}")
    public void dispatchPendingDeliveries() {
        List<WebhookDelivery> pending = deliveryRepository.findPendingDeliveries(LocalDateTime.now(), MAX_ATTEMPTS);
        if (pending.isEmpty()) return;
        logger.debug("Dispatching {} pending webhook deliveries", pending.size());
        for (WebhookDelivery delivery : pending) {
            dispatchSingle(delivery);
        }
    }

    @Transactional
    public void dispatchSingle(WebhookDelivery delivery) {
        WebhookSubscription sub = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub == null) {
            delivery.setStatus("FAILED");
            deliveryRepository.save(delivery);
            return;
        }

        int httpStatus = executePost(sub.getTargetUrl(), sub.getBearerToken(), delivery.getPayload());

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(LocalDateTime.now());
        delivery.setResponseStatus(httpStatus == 0 ? null : httpStatus);

        if (httpStatus >= 200 && httpStatus < 300) {
            delivery.setStatus("DELIVERED");
            logger.info("Webhook delivery {} DELIVERED to {}", delivery.getId(), sub.getTargetUrl());
        } else {
            if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
                delivery.setStatus("FAILED");
                logger.warn("Webhook delivery {} permanently FAILED after {} attempts", delivery.getId(), MAX_ATTEMPTS);
            } else {
                long backoffSeconds = BASE_BACKOFF_SECONDS * (1L << delivery.getAttemptCount());
                delivery.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
                logger.debug("Webhook delivery {} retrying in {}s (attempt {})",
                        delivery.getId(), backoffSeconds, delivery.getAttemptCount());
            }
        }

        deliveryRepository.save(delivery);
    }

    /** Package-private: sends a POST and returns the HTTP status code, or 0 on connection failure. */
    int executePost(String targetUrl, String bearerToken, String payloadJson) {
        try {
            byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
            ResponseEntity<Void> response = webhookRestClient.post()
                    .uri(targetUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return response.getStatusCode().value();
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode().value();
        } catch (Exception ex) {
            logger.warn("Webhook POST to {} failed: {}", targetUrl, ex.getMessage());
            return 0;
        }
    }
}
```

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw test -pl . -Dtest=WebhookDispatcherServiceTest -q 2>&1 | tail -20
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/paymentapi/config/WebhookConfig.java \
        src/main/java/com/example/paymentapi/service/WebhookDispatcherService.java \
        src/test/java/com/example/paymentapi/service/WebhookDispatcherServiceTest.java
git commit -m "feat: WebhookDispatcherService with exponential-backoff retry + RestClient"
```

---

## Task 9: WebhookController + SecurityConfig Update (TDD)

**Files:**
- Create: `src/main/java/com/example/paymentapi/controller/WebhookController.java`
- Create: `src/test/java/com/example/paymentapi/controller/WebhookControllerTest.java`
- Modify: `src/main/java/com/example/paymentapi/config/SecurityConfig.java`

- [ ] **Step 1: Write the failing controller test**

```java
package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.*;
import com.example.paymentapi.service.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
class WebhookControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WebhookService webhookService;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        userToken  = obtainToken("user",  "password");
        adminToken = obtainToken("admin", "password");
    }

    private String obtainToken(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private WebhookSubscriptionResponse sampleResponse(String id) {
        return WebhookSubscriptionResponse.builder()
                .id(id)
                .userId(1L)
                .targetUrl("http://example.com/hook")
                .bearerToken("***")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createSubscription_returns201() throws Exception {
        when(webhookService.createSubscription(any(), eq("user"))).thenReturn(sampleResponse("sub-1"));

        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("token")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("sub-1"))
                .andExpect(jsonPath("$.bearerToken").value("***"));
    }

    @Test
    void createSubscription_missingTargetUrl_returns400() throws Exception {
        String body = "{\"bearerToken\":\"tok\",\"eventTypes\":[\"PAYMENT_COMPLETED\"]}";

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSubscription_invalidUrl_returns400() throws Exception {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("not-a-url")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        mockMvc.perform(post("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listSubscriptions_returns200WithList() throws Exception {
        when(webhookService.listSubscriptions(eq("user"), eq(false)))
                .thenReturn(List.of(sampleResponse("sub-1")));

        mockMvc.perform(get("/api/v1/webhooks")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sub-1"));
    }

    @Test
    void getSubscription_notFound_returns404() throws Exception {
        when(webhookService.getSubscription(eq("bad-id"), eq("user"), eq(false)))
                .thenThrow(new NoSuchElementException("not found"));

        mockMvc.perform(get("/api/v1/webhooks/bad-id")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSubscription_returns204() throws Exception {
        doNothing().when(webhookService).deleteSubscription(eq("sub-1"), eq("admin"), eq(true));

        mockMvc.perform(delete("/api/v1/webhooks/sub-1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void getDeliveries_adminOnly_returns200() throws Exception {
        when(webhookService.getDeliveries("sub-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/webhooks/sub-1/deliveries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void getDeliveries_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/sub-1/deliveries")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./mvnw test -pl . -Dtest=WebhookControllerTest -q 2>&1 | tail -20
```
Expected: compilation or mapping errors (`WebhookController` not found, or 404 from security).

- [ ] **Step 3: Write WebhookController**

```java
package com.example.paymentapi.controller;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/webhooks")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@Tag(name = "Webhooks", description = "Webhook subscription management — configure push notifications for payment events")
@SecurityRequirement(name = "bearerAuth")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    @Operation(summary = "Register a new webhook subscription",
               description = "Subscribe to one or more payment event types. Set adminScope=true (ADMIN only) to receive events for all users.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "400", description = "Invalid request or event type"),
        @ApiResponse(responseCode = "403", description = "adminScope=true requires ROLE_ADMIN")
    })
    public ResponseEntity<WebhookSubscriptionResponse> createSubscription(
            @Valid @RequestBody WebhookSubscriptionRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(webhookService.createSubscription(request, principal.getUsername()));
    }

    @GetMapping
    @Operation(summary = "List webhook subscriptions",
               description = "Users see only their own subscriptions. Admins see all.")
    @ApiResponse(responseCode = "200", description = "Subscriptions returned")
    public ResponseEntity<List<WebhookSubscriptionResponse>> listSubscriptions(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(webhookService.listSubscriptions(principal.getUsername(), isAdmin(principal)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a webhook subscription by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscription found"),
        @ApiResponse(responseCode = "403", description = "Subscription belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    public ResponseEntity<WebhookSubscriptionResponse> getSubscription(
            @Parameter(description = "Subscription ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(webhookService.getSubscription(id, principal.getUsername(), isAdmin(principal)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a webhook subscription",
               description = "All fields in the request body replace existing values. Set active=false to soft-disable.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscription updated"),
        @ApiResponse(responseCode = "400", description = "Invalid event type"),
        @ApiResponse(responseCode = "403", description = "Ownership or adminScope violation"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    public ResponseEntity<WebhookSubscriptionResponse> updateSubscription(
            @Parameter(description = "Subscription ID") @PathVariable String id,
            @Valid @RequestBody WebhookSubscriptionRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(webhookService.updateSubscription(id, request, principal.getUsername(), isAdmin(principal)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a webhook subscription")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Deleted"),
        @ApiResponse(responseCode = "403", description = "Subscription belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    public ResponseEntity<Void> deleteSubscription(
            @Parameter(description = "Subscription ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        webhookService.deleteSubscription(id, principal.getUsername(), isAdmin(principal));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deliveries")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get delivery history for a subscription (admin only)",
               description = "Returns all delivery attempts for the given subscription ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery list returned"),
        @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN")
    })
    public ResponseEntity<List<WebhookDeliveryResponse>> getDeliveries(
            @Parameter(description = "Subscription ID") @PathVariable String id) {
        return ResponseEntity.ok(webhookService.getDeliveries(id));
    }

    private boolean isAdmin(UserDetails principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
```

- [ ] **Step 4: Add webhook authorization rule to SecurityConfig**

In `src/main/java/com/example/paymentapi/config/SecurityConfig.java`, inside the `.authorizeHttpRequests(auth -> auth` block, add the webhook rule **before** the `.anyRequest().authenticated()` line:

```java
                .requestMatchers("/api/v1/webhooks/**").hasAnyRole("USER", "ADMIN")
```

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw test -pl . -Dtest=WebhookControllerTest -q 2>&1 | tail -20
```
Expected: `Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/paymentapi/controller/WebhookController.java \
        src/main/java/com/example/paymentapi/config/SecurityConfig.java \
        src/test/java/com/example/paymentapi/controller/WebhookControllerTest.java
git commit -m "feat: WebhookController CRUD endpoints + delivery history + SecurityConfig rule"
```

---

## Task 10: BDD Feature Files + WebhookSteps + Test Echo Endpoint

**Files:**
- Create: `src/test/java/com/example/paymentapi/controller/WebhookTestController.java`
- Create: `src/test/resources/features/webhooks/webhook_registration.feature`
- Create: `src/test/resources/features/webhooks/webhook_delivery.feature`
- Create: `src/test/java/com/example/paymentapi/bdd/steps/WebhookSteps.java`

The `WebhookTestController` is a `@Profile("test")` REST controller that acts as the webhook target URL in BDD delivery tests. The BDD delivery test calls `dispatcherService.dispatchPendingDeliveries()` directly via `@Autowired` in the step definition (since `@Scheduled` is disabled in tests). `Thread.sleep(500)` is used to let the `@Async` listener finish before dispatching.

- [ ] **Step 1: Write WebhookTestController (test-scoped echo endpoint)**

```java
package com.example.paymentapi.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP endpoint that acts as a webhook target URL in BDD delivery scenarios.
 * Active only with the "test" profile — never deployed to production.
 */
@RestController
@Profile("test")
@RequestMapping("/test")
public class WebhookTestController {

    @PostMapping("/webhook-echo")
    public ResponseEntity<String> echoWebhook(@RequestBody String body) {
        return ResponseEntity.ok("received");
    }
}
```

- [ ] **Step 2: Write webhook_registration.feature**

```gherkin
Feature: Webhook Subscription Management
  As an authenticated user
  I want to manage webhook subscriptions
  So that I receive push notifications when my payment status changes

  Background:
    Given I am logged in as "user" with password "password"

  Scenario: Register and retrieve a webhook subscription
    When I register a webhook for events "PAYMENT_COMPLETED,PAYMENT_FAILED" pointing to "http://example.com/hook" with token "secret"
    Then the response status is 201
    And the response contains a webhook subscription with targetUrl "http://example.com/hook"
    And the response bearerToken is masked as "***"

  Scenario: List my webhook subscriptions
    When I register a webhook for events "PAYMENT_CREATED" pointing to "http://example.com/hook1" with token "tok1"
    And I register a webhook for events "PAYMENT_FAILED" pointing to "http://example.com/hook2" with token "tok2"
    When I list my webhook subscriptions
    Then the response status is 200
    And the response contains at least 2 subscriptions

  Scenario: Update a webhook subscription
    When I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/old" with token "old-token"
    And I save the subscription ID
    When I update the subscription with targetUrl "http://example.com/new" events "PAYMENT_FAILED" and active "true"
    Then the response status is 200
    And the response contains a webhook subscription with targetUrl "http://example.com/new"

  Scenario: Delete a webhook subscription
    When I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/hook" with token "tok"
    And I save the subscription ID
    When I delete the webhook subscription
    Then the response status is 204

  Scenario: Cannot register adminScope subscription as regular user
    When I try to register an adminScope webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/hook"
    Then the response status is 403

  Scenario: Cannot access another user's subscription
    Given I am logged in as "admin" with password "password"
    When I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/admin-hook" with token "admin-tok"
    And I save the subscription ID
    Given I am logged in as "user" with password "password"
    When I try to get the saved subscription
    Then the response status is 403
```

- [ ] **Step 3: Write webhook_delivery.feature**

```gherkin
Feature: Webhook Delivery
  As an authenticated user with a registered webhook
  I want my webhook to receive deliveries when payments change status
  So that my system can react to payment events in real time

  Background:
    Given I am logged in as "user" with password "password"

  Scenario: Delivery is queued after a payment completes
    Given I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://localhost/test/webhook-echo" with token "tok"
    And I save the subscription ID
    When I create a payment for 50 USD from "ACC001" to "ACC002"
    And I wait 500ms for async processing
    Then the webhook subscription has at least 1 pending or delivered delivery

  Scenario: Inactive subscription does not receive deliveries
    Given I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://localhost/test/webhook-echo" with token "tok" and active false
    And I save the subscription ID
    When I create a payment for 50 USD from "ACC001" to "ACC002"
    And I wait 500ms for async processing
    Then the webhook subscription has 0 deliveries
```

- [ ] **Step 4: Write WebhookSteps**

```java
package com.example.paymentapi.bdd.steps;

import com.example.paymentapi.bdd.ScenarioContext;
import com.example.paymentapi.service.WebhookDispatcherService;
import io.cucumber.java.en.*;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class WebhookSteps {

    @Autowired private ScenarioContext ctx;
    @Autowired private WebhookDispatcherService dispatcherService;

    // ── Authentication ──────────────────────────────────────────────────────────

    @Given("I am logged in as {string} with password {string}")
    public void loginAs(String username, String password) {
        String token = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .extract().path("token");
        ctx.setAuthToken(token);
    }

    // ── Subscription registration ───────────────────────────────────────────────

    @When("I register a webhook for events {string} pointing to {string} with token {string}")
    public void registerWebhook(String events, String url, String token) {
        String[] parts = events.split(",");
        StringBuilder types = new StringBuilder("[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) types.append(",");
            types.append("\"").append(parts[i].trim()).append("\"");
        }
        types.append("]");

        String body = """
                {"targetUrl":"%s","bearerToken":"%s","eventTypes":%s,"adminScope":false,"active":true}
                """.formatted(url, token, types);

        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/webhooks");
        ctx.setLastResponse(resp);
        if (resp.getStatusCode() == 201) {
            ctx.setWebhookSubscriptionId(resp.path("id"));
        }
    }

    @Given("I register a webhook for events {string} pointing to {string} with token {string} and active false")
    public void registerInactiveWebhook(String events, String url, String token) {
        String body = """
                {"targetUrl":"%s","bearerToken":"%s","eventTypes":["%s"],"adminScope":false,"active":false}
                """.formatted(url, token, events);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/webhooks");
        ctx.setLastResponse(resp);
        if (resp.getStatusCode() == 201) {
            ctx.setWebhookSubscriptionId(resp.path("id"));
        }
    }

    @When("I try to register an adminScope webhook for events {string} pointing to {string}")
    public void registerAdminScopeWebhook(String events, String url) {
        String body = """
                {"targetUrl":"%s","bearerToken":"tok","eventTypes":["%s"],"adminScope":true,"active":true}
                """.formatted(url, events);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/webhooks");
        ctx.setLastResponse(resp);
    }

    @And("I save the subscription ID")
    public void saveSubscriptionId() {
        // Already saved in registerWebhook if status was 201
    }

    // ── List / Get / Update / Delete ────────────────────────────────────────────

    @When("I list my webhook subscriptions")
    public void listWebhookSubscriptions() {
        Response resp = given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/webhooks");
        ctx.setLastResponse(resp);
    }

    @When("I update the subscription with targetUrl {string} events {string} and active {string}")
    public void updateSubscription(String url, String events, String active) {
        String body = """
                {"targetUrl":"%s","bearerToken":"updated-tok","eventTypes":["%s"],"adminScope":false,"active":%s}
                """.formatted(url, events, active);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .patch("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId());
        ctx.setLastResponse(resp);
    }

    @When("I delete the webhook subscription")
    public void deleteWebhookSubscription() {
        Response resp = given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .delete("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId());
        ctx.setLastResponse(resp);
    }

    @When("I try to get the saved subscription")
    public void tryGetSavedSubscription() {
        Response resp = given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId());
        ctx.setLastResponse(resp);
    }

    // ── Response assertions ─────────────────────────────────────────────────────

    @Then("the response status is {int}")
    public void assertResponseStatus(int expectedStatus) {
        assertThat(ctx.getLastResponse().getStatusCode(), equalTo(expectedStatus));
    }

    @Then("the response contains a webhook subscription with targetUrl {string}")
    public void assertTargetUrl(String expectedUrl) {
        assertThat(ctx.getLastResponse().path("targetUrl"), equalTo(expectedUrl));
    }

    @Then("the response bearerToken is masked as {string}")
    public void assertBearerTokenMasked(String expected) {
        assertThat(ctx.getLastResponse().path("bearerToken"), equalTo(expected));
    }

    @Then("the response contains at least {int} subscriptions")
    public void assertAtLeastSubscriptions(int minCount) {
        int actual = ((java.util.List<?>) ctx.getLastResponse().path("")).size();
        assertThat(actual, greaterThanOrEqualTo(minCount));
    }

    // ── Delivery assertions ─────────────────────────────────────────────────────

    @When("I create a payment for {int} {word} from {string} to {string}")
    public void createPaymentForDelivery(int amount, String currency, String src, String dst) {
        String body = """
                {"sourceAccount":"%s","destinationAccount":"%s","amount":%d,"currency":"%s"}
                """.formatted(src, dst, amount, currency);
        Response resp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/payments");
        ctx.setLastResponse(resp);
    }

    @And("I wait {int}ms for async processing")
    public void waitForAsync(int millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Then("the webhook subscription has at least {int} pending or delivered delivery")
    public void assertAtLeastOneDelivery(int minCount) {
        // Query admin delivery endpoint using admin token
        String adminToken = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}")
            .when()
                .post("/api/v1/auth/login")
            .then().statusCode(200).extract().path("token");

        Response resp = given()
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId() + "/deliveries");
        assertThat(resp.getStatusCode(), equalTo(200));
        int count = ((java.util.List<?>) resp.path("")).size();
        assertThat(count, greaterThanOrEqualTo(minCount));
    }

    @Then("the webhook subscription has {int} deliveries")
    public void assertDeliveryCount(int expectedCount) {
        String adminToken = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}")
            .when()
                .post("/api/v1/auth/login")
            .then().statusCode(200).extract().path("token");

        Response resp = given()
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/webhooks/" + ctx.getWebhookSubscriptionId() + "/deliveries");
        assertThat(resp.getStatusCode(), equalTo(200));
        int count = ((java.util.List<?>) resp.path("")).size();
        assertThat(count, equalTo(expectedCount));
    }
}
```

- [ ] **Step 5: Add `webhookSubscriptionId` to ScenarioContext**

In `src/test/java/com/example/paymentapi/bdd/ScenarioContext.java`, add:

```java
    private String webhookSubscriptionId;

    public String getWebhookSubscriptionId()           { return webhookSubscriptionId; }
    public void   setWebhookSubscriptionId(String id)  { this.webhookSubscriptionId = id; }
```

- [ ] **Step 6: Fix the delivery feature — use runtime port for targetUrl**

The `webhook_delivery.feature` uses `http://localhost/test/webhook-echo`. In BDD tests, the server runs on a random port. Update `WebhookSteps` to have a `@Autowired @LocalServerPort int port` field and use `http://localhost:{port}/test/webhook-echo` as the URL. Replace the hard-coded URL in the step:

Add to `WebhookSteps`:
```java
    @org.springframework.beans.factory.annotation.Value("${local.server.port:8080}")
    private int port;
```

And update the delivery feature to pass `"ECHO"` as the URL sentinel, and resolve it in the step:

In the step `registerWebhook`, detect `"ECHO"` and replace with `http://localhost:` + port + `/test/webhook-echo`:
```java
    if ("ECHO".equals(url)) url = "http://localhost:" + port + "/test/webhook-echo";
```

Update `webhook_delivery.feature` to use `"ECHO"` instead of the hard-coded URL:
```gherkin
    Given I register a webhook for events "PAYMENT_COMPLETED" pointing to "ECHO" with token "tok"
```
And similarly for the inactive subscription scenario.

- [ ] **Step 7: Compile check**

```bash
./mvnw compile -q && ./mvnw test-compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/example/paymentapi/controller/WebhookTestController.java \
        src/test/resources/features/webhooks/ \
        src/test/java/com/example/paymentapi/bdd/steps/WebhookSteps.java \
        src/test/java/com/example/paymentapi/bdd/ScenarioContext.java
git commit -m "feat: BDD webhook feature files, WebhookSteps, and test echo endpoint"
```

---

## Task 11: Full Test Run + Verification

**Files:** none new — run existing + new tests.

- [ ] **Step 1: Run the full test suite**

```bash
./mvnw verify -q 2>&1 | tail -40
```
Expected: `BUILD SUCCESS`, all tests pass, JaCoCo ≥ 75% line coverage.

- [ ] **Step 2: If BDD webhook scenarios fail, diagnose**

Run just the webhook BDD tests:
```bash
./mvnw verify -q -Dcucumber.filter.tags="@webhook" 2>&1 | tail -40
```
If `@webhook` tags don't exist, run by feature file:
```bash
./mvnw verify -q 2>&1 | grep -E "(webhook|FAIL|ERROR)" | head -30
```

Common issues:
- `@Async` delivery not created by the time dispatcher runs: increase `Thread.sleep` from 500 to 1000ms.
- `NoSuchElementException` on `ctx.getWebhookSubscriptionId()`: ensure the `save the subscription ID` step runs after a 201.
- `400 on targetUrl`: The `@URL` annotation requires `http://` or `https://`. Ensure the port-based URL includes the protocol.

- [ ] **Step 3: Update project_state memory**

Update `memory/project_state.md` to reflect new test count and the webhook feature added.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat: webhook subscriptions — complete implementation with BDD + unit tests"
```

---

## Self-Review Against Spec

| Spec Requirement | Covered By Task |
|---|---|
| V10 webhook_subscriptions table | Task 1 |
| V11 webhook_deliveries table | Task 1 |
| WebhookEventType enum (7 types) | Task 2 |
| WebhookSubscription entity with all columns | Task 3 |
| WebhookDelivery entity with all columns | Task 3 |
| WebhookSubscriptionRepository queries | Task 4 |
| WebhookDeliveryRepository polling query | Task 4 |
| WebhookSubscriptionRequest validation (`@URL`, `@NotBlank`, `@NotEmpty`) | Task 5 |
| bearerToken masked in response | Tasks 5, 6 |
| WebhookService CRUD + ownership checks | Task 6 |
| adminScope blocked for non-admin callers | Task 6 |
| Invalid event type → 400 | Task 6 |
| NoSuchElementException → 404 | Task 6 |
| PaymentEvent POJO | Task 7 |
| WebhookEventListener @TransactionalEventListener(AFTER_COMMIT) + @Async | Task 7 |
| Fan-out: user-scoped + admin-scoped matching | Task 7 |
| PAYMENT_STATUS_CHANGED catch-all | Task 7 |
| Inactive subscriptions skipped | Task 7 |
| PaymentServiceImpl publishes events at create/cancel/status-change/reversal | Task 7 |
| WebhookDispatcherService @Scheduled polling | Task 8 |
| Exponential backoff (30s × 2^attempt) | Task 8 |
| 5 attempts → FAILED | Task 8 |
| 2xx → DELIVERED | Task 8 |
| RestClient with Bearer token | Task 8 |
| POST /api/v1/webhooks CRUD | Task 9 |
| GET /api/v1/webhooks/{id}/deliveries (admin only) | Task 9 |
| SecurityConfig webhook rule | Task 9 |
| BDD registration scenarios | Task 10 |
| BDD delivery scenarios | Task 10 |
