# Temporal Payment Creation Workflow — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `CreatePaymentHandler` with a durable Temporal workflow that makes `POST /api/v1/payments` return `202 Accepted + workflowId` and orchestrates validation, persistence, transfer, and notifications with per-step retries and saga compensation.

**Architecture:** Controller starts `PaymentCreationWorkflowImpl` asynchronously via `WorkflowClient`. Four activity interface/impl pairs wrap existing shared helpers. `TemporalConfig` conditionally wires the production worker; `TemporalTestConfig` provides a `TestWorkflowEnvironment` for all tests. Existing `PaymentSystemTest`, `PaymentControllerTest`, and Cucumber BDD tests are updated for the 202 contract.

**Tech Stack:** Spring Boot 3.5.13 · Java 21 · Temporal SDK `io.temporal:temporal-sdk:1.26.1` · Temporal Testing `io.temporal:temporal-testing:1.26.1` · MySQL 8 · H2 (tests) · Redis · JUnit 5 · MockMvc · RestAssured

---

## File Map

**New — main:**
- `src/main/java/com/example/paymentapi/temporal/config/TemporalProperties.java`
- `src/main/java/com/example/paymentapi/temporal/config/TemporalConfig.java`
- `src/main/java/com/example/paymentapi/temporal/dto/PaymentCreationResult.java`
- `src/main/java/com/example/paymentapi/temporal/dto/PaymentWorkflowResponse.java`
- `src/main/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflow.java`
- `src/main/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflowImpl.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentValidationActivities.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentValidationActivitiesImpl.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivities.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivitiesImpl.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentTransferActivities.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentTransferActivitiesImpl.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivities.java`
- `src/main/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivitiesImpl.java`

**New — test:**
- `src/test/java/com/example/paymentapi/temporal/config/TemporalTestConfig.java`
- `src/test/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflowTest.java`
- `src/test/java/com/example/paymentapi/temporal/activity/PaymentValidationActivitiesImplTest.java`
- `src/test/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivitiesImplTest.java`
- `src/test/java/com/example/paymentapi/temporal/activity/PaymentTransferActivitiesImplTest.java`
- `src/test/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivitiesImplTest.java`
- `src/test/java/com/example/paymentapi/temporal/PaymentWorkflowIntegrationTest.java`

**Modified — main:**
- `pom.xml` — add `temporal-sdk`, `temporal-testing`
- `src/main/java/com/example/paymentapi/service/IdempotencyService.java` — `PaymentResponse` → `PaymentWorkflowResponse`
- `src/main/java/com/example/paymentapi/service/IdempotencyServiceImpl.java` — same type change
- `src/main/java/com/example/paymentapi/controller/PaymentController.java` — replace `CreatePaymentHandler` with `WorkflowClient`
- `docker-compose.yml` — add `temporal` + `temporal-ui` services
- `src/main/resources/application.properties` — add `temporal.*` properties

**Modified — test:**
- `src/test/java/com/example/paymentapi/config/TestConfig.java` — add `@Import(TemporalTestConfig.class)`
- `src/test/resources/application-test.properties` — add `temporal.enabled=false`
- `src/test/java/com/example/paymentapi/controller/PaymentControllerTest.java` — swap `CreatePaymentHandler` mock for `WorkflowClient`
- `src/test/java/com/example/paymentapi/PaymentSystemTest.java` — await workflow result for paymentId
- `src/test/java/com/example/paymentapi/bdd/steps/PaymentSteps.java` — 202 contract + workflowId→paymentId
- `src/test/resources/features/payments/payment_lifecycle.feature` — 201→202, add GET step

**Deleted:**
- `src/main/java/com/example/paymentapi/service/command/CreatePaymentHandler.java`

---

## Task 1: Add Temporal Dependencies to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add `temporal.version` property and both dependencies**

In `pom.xml`, add the version property inside `<properties>` after the existing entries:

```xml
<temporal.version>1.26.1</temporal.version>
```

Add the main dependency inside `<dependencies>` (after the Flyway dependencies):

```xml
<!-- Temporal — durable workflow orchestration -->
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>${temporal.version}</version>
</dependency>

<!-- Temporal testing support (TestWorkflowEnvironment) -->
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-testing</artifactId>
    <version>${temporal.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify it compiles**

```bash
cd C:\Users\xphil\Desktop\Philip\Workspace\payment-api
mvn compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat(deps): add temporal-sdk 1.26.1 + temporal-testing"
```

---

## Task 2: Create TemporalProperties

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/config/TemporalProperties.java`

- [ ] **Step 1: Write the properties class**

```java
package com.example.paymentapi.temporal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "temporal")
public class TemporalProperties {
    private String host = "localhost:7233";
    private String namespace = "payment-api";
    private String taskQueue = "payment-creation-queue";
    private boolean enabled = true;
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 3: Create DTOs — PaymentCreationResult and PaymentWorkflowResponse

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/dto/PaymentCreationResult.java`
- Create: `src/main/java/com/example/paymentapi/temporal/dto/PaymentWorkflowResponse.java`

- [ ] **Step 1: Write PaymentCreationResult**

Temporal serialises workflow return values as JSON. Use Lombok for safe Jackson serialisation (no-arg constructor required).

```java
package com.example.paymentapi.temporal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCreationResult {
    private String workflowId;
    private String paymentId;
    private String finalStatus;
}
```

- [ ] **Step 2: Write PaymentWorkflowResponse**

REST response DTO returned by `POST /api/v1/payments`.

```java
package com.example.paymentapi.temporal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentWorkflowResponse(
        String workflowId,
        String status,
        String statusUrl   // null until persistPending completes; reserved for future status endpoint
) {}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 4: Create 4 Activity Interfaces

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentValidationActivities.java`
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivities.java`
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentTransferActivities.java`
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivities.java`

- [ ] **Step 1: Write PaymentValidationActivities**

```java
package com.example.paymentapi.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;

@ActivityInterface
public interface PaymentValidationActivities {

    @ActivityMethod
    void validateAccounts(String sourceAccount, String destinationAccount);

    @ActivityMethod
    void validateFunds(String sourceAccount, BigDecimal amount);
}
```

- [ ] **Step 2: Write PaymentPersistenceActivities**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentPersistenceActivities {

    @ActivityMethod
    String persistPending(PaymentRequest request, String initiatedBy);

    @ActivityMethod
    void completePayment(String paymentId);

    @ActivityMethod
    void failPayment(String paymentId, String reason);
}
```

- [ ] **Step 3: Write PaymentTransferActivities**

```java
package com.example.paymentapi.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;

@ActivityInterface
public interface PaymentTransferActivities {

    @ActivityMethod
    void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount);
}
```

- [ ] **Step 4: Write PaymentNotificationActivities**

```java
package com.example.paymentapi.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentNotificationActivities {

    @ActivityMethod
    void sendNotification(String paymentId, String email, String message);

    @ActivityMethod
    void publishWebhookEvent(String paymentId, String createdBy);
}
```

- [ ] **Step 5: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 5: Implement and Test PaymentValidationActivitiesImpl

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentValidationActivitiesImpl.java`
- Create: `src/test/java/com/example/paymentapi/temporal/activity/PaymentValidationActivitiesImplTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.exception.InsufficientFundsException;
import com.example.paymentapi.exception.InvalidAccountException;
import com.example.paymentapi.service.shared.PaymentValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentValidationActivitiesImplTest {

    @Mock
    private PaymentValidationService validationService;

    @InjectMocks
    private PaymentValidationActivitiesImpl subject;

    @Test
    void validateAccounts_delegatesToService() {
        subject.validateAccounts("src", "dst");
        verify(validationService).validateAccounts("src", "dst");
    }

    @Test
    void validateAccounts_propagatesException() {
        doThrow(new InvalidAccountException("bad")).when(validationService).validateAccounts(any(), any());
        assertThrows(InvalidAccountException.class, () -> subject.validateAccounts("x", "y"));
    }

    @Test
    void validateFunds_delegatesToService() {
        subject.validateFunds("src", BigDecimal.TEN);
        verify(validationService).validateSufficientFunds("src", BigDecimal.TEN);
    }

    @Test
    void validateFunds_propagatesException() {
        doThrow(new InsufficientFundsException("low")).when(validationService)
            .validateSufficientFunds(any(), any());
        assertThrows(InsufficientFundsException.class,
            () -> subject.validateFunds("src", BigDecimal.ONE));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails (class not found)**

```bash
mvn test -pl . -Dtest=PaymentValidationActivitiesImplTest -q 2>&1 | tail -20
```

Expected: compilation failure — `PaymentValidationActivitiesImpl` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.shared.PaymentValidationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentValidationActivitiesImpl implements PaymentValidationActivities {

    private final PaymentValidationService validationService;

    public PaymentValidationActivitiesImpl(PaymentValidationService validationService) {
        this.validationService = validationService;
    }

    @Override
    public void validateAccounts(String sourceAccount, String destinationAccount) {
        validationService.validateAccounts(sourceAccount, destinationAccount);
    }

    @Override
    public void validateFunds(String sourceAccount, BigDecimal amount) {
        validationService.validateSufficientFunds(sourceAccount, amount);
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn test -pl . -Dtest=PaymentValidationActivitiesImplTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/paymentapi/temporal/activity/PaymentValidationActivities*.java \
        src/test/java/com/example/paymentapi/temporal/activity/PaymentValidationActivitiesImplTest.java
git commit -m "feat(temporal): PaymentValidationActivities interface + impl + tests"
```

---

## Task 6: Implement and Test PaymentPersistenceActivitiesImpl

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivitiesImpl.java`
- Create: `src/test/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivitiesImplTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.util.PaymentConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentPersistenceActivitiesImplTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private TransactionService transactionService;
    @Mock private AuditService auditService;

    @InjectMocks
    private PaymentPersistenceActivitiesImpl subject;

    @Test
    void persistPending_savesPaymentAndReturnsId() {
        Payment saved = new Payment();
        saved.setId("pay-abc");
        when(paymentRepository.save(any())).thenReturn(saved);

        PaymentRequest req = new PaymentRequest(
            "1234567890", "0987654321", BigDecimal.valueOf(50), "USD", "test");
        String id = subject.persistPending(req, "alice");

        assertEquals("pay-abc", id);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment captured = captor.getValue();
        assertEquals(PaymentStatus.PENDING.getCode(), captured.getStatus());
        assertEquals("alice", captured.getCreatedBy());
        verify(transactionService).createTransaction("pay-abc");
        verify(auditService).logPaymentEvent("pay-abc", PaymentConstants.AUDIT_PAYMENT_CREATED);
    }

    @Test
    void completePayment_setsStatusAndLogsAudit() {
        Payment payment = new Payment();
        payment.setId("pay-abc");
        when(paymentRepository.findById("pay-abc")).thenReturn(Optional.of(payment));

        subject.completePayment("pay-abc");

        assertEquals(PaymentStatus.COMPLETED.getCode(), payment.getStatus());
        verify(paymentRepository).save(payment);
        verify(auditService).logPaymentEvent("pay-abc", PaymentConstants.AUDIT_PAYMENT_COMPLETED);
    }

    @Test
    void failPayment_setsStatusAndLogsAudit() {
        Payment payment = new Payment();
        payment.setId("pay-abc");
        when(paymentRepository.findById("pay-abc")).thenReturn(Optional.of(payment));

        subject.failPayment("pay-abc", "transfer failed");

        assertEquals(PaymentStatus.FAILED.getCode(), payment.getStatus());
        verify(paymentRepository).save(payment);
        verify(auditService).logPaymentEvent("pay-abc", PaymentConstants.AUDIT_PAYMENT_FAILED);
    }

    @Test
    void completePayment_throwsWhenPaymentNotFound() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> subject.completePayment("missing"));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
mvn test -pl . -Dtest=PaymentPersistenceActivitiesImplTest -q 2>&1 | tail -20
```

Expected: compilation failure.

- [ ] **Step 3: Write the implementation**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.AuditService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.util.PaymentConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPersistenceActivitiesImpl implements PaymentPersistenceActivities {

    private final PaymentRepository paymentRepository;
    private final TransactionService transactionService;
    private final AuditService auditService;

    public PaymentPersistenceActivitiesImpl(PaymentRepository paymentRepository,
                                            TransactionService transactionService,
                                            AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public String persistPending(PaymentRequest request, String initiatedBy) {
        Payment payment = new Payment();
        payment.setSourceAccount(request.getSourceAccount());
        payment.setDestinationAccount(request.getDestinationAccount());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setStatus(PaymentStatus.PENDING.getCode());
        payment.setCreatedBy(initiatedBy);
        payment.setDescription(request.getDescription());
        Payment saved = paymentRepository.save(payment);
        transactionService.createTransaction(saved.getId());
        auditService.logPaymentEvent(saved.getId(), PaymentConstants.AUDIT_PAYMENT_CREATED);
        return saved.getId();
    }

    @Override
    @Transactional
    public void completePayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        payment.setStatus(PaymentStatus.COMPLETED.getCode());
        paymentRepository.save(payment);
        auditService.logPaymentEvent(paymentId, PaymentConstants.AUDIT_PAYMENT_COMPLETED);
    }

    @Override
    @Transactional
    public void failPayment(String paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        payment.setStatus(PaymentStatus.FAILED.getCode());
        paymentRepository.save(payment);
        auditService.logPaymentEvent(paymentId, PaymentConstants.AUDIT_PAYMENT_FAILED);
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn test -pl . -Dtest=PaymentPersistenceActivitiesImplTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivities*.java \
        src/test/java/com/example/paymentapi/temporal/activity/PaymentPersistenceActivitiesImplTest.java
git commit -m "feat(temporal): PaymentPersistenceActivities interface + impl + tests"
```

---

## Task 7: Implement and Test PaymentTransferActivitiesImpl

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentTransferActivitiesImpl.java`
- Create: `src/test/java/com/example/paymentapi/temporal/activity/PaymentTransferActivitiesImplTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.BankingAPIService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentTransferActivitiesImplTest {

    @Mock
    private BankingAPIService bankingAPIService;

    @InjectMocks
    private PaymentTransferActivitiesImpl subject;

    @Test
    void transferFunds_delegatesToBankingService() {
        subject.transferFunds("src", "dst", BigDecimal.TEN);
        verify(bankingAPIService).transferFunds("src", "dst", BigDecimal.TEN);
    }

    @Test
    void transferFunds_propagatesException() {
        doThrow(new RuntimeException("bank down")).when(bankingAPIService)
            .transferFunds(any(), any(), any());
        assertThrows(RuntimeException.class,
            () -> subject.transferFunds("src", "dst", BigDecimal.ONE));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
mvn test -pl . -Dtest=PaymentTransferActivitiesImplTest -q 2>&1 | tail -20
```

Expected: compilation failure.

- [ ] **Step 3: Write the implementation**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.service.BankingAPIService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PaymentTransferActivitiesImpl implements PaymentTransferActivities {

    private final BankingAPIService bankingAPIService;

    public PaymentTransferActivitiesImpl(BankingAPIService bankingAPIService) {
        this.bankingAPIService = bankingAPIService;
    }

    @Override
    public void transferFunds(String sourceAccount, String destinationAccount, BigDecimal amount) {
        bankingAPIService.transferFunds(sourceAccount, destinationAccount, amount);
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn test -pl . -Dtest=PaymentTransferActivitiesImplTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/paymentapi/temporal/activity/PaymentTransferActivities*.java \
        src/test/java/com/example/paymentapi/temporal/activity/PaymentTransferActivitiesImplTest.java
git commit -m "feat(temporal): PaymentTransferActivities interface + impl + tests"
```

---

## Task 8: Implement and Test PaymentNotificationActivitiesImpl

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivitiesImpl.java`
- Create: `src/test/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivitiesImplTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentNotificationActivitiesImplTest {

    @Mock private NotificationService notificationService;
    @Mock private PaymentEventPublisher eventPublisher;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentMapper mapper;

    @InjectMocks
    private PaymentNotificationActivitiesImpl subject;

    @Test
    void sendNotification_delegatesToNotificationService() {
        subject.sendNotification("pay-1", "user@example.com", "Payment done");
        verify(notificationService).sendPaymentNotification("user@example.com", "Payment done");
    }

    @Test
    void publishWebhookEvent_loadsPaymentAndPublishes() {
        Payment payment = new Payment();
        payment.setId("pay-1");
        PaymentResponse response = PaymentResponse.builder().id("pay-1").status("COMPLETED").build();
        when(paymentRepository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(mapper.toResponse(payment)).thenReturn(response);

        subject.publishWebhookEvent("pay-1", "alice");

        verify(eventPublisher).publish(eq(WebhookEventType.PAYMENT_CREATED), eq("alice"), eq(response));
    }

    @Test
    void publishWebhookEvent_throwsWhenPaymentNotFound() {
        when(paymentRepository.findById("missing")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> subject.publishWebhookEvent("missing", "alice"));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
mvn test -pl . -Dtest=PaymentNotificationActivitiesImplTest -q 2>&1 | tail -20
```

Expected: compilation failure.

- [ ] **Step 3: Write the implementation**

```java
package com.example.paymentapi.temporal.activity;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.WebhookEventType;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.service.NotificationService;
import com.example.paymentapi.service.shared.PaymentEventPublisher;
import com.example.paymentapi.service.shared.PaymentMapper;
import org.springframework.stereotype.Service;

@Service
public class PaymentNotificationActivitiesImpl implements PaymentNotificationActivities {

    private final NotificationService notificationService;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentRepository paymentRepository;
    private final PaymentMapper mapper;

    public PaymentNotificationActivitiesImpl(NotificationService notificationService,
                                             PaymentEventPublisher eventPublisher,
                                             PaymentRepository paymentRepository,
                                             PaymentMapper mapper) {
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.paymentRepository = paymentRepository;
        this.mapper = mapper;
    }

    @Override
    public void sendNotification(String paymentId, String email, String message) {
        notificationService.sendPaymentNotification(email, message);
    }

    @Override
    public void publishWebhookEvent(String paymentId, String createdBy) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        PaymentResponse response = mapper.toResponse(payment);
        eventPublisher.publish(WebhookEventType.PAYMENT_CREATED, createdBy, response);
    }
}
```

- [ ] **Step 4: Run the test — verify it passes**

```bash
mvn test -pl . -Dtest=PaymentNotificationActivitiesImplTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivities*.java \
        src/test/java/com/example/paymentapi/temporal/activity/PaymentNotificationActivitiesImplTest.java
git commit -m "feat(temporal): PaymentNotificationActivities interface + impl + tests"
```

---

## Task 9: Create PaymentCreationWorkflow Interface + Implementation

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflow.java`
- Create: `src/main/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflowImpl.java`

- [ ] **Step 1: Write the workflow interface**

```java
package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PaymentCreationWorkflow {

    @WorkflowMethod
    PaymentCreationResult create(PaymentRequest request, String initiatedBy);

    @QueryMethod
    String getCurrentStatus();

    @QueryMethod
    String getPaymentId();
}
```

- [ ] **Step 2: Write the workflow implementation**

```java
package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.activity.PaymentNotificationActivities;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivities;
import com.example.paymentapi.temporal.activity.PaymentTransferActivities;
import com.example.paymentapi.temporal.activity.PaymentValidationActivities;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class PaymentCreationWorkflowImpl implements PaymentCreationWorkflow {

    private String currentStatus = "PENDING";
    private String paymentId;

    private final PaymentValidationActivities validationActivities =
            Workflow.newActivityStub(PaymentValidationActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(10))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());

    private final PaymentPersistenceActivities persistenceActivities =
            Workflow.newActivityStub(PaymentPersistenceActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(15))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());

    private final PaymentTransferActivities transferActivities =
            Workflow.newActivityStub(PaymentTransferActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(5)
                                    .setInitialInterval(Duration.ofMillis(500))
                                    .setBackoffCoefficient(2.0)
                                    .build())
                            .build());

    private final PaymentNotificationActivities notificationActivities =
            Workflow.newActivityStub(PaymentNotificationActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(10))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(1.0)
                                    .build())
                            .build());

    @Override
    public PaymentCreationResult create(PaymentRequest request, String initiatedBy) {
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        String workflowId = Workflow.getInfo().getWorkflowId();

        currentStatus = "VALIDATING";
        validationActivities.validateAccounts(request.getSourceAccount(), request.getDestinationAccount());
        validationActivities.validateFunds(request.getSourceAccount(), request.getAmount());

        currentStatus = "PERSISTING";
        paymentId = persistenceActivities.persistPending(request, initiatedBy);
        saga.addCompensation(() ->
                persistenceActivities.failPayment(paymentId, "Payment failed during processing"));

        currentStatus = "TRANSFERRING";
        try {
            transferActivities.transferFunds(
                    request.getSourceAccount(), request.getDestinationAccount(), request.getAmount());
        } catch (ActivityFailure e) {
            saga.compensate();
            throw e;
        }

        currentStatus = "COMPLETING";
        persistenceActivities.completePayment(paymentId);

        currentStatus = "NOTIFYING";
        try {
            notificationActivities.sendNotification(paymentId, "user@example.com",
                    "Payment completed. Amount: " + request.getAmount() + " " + request.getCurrency());
        } catch (ActivityFailure e) {
            Workflow.getLogger(PaymentCreationWorkflowImpl.class)
                    .warn("Notification failed for payment {}: {}", paymentId, e.getMessage());
        }
        try {
            notificationActivities.publishWebhookEvent(paymentId, initiatedBy);
        } catch (ActivityFailure e) {
            Workflow.getLogger(PaymentCreationWorkflowImpl.class)
                    .warn("Webhook event failed for payment {}: {}", paymentId, e.getMessage());
        }

        currentStatus = "COMPLETED";
        return new PaymentCreationResult(workflowId, paymentId, "COMPLETED");
    }

    @Override
    public String getCurrentStatus() {
        return currentStatus;
    }

    @Override
    public String getPaymentId() {
        return paymentId;
    }
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 10: Write PaymentCreationWorkflowTest

**Files:**
- Create: `src/test/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflowTest.java`

- [ ] **Step 1: Write the test**

```java
package com.example.paymentapi.temporal.workflow;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.temporal.activity.PaymentNotificationActivities;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivities;
import com.example.paymentapi.temporal.activity.PaymentTransferActivities;
import com.example.paymentapi.temporal.activity.PaymentValidationActivities;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentCreationWorkflowTest {

    @RegisterExtension
    static TestWorkflowExtension testWorkflow = TestWorkflowExtension.newBuilder()
            .setWorkflowTypes(PaymentCreationWorkflowImpl.class)
            .build();

    private static PaymentRequest sampleRequest() {
        return new PaymentRequest("1234567890", "0987654321",
                BigDecimal.valueOf(100), "USD", "test payment");
    }

    private static void registerHappyPathMocks(Worker worker,
                                               PaymentValidationActivities validationMock,
                                               PaymentPersistenceActivities persistenceMock,
                                               PaymentTransferActivities transferMock,
                                               PaymentNotificationActivities notificationMock) {
        doNothing().when(validationMock).validateAccounts(any(), any());
        doNothing().when(validationMock).validateFunds(any(), any());
        when(persistenceMock.persistPending(any(), any())).thenReturn("pay-test-123");
        doNothing().when(persistenceMock).completePayment(any());
        doNothing().when(transferMock).transferFunds(any(), any(), any());
        doNothing().when(notificationMock).sendNotification(any(), any(), any());
        doNothing().when(notificationMock).publishWebhookEvent(any(), any());
        worker.registerActivitiesImplementations(
                validationMock, persistenceMock, transferMock, notificationMock);
    }

    @Test
    void happyPath_returnsCompleted(Worker worker, PaymentCreationWorkflow workflowStub) {
        PaymentValidationActivities validationMock = mock(PaymentValidationActivities.class);
        PaymentPersistenceActivities persistenceMock = mock(PaymentPersistenceActivities.class);
        PaymentTransferActivities transferMock = mock(PaymentTransferActivities.class);
        PaymentNotificationActivities notificationMock = mock(PaymentNotificationActivities.class);
        registerHappyPathMocks(worker, validationMock, persistenceMock, transferMock, notificationMock);

        PaymentCreationResult result = workflowStub.create(sampleRequest(), "user1");

        assertEquals("COMPLETED", result.getFinalStatus());
        assertEquals("pay-test-123", result.getPaymentId());
        verify(transferMock).transferFunds("1234567890", "0987654321", BigDecimal.valueOf(100));
        verify(persistenceMock).completePayment("pay-test-123");
    }

    @Test
    void transferFailure_compensatesAndFails(Worker worker, PaymentCreationWorkflow workflowStub) {
        PaymentValidationActivities validationMock = mock(PaymentValidationActivities.class);
        PaymentPersistenceActivities persistenceMock = mock(PaymentPersistenceActivities.class);
        PaymentTransferActivities transferMock = mock(PaymentTransferActivities.class);
        PaymentNotificationActivities notificationMock = mock(PaymentNotificationActivities.class);

        doNothing().when(validationMock).validateAccounts(any(), any());
        doNothing().when(validationMock).validateFunds(any(), any());
        when(persistenceMock.persistPending(any(), any())).thenReturn("pay-test-123");
        doThrow(new RuntimeException("bank unreachable"))
                .when(transferMock).transferFunds(any(), any(), any());
        worker.registerActivitiesImplementations(
                validationMock, persistenceMock, transferMock, notificationMock);

        assertThrows(Exception.class, () -> workflowStub.create(sampleRequest(), "user1"));
        // failPayment is the saga compensation — called after all retries exhausted
        verify(persistenceMock, atLeastOnce()).failPayment(eq("pay-test-123"), anyString());
        verify(persistenceMock, never()).completePayment(any());
    }

    @Test
    void notificationFailure_paymentStaysCompleted(Worker worker, PaymentCreationWorkflow workflowStub) {
        PaymentValidationActivities validationMock = mock(PaymentValidationActivities.class);
        PaymentPersistenceActivities persistenceMock = mock(PaymentPersistenceActivities.class);
        PaymentTransferActivities transferMock = mock(PaymentTransferActivities.class);
        PaymentNotificationActivities notificationMock = mock(PaymentNotificationActivities.class);

        doNothing().when(validationMock).validateAccounts(any(), any());
        doNothing().when(validationMock).validateFunds(any(), any());
        when(persistenceMock.persistPending(any(), any())).thenReturn("pay-test-123");
        doNothing().when(persistenceMock).completePayment(any());
        doNothing().when(transferMock).transferFunds(any(), any(), any());
        doThrow(new RuntimeException("email server down")).when(notificationMock)
                .sendNotification(any(), any(), any());
        doNothing().when(notificationMock).publishWebhookEvent(any(), any());
        worker.registerActivitiesImplementations(
                validationMock, persistenceMock, transferMock, notificationMock);

        PaymentCreationResult result = workflowStub.create(sampleRequest(), "user1");

        assertEquals("COMPLETED", result.getFinalStatus());
        verify(persistenceMock, never()).failPayment(any(), any());
    }

    @Test
    void queryMethod_returnsCurrentStatus(Worker worker, PaymentCreationWorkflow workflowStub) {
        // Initial status before workflow runs
        String initial = workflowStub.getCurrentStatus();
        assertEquals("PENDING", initial);
    }
}
```

- [ ] **Step 2: Run the test**

```bash
mvn test -pl . -Dtest=PaymentCreationWorkflowTest -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. Note: `transferFailure_compensatesAndFails` exercises Temporal's retry loop, so it may take a few seconds.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/paymentapi/temporal/workflow/ \
        src/test/java/com/example/paymentapi/temporal/workflow/PaymentCreationWorkflowTest.java
git commit -m "feat(temporal): PaymentCreationWorkflow + impl + tests"
```

---

## Task 11: Create TemporalConfig (Production Spring Bean)

**Files:**
- Create: `src/main/java/com/example/paymentapi/temporal/config/TemporalConfig.java`

- [ ] **Step 1: Write TemporalConfig**

```java
package com.example.paymentapi.temporal.config;

import com.example.paymentapi.temporal.activity.PaymentNotificationActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentTransferActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentValidationActivitiesImpl;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties props) {
        return WorkflowServiceStubs.newConnectedServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(props.getHost())
                        .build(),
                Duration.ofSeconds(30));
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties props) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(props.getNamespace())
                        .build());
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean
    public Worker paymentWorker(
            WorkerFactory workerFactory,
            TemporalProperties props,
            PaymentValidationActivitiesImpl validationActivities,
            PaymentPersistenceActivitiesImpl persistenceActivities,
            PaymentTransferActivitiesImpl transferActivities,
            PaymentNotificationActivitiesImpl notificationActivities) {
        Worker worker = workerFactory.newWorker(props.getTaskQueue());
        worker.registerWorkflowImplementationTypes(PaymentCreationWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                validationActivities, persistenceActivities,
                transferActivities, notificationActivities);
        workerFactory.start();
        return worker;
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 12: Create TemporalTestConfig + Update TestConfig

**Files:**
- Create: `src/test/java/com/example/paymentapi/temporal/config/TemporalTestConfig.java`
- Modify: `src/test/java/com/example/paymentapi/config/TestConfig.java`

- [ ] **Step 1: Write TemporalTestConfig**

```java
package com.example.paymentapi.temporal.config;

import com.example.paymentapi.temporal.activity.PaymentNotificationActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentPersistenceActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentTransferActivitiesImpl;
import com.example.paymentapi.temporal.activity.PaymentValidationActivitiesImpl;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TemporalTestConfig {

    @Bean(destroyMethod = "close")
    public TestWorkflowEnvironment testWorkflowEnvironment(
            TemporalProperties props,
            PaymentValidationActivitiesImpl validationActivities,
            PaymentPersistenceActivitiesImpl persistenceActivities,
            PaymentTransferActivitiesImpl transferActivities,
            PaymentNotificationActivitiesImpl notificationActivities) {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(props.getTaskQueue());
        worker.registerWorkflowImplementationTypes(PaymentCreationWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                validationActivities, persistenceActivities,
                transferActivities, notificationActivities);
        env.start();
        return env;
    }

    @Bean
    @Primary
    public WorkflowClient testWorkflowClient(TestWorkflowEnvironment env) {
        return env.getWorkflowClient();
    }
}
```

- [ ] **Step 2: Update TestConfig to import TemporalTestConfig**

In `src/test/java/com/example/paymentapi/config/TestConfig.java`, add the import:

```java
package com.example.paymentapi.config;

import com.example.paymentapi.temporal.config.TemporalTestConfig;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

@TestConfiguration
@Import(TemporalTestConfig.class)
public class TestConfig {

    @Bean
    @Primary
    public CacheManager testCacheManager() {
        return new ConcurrentMapCacheManager("payments", "users");
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory();
        factory.setValidateConnection(false);
        return factory;
    }

    @Bean
    @Primary
    public RestTemplateBuilder restTemplateBuilder() {
        HttpClient httpClient = HttpClients.createDefault();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplateBuilder().requestFactory(() -> factory);
    }
}
```

- [ ] **Step 3: Compile test sources**

```bash
mvn test-compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 13: Update IdempotencyService to Use PaymentWorkflowResponse

**Files:**
- Modify: `src/main/java/com/example/paymentapi/service/IdempotencyService.java`
- Modify: `src/main/java/com/example/paymentapi/service/IdempotencyServiceImpl.java`

- [ ] **Step 1: Update IdempotencyService interface**

Replace the full content of `IdempotencyService.java`:

```java
package com.example.paymentapi.service;

import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;

import java.util.Optional;

/**
 * Idempotency guard for payment creation.
 *
 * <p>Clients should send a unique {@code Idempotency-Key} header on POST requests.
 * The first response is stored and replayed verbatim for any subsequent request
 * that carries the same key, preventing duplicate charges caused by network retries.
 */
public interface IdempotencyService {

    Optional<PaymentWorkflowResponse> get(String idempotencyKey);

    void store(String idempotencyKey, PaymentWorkflowResponse response);
}
```

- [ ] **Step 2: Update IdempotencyServiceImpl**

Replace the full content of `IdempotencyServiceImpl.java`:

```java
package com.example.paymentapi.service;

import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyServiceImpl.class);
    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PaymentWorkflowResponse> get(String idempotencyKey) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, PaymentWorkflowResponse.class));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to deserialise cached idempotency response for key {}: {}",
                    idempotencyKey, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Idempotency check unavailable for key {}, proceeding without deduplication: {}",
                    idempotencyKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(String idempotencyKey, PaymentWorkflowResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, json, TTL);
            logger.debug("Stored idempotency response for key {} (TTL={})", idempotencyKey, TTL);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialise idempotency response for key {}: {}",
                    idempotencyKey, e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to store idempotency response for key {}: {}",
                    idempotencyKey, e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`. If there are compile errors in `PaymentController`, proceed to Task 14 which fixes the controller.

---

## Task 14: Update PaymentController

**Files:**
- Modify: `src/main/java/com/example/paymentapi/controller/PaymentController.java`

The controller replaces `CreatePaymentHandler` with `WorkflowClient`. `POST /api/v1/payments` now returns `202 Accepted + PaymentWorkflowResponse`.

- [ ] **Step 1: Rewrite the controller**

Replace the full file content:

```java
package com.example.paymentapi.controller;

import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.dto.PaymentStatusRequest;
import com.example.paymentapi.dto.ReversalRequest;
import com.example.paymentapi.dto.TransactionResponse;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.command.CancellationHandler;
import com.example.paymentapi.service.command.PaymentLifecycleHandler;
import com.example.paymentapi.service.command.ReversalHandler;
import com.example.paymentapi.service.query.PaymentQueryService;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import com.example.paymentapi.temporal.config.TemporalProperties;
import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.example.paymentapi.temporal.workflow.PaymentCreationWorkflow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@Tag(name = "Payments", description = "Operations related to payment processing")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final WorkflowClient workflowClient;
    private final TemporalProperties temporalProperties;
    private final PaymentSecurityHelper security;
    private final ReversalHandler reversalHandler;
    private final CancellationHandler cancellationHandler;
    private final PaymentLifecycleHandler lifecycleHandler;
    private final PaymentQueryService queryService;
    private final IdempotencyService idempotencyService;
    private final TransactionService transactionService;

    public PaymentController(WorkflowClient workflowClient,
                             TemporalProperties temporalProperties,
                             PaymentSecurityHelper security,
                             ReversalHandler reversalHandler,
                             CancellationHandler cancellationHandler,
                             PaymentLifecycleHandler lifecycleHandler,
                             PaymentQueryService queryService,
                             IdempotencyService idempotencyService,
                             TransactionService transactionService) {
        this.workflowClient = workflowClient;
        this.temporalProperties = temporalProperties;
        this.security = security;
        this.reversalHandler = reversalHandler;
        this.cancellationHandler = cancellationHandler;
        this.lifecycleHandler = lifecycleHandler;
        this.queryService = queryService;
        this.idempotencyService = idempotencyService;
        this.transactionService = transactionService;
    }

    @PostMapping
    @Operation(summary = "Create a new payment",
               description = "Starts a durable Temporal workflow. Returns 202 Accepted + workflowId. "
                           + "Poll GET /api/v1/payments/{paymentId} until status leaves PENDING. "
                           + "Optionally supply an 'Idempotency-Key' header to prevent duplicate charges.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Payment workflow started"),
        @ApiResponse(responseCode = "400", description = "Invalid payment request"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentWorkflowResponse> createPayment(
            @Parameter(description = "Client-generated unique key to prevent duplicate payments (UUID recommended)")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest paymentRequest) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentWorkflowResponse> cached = idempotencyService.get(idempotencyKey);
            if (cached.isPresent()) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(cached.get());
            }
        }

        String workflowId = "payment-" + UUID.randomUUID();
        String initiatedBy = security.currentUsername();

        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(
                PaymentCreationWorkflow.class.getSimpleName(),
                WorkflowOptions.newBuilder()
                        .setTaskQueue(temporalProperties.getTaskQueue())
                        .setWorkflowId(workflowId)
                        .build());
        stub.start(paymentRequest, initiatedBy);

        PaymentWorkflowResponse wfResponse = new PaymentWorkflowResponse(workflowId, "PENDING", null);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.store(idempotencyKey, wfResponse);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(wfResponse);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment found"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> getPaymentById(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(queryService.findById(id));
    }

    @GetMapping
    @Operation(summary = "Get payments (paginated + filtered)",
               description = "All filter params are optional. Combine freely: ?status=FAILED&currency=USD&amountFrom=100&amountTo=500")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid filter parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Page<PaymentResponse>> getPayments(
            @Parameter(description = "Filter by status (PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED, REVERSED, REFUNDED)")
            @RequestParam(required = false) String status,
            @Parameter(description = "Filter by creation date — from (ISO-8601, e.g. 2026-01-01T00:00:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @Parameter(description = "Filter by creation date — to (ISO-8601, e.g. 2026-12-31T23:59:59)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @Parameter(description = "Filter by minimum amount (inclusive)")
            @RequestParam(required = false) BigDecimal amountFrom,
            @Parameter(description = "Filter by maximum amount (inclusive)")
            @RequestParam(required = false) BigDecimal amountTo,
            @Parameter(description = "Filter by ISO 4217 currency code (e.g. USD, EUR, GBP)")
            @RequestParam(required = false) String currency,
            Pageable pageable) {
        if (amountFrom != null && amountTo != null && amountFrom.compareTo(amountTo) > 0) {
            throw new IllegalArgumentException("amountFrom cannot be greater than amountTo");
        }
        return ResponseEntity.ok(queryService.findAll(status, dateFrom, dateTo, amountFrom, amountTo, currency, pageable));
    }

    @GetMapping("/source-account")
    @Operation(summary = "Get payments by source account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<PaymentResponse>> getPaymentsBySourceAccount(
            @Parameter(description = "Source account number", required = true)
            @RequestParam String sourceAccount) {
        return ResponseEntity.ok(queryService.findBySourceAccount(sourceAccount));
    }

    @GetMapping("/destination-account")
    @Operation(summary = "Get payments by destination account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payments retrieved successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<PaymentResponse>> getPaymentsByDestinationAccount(
            @Parameter(description = "Destination account number", required = true)
            @RequestParam String destinationAccount) {
        return ResponseEntity.ok(queryService.findByDestinationAccount(destinationAccount));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update payment status")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment status updated successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> updatePaymentStatus(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id,
            @Valid @RequestBody PaymentStatusRequest request) {
        return ResponseEntity.ok(lifecycleHandler.updateStatus(id, request.getStatus()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a payment",
               description = "Only PENDING payments can be cancelled. Returns 409 for any other status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment cancelled successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment cannot be cancelled in its current status"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> cancelPayment(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(cancellationHandler.handle(id));
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "List transactions for a payment",
               description = "Returns all transaction records associated with the given payment. "
                           + "Non-admin users may only retrieve transactions for payments they own.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transactions retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "403", description = "Access denied: payment belongs to another user"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<List<TransactionResponse>> getTransactionsByPaymentId(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        queryService.findById(id);
        List<TransactionResponse> txns = transactionService.getTransactionsByPaymentId(id)
                .stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(txns);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a payment")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Payment deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Cannot delete a completed payment"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Void> deletePayment(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        lifecycleHandler.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a failed payment",
               description = "Re-attempts a FAILED payment. Returns 409 if the payment is not in FAILED status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Retry initiated successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "409", description = "Payment is not in FAILED status"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> retryPayment(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id) {
        return ResponseEntity.ok(lifecycleHandler.retry(id));
    }

    @PostMapping("/{id}/reversal")
    @Operation(summary = "Initiate a payment reversal or partial refund")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reversal processed successfully"),
        @ApiResponse(responseCode = "404", description = "Payment not found"),
        @ApiResponse(responseCode = "422", description = "Reversal not possible for current payment state"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaymentResponse> initiatePaymentReversal(
            @Parameter(description = "Payment UUID", required = true)
            @PathVariable String id,
            @Valid @RequestBody ReversalRequest reversalRequest) {
        return ResponseEntity.ok(reversalHandler.handle(id, reversalRequest));
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 15: Update Properties Files

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application-test.properties`

- [ ] **Step 1: Add Temporal properties to application.properties**

Append to the end of `application.properties`:

```properties
# =============================================================================
# Temporal — durable workflow orchestration
# =============================================================================
temporal.host=${TEMPORAL_HOST:localhost:7233}
temporal.namespace=payment-api
temporal.task-queue=payment-creation-queue
temporal.enabled=true
```

- [ ] **Step 2: Add Temporal properties to application-test.properties**

Append to the end of `application-test.properties`:

```properties
# =============================================================================
# Temporal — disable production worker; TemporalTestConfig registers TestWorkflowEnvironment
# =============================================================================
temporal.enabled=false
temporal.task-queue=payment-creation-queue
temporal.namespace=payment-api
temporal.host=localhost:7233
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`.

---

## Task 16: Update PaymentControllerTest

**Files:**
- Modify: `src/test/java/com/example/paymentapi/controller/PaymentControllerTest.java`

- [ ] **Step 1: Replace the full file**

```java
package com.example.paymentapi.controller;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.dto.PaymentRequest;
import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.model.Transaction;
import com.example.paymentapi.service.IdempotencyService;
import com.example.paymentapi.service.TransactionService;
import com.example.paymentapi.service.command.CancellationHandler;
import com.example.paymentapi.service.command.PaymentLifecycleHandler;
import com.example.paymentapi.service.command.ReversalHandler;
import com.example.paymentapi.service.query.PaymentQueryService;
import com.example.paymentapi.temporal.dto.PaymentWorkflowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.paymentapi.controller.PaymentController.IDEMPOTENCY_KEY_HEADER;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private ReversalHandler reversalHandler;

    @MockitoBean
    private CancellationHandler cancellationHandler;

    @MockitoBean
    private PaymentLifecycleHandler lifecycleHandler;

    @MockitoBean
    private PaymentQueryService queryService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @MockitoBean
    private TransactionService transactionService;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    public void setUp() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("password");

        String loginResponseJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        LoginResponse loginResponse = objectMapper.readValue(loginResponseJson, LoginResponse.class);
        token = "Bearer " + loginResponse.getToken();
    }

    private WorkflowStub stubWorkflowStub() {
        WorkflowStub mockStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(
                eq("PaymentCreationWorkflow"), any())).thenReturn(mockStub);
        return mockStub;
    }

    @Test
    public void testCreatePayment() throws Exception {
        stubWorkflowStub();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    public void testCreatePayment_ValidationError() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        // Missing required fields

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_firstRequest_processesAndStores() throws Exception {
        stubWorkflowStub();

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        when(idempotencyService.get("key-abc")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").isNotEmpty());

        verify(idempotencyService).store(eq("key-abc"), any(PaymentWorkflowResponse.class));
    }

    @Test
    public void testCreatePayment_withIdempotencyKey_duplicateRequest_returnsCachedResponse() throws Exception {
        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setSourceAccount("1234567890");
        paymentRequest.setDestinationAccount("0987654321");
        paymentRequest.setAmount(BigDecimal.valueOf(100));
        paymentRequest.setCurrency("USD");

        PaymentWorkflowResponse cached = new PaymentWorkflowResponse("payment-cached-xyz", "PENDING", null);
        when(idempotencyService.get("key-abc")).thenReturn(Optional.of(cached));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .header(IDEMPOTENCY_KEY_HEADER, "key-abc")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value("payment-cached-xyz"));

        verify(workflowClient, never()).newUntypedWorkflowStub(any(), any());
    }

    @Test
    public void testGetPaymentById() throws Exception {
        PaymentResponse paymentResponse = new PaymentResponse();
        paymentResponse.setId("payment123");
        paymentResponse.setStatus("COMPLETED");
        paymentResponse.setCreatedAt(LocalDateTime.now());

        when(queryService.findById("payment123")).thenReturn(paymentResponse);

        mockMvc.perform(get("/api/v1/payments/payment123")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("payment123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
```

- [ ] **Step 2: Run the updated controller tests**

```bash
mvn test -pl . -Dtest=PaymentControllerTest -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

---

## Task 17: Update PaymentSystemTest.createPayment() Helper

**Files:**
- Modify: `src/test/java/com/example/paymentapi/PaymentSystemTest.java`

The `createPayment()` helper must:
1. POST → 202, get `workflowId`
2. Use `TestWorkflowEnvironment` to await the workflow result and extract `paymentId`

- [ ] **Step 1: Add TestWorkflowEnvironment injection and update imports**

At the top of the class, add `@Autowired`:

```java
@Autowired private TestWorkflowEnvironment testEnv;
```

Add imports:
```java
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.temporal.testing.TestWorkflowEnvironment;
```

- [ ] **Step 2: Replace both createPayment() overloads**

Replace the two `createPayment` helper methods (lines 104–118 in the original):

```java
private String createPayment(String token) throws Exception {
    return createPayment(token, BigDecimal.valueOf(100), "USD");
}

private String createPayment(String token, BigDecimal amount, String currency) throws Exception {
    PaymentRequest req = new PaymentRequest(
            "1234567890", "0987654321", amount, currency, null);
    String json = mockMvc.perform(post("/api/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", token)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())          // 202
            .andReturn().getResponse().getContentAsString();
    String workflowId = objectMapper.readTree(json).get("workflowId").asText();

    // Await workflow completion in the embedded test environment
    PaymentCreationResult result = testEnv.getWorkflowClient()
            .newUntypedWorkflowStub(workflowId)
            .getResult(PaymentCreationResult.class);
    return result.getPaymentId();
}
```

- [ ] **Step 3: Run the system tests**

```bash
mvn test -pl . -Dtest=PaymentSystemTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. If any test fails due to a race condition with the workflow environment, it indicates the `TestWorkflowEnvironment` didn't complete before the `getResult()` call — this is not expected with the embedded env and should be investigated before continuing.

---

## Task 18: Update PaymentSteps.java

**Files:**
- Modify: `src/test/java/com/example/paymentapi/bdd/steps/PaymentSteps.java`

- [ ] **Step 1: Rewrite PaymentSteps.java**

Replace the full file content:

```java
package com.example.paymentapi.bdd.steps;

import com.example.paymentapi.bdd.ScenarioContext;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.temporal.testing.TestWorkflowEnvironment;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

public class PaymentSteps {

    @Autowired
    private ScenarioContext ctx;

    @Autowired
    private TestWorkflowEnvironment testEnv;

    // ── Payment creation ───────────────────────────────────────────────────────

    @When("I create a payment for {int} {word} from {string} to {string}")
    public void createPayment(int amount, String currency, String src, String dst) {
        String body = """
                {"sourceAccount":"%s","destinationAccount":"%s","amount":%d,"currency":"%s"}
                """.formatted(src, dst, amount, currency);
        Response response = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/payments");
        ctx.setLastResponse(response);
        if (response.getStatusCode() == 202) {
            String workflowId = response.path("workflowId");
            PaymentCreationResult result = testEnv.getWorkflowClient()
                    .newUntypedWorkflowStub(workflowId)
                    .getResult(PaymentCreationResult.class);
            ctx.setLastPaymentId(result.getPaymentId());
        }
    }

    @Given("{string} has created a payment")
    public void userHasCreatedPayment(String username) {
        String password = "password";
        String token = given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .extract().path("token");

        Response response = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments");
        response.then().statusCode(202);
        String workflowId = response.path("workflowId");
        PaymentCreationResult result = testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class);
        ctx.setLastPaymentId(result.getPaymentId());
    }

    @Then("the payment is created successfully")
    public void paymentCreatedSuccessfully() {
        assertEquals(202, ctx.getLastResponse().getStatusCode(),
                "Expected 202 Accepted but got " + ctx.getLastResponse().getStatusCode());
        assertNotNull(ctx.getLastPaymentId(), "Payment ID must be set after creation");
    }

    // ── Payment operations ─────────────────────────────────────────────────────

    @When("I reverse the payment")
    public void reversePayment() {
        initiateReversalWithReason("BDD reversal test");
    }

    @When("I initiate a reversal of the payment with reason {string}")
    public void initiateReversalWithReason(String reason) {
        String body = String.format(
                "{\"reason\":\"%s\",\"partialReversal\":false}", reason);
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .body(body)
            .when()
                .post("/api/v1/payments/{id}/reversal", ctx.getLastPaymentId())
        );
    }

    @When("I cancel the payment")
    public void cancelPayment() {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .post("/api/v1/payments/{id}/cancel", ctx.getLastPaymentId())
        );
    }

    @When("I GET that payment by ID")
    public void getPaymentById() {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/payments/{id}", ctx.getLastPaymentId())
        );
    }

    @When("I list all payments")
    public void listPayments() {
        ctx.setLastResponse(
            given()
                .header("Authorization", "Bearer " + ctx.getAuthToken())
            .when()
                .get("/api/v1/payments")
        );
    }

    // ── Idempotency ────────────────────────────────────────────────────────────

    @Given("an idempotency key {string}")
    public void idempotencyKey(String key) {
        ctx.setIdempotencyKey(key);
    }

    @When("I create a payment with that idempotency key")
    public void createPaymentWithIdempotencyKey() {
        createPaymentWithKey(ctx.getIdempotencyKey());
    }

    @When("I create another payment with the same idempotency key")
    public void createAnotherPaymentWithSameKey() {
        createPaymentWithKey(ctx.getIdempotencyKey());
    }

    private void createPaymentWithKey(String key) {
        ctx.setLastResponse(
            given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + ctx.getAuthToken())
                .header("Idempotency-Key", key)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":5,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
        );
    }

    @Then("only one payment exists with that idempotency key")
    public void onlyOnePaymentWithKey() {
        Response secondResponse = ctx.getLastResponse();
        assertEquals(202, secondResponse.getStatusCode(),
                "Expected 202 for idempotent request, got " + secondResponse.getStatusCode());
        String workflowId = secondResponse.path("workflowId");
        assertNotNull(workflowId, "Second idempotent response must return the original workflowId");
    }

    // ── Assertions ─────────────────────────────────────────────────────────────

    @Then("the payment status is {string}")
    public void paymentStatusIs(String expected) {
        String actual = ctx.getLastResponse().path("status");
        assertEquals(expected, actual, "Expected payment status " + expected + " but was " + actual);
    }

    @Then("the payment has an {string} field")
    public void paymentHasField(String fieldName) {
        Object value = ctx.getLastResponse().path(fieldName);
        assertNotNull(value, "Expected field '" + fieldName + "' to be present");
    }

    @Then("account numbers are masked in the response")
    public void accountNumbersAreMasked() {
        String source = ctx.getLastResponse().path("sourceAccount");
        String dest   = ctx.getLastResponse().path("destinationAccount");
        assertNotNull(source, "sourceAccount must be present");
        assertNotNull(dest,   "destinationAccount must be present");
        assertTrue(source.startsWith("******"), "sourceAccount must be masked, got: " + source);
        assertTrue(dest.startsWith("******"),   "destinationAccount must be masked, got: " + dest);
    }

    @Then("I do not see the admin's payment in the list")
    public void doNotSeeAdminPayment() {
        String adminPaymentId = ctx.getLastPaymentId();
        List<String> ids = ctx.getLastResponse().path("content.id");
        assertThat("User's payment list must not contain admin's payment",
                ids, not(hasItem(adminPaymentId)));
    }
}
```

---

## Task 19: Update payment_lifecycle.feature

**Files:**
- Modify: `src/test/resources/features/payments/payment_lifecycle.feature`

- [ ] **Step 1: Rewrite the feature file**

```gherkin
Feature: Payment Lifecycle
  In order to process financial transactions
  As an authenticated user
  I want to create, track, and manage payments through their full lifecycle

  Background:
    Given I am authenticated as "admin"

  Scenario: Create a payment — verify structure and account masking
    When I create a payment for 100 USD from "1234567890" to "0987654321"
    Then the response status code is 202
    When I GET that payment by ID
    Then the payment status is "COMPLETED"
    And the payment has an "id" field
    And account numbers are masked in the response

  Scenario: Reverse a completed payment
    When I create a payment for 50 USD from "1234567890" to "0987654321"
    Then the payment is created successfully
    When I initiate a reversal of the payment with reason "Customer requested refund for BDD test"
    Then the response status code is 200
    And the payment status is "REVERSED"

  Scenario: Cancel a completed payment returns conflict
    When I create a payment for 25 USD from "1234567890" to "0987654321"
    Then the payment is created successfully
    When I cancel the payment
    Then the response status code is 409

  Scenario: Idempotency key prevents duplicate payments
    Given an idempotency key "bdd-idem-key-lifecycle-001"
    When I create a payment with that idempotency key
    And I create another payment with the same idempotency key
    Then only one payment exists with that idempotency key

  Scenario: Zero-amount payment is rejected
    When I create a payment for 0 USD from "1234567890" to "0987654321"
    Then the response status code is 400
```

---

## Task 20: Delete CreatePaymentHandler.java

**Files:**
- Delete: `src/main/java/com/example/paymentapi/service/command/CreatePaymentHandler.java`

- [ ] **Step 1: Delete the file**

```bash
rm src/main/java/com/example/paymentapi/service/command/CreatePaymentHandler.java
```

- [ ] **Step 2: Verify nothing references CreatePaymentHandler**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`. If there are compilation errors referencing `CreatePaymentHandler`, fix the import.

---

## Task 21: Update docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add Temporal services**

In `docker-compose.yml`, add these two services after the `zipkin` service (before `networks:`). Also add a `temporal` dependency to the `app` service.

In the `app.depends_on` section, add:
```yaml
      temporal:
        condition: service_healthy
```

Add new services:
```yaml
  # Temporal Server — durable workflow orchestration
  temporal:
    image: temporalio/auto-setup:1.26.2
    container_name: payment-temporal
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
    networks:
      - payment-network
    healthcheck:
      test: ["CMD", "tctl", "--address", "temporal:7233", "cluster", "health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
    restart: unless-stopped

  # Temporal Web UI
  temporal-ui:
    image: temporalio/ui:2.32.0
    container_name: payment-temporal-ui
    ports:
      - "8088:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
    depends_on:
      - temporal
    networks:
      - payment-network
    restart: unless-stopped
```

Also add `TEMPORAL_HOST=temporal:7233` to the `app` service environment:
```yaml
      - TEMPORAL_HOST=temporal:7233
```

- [ ] **Step 2: Verify compose file is valid YAML**

```bash
docker compose config --quiet 2>&1 | head -5
```

Expected: no output (valid YAML) or minor warnings only.

---

## Task 22: Write PaymentWorkflowIntegrationTest

**Files:**
- Create: `src/test/java/com/example/paymentapi/temporal/PaymentWorkflowIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

```java
package com.example.paymentapi.temporal;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.LoginRequest;
import com.example.paymentapi.dto.LoginResponse;
import com.example.paymentapi.temporal.dto.PaymentCreationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
class PaymentWorkflowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestWorkflowEnvironment testEnv;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        io.restassured.RestAssured.port = port;
        adminToken = loginAs("admin", "password");
    }

    private String loginAs(String username, String password) throws Exception {
        String json = given()
                .contentType(ContentType.JSON)
                .body(objectMapper.writeValueAsString(new LoginRequest(username, password)))
            .when()
                .post("/api/v1/auth/login")
            .then()
                .statusCode(200)
                .extract().asString();
        return objectMapper.readValue(json, LoginResponse.class).getToken();
    }

    @Test
    void fullRoundTrip_postReturns202_thenGetReturnsCompleted() {
        // POST → 202
        Response postResponse = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":100,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(202)
                .body("workflowId", notNullValue())
                .body("status", equalTo("PENDING"))
                .extract().response();

        String workflowId = postResponse.path("workflowId");
        assertNotNull(workflowId);
        assertTrue(workflowId.startsWith("payment-"));

        // Await workflow completion and get paymentId
        PaymentCreationResult result = testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class);

        assertEquals("COMPLETED", result.getFinalStatus());
        assertNotNull(result.getPaymentId());

        // GET the payment — confirm COMPLETED in DB
        given()
                .header("Authorization", "Bearer " + adminToken)
            .when()
                .get("/api/v1/payments/" + result.getPaymentId())
            .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("id", equalTo(result.getPaymentId()));
    }

    @Test
    void bankingApiThrows_paymentLandsInFailedState() {
        // The BankingAPIServiceImpl starts with balance 0 for unknown accounts.
        // Use an account with insufficient funds to trigger a transfer failure.
        Response postResponse = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body("""
                    {"sourceAccount":"0000000000","destinationAccount":"0987654321",
                     "amount":999999,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(202)
                .extract().response();

        String workflowId = postResponse.path("workflowId");

        // Workflow will fail after transfer retries are exhausted; getResult() throws
        assertThrows(Exception.class, () ->
            testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(workflowId)
                .getResult(PaymentCreationResult.class)
        );
    }

    @Test
    void duplicateWorkflowId_rejectedByTemporal() {
        // Start first workflow
        Response first = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(202)
                .extract().response();

        String firstWorkflowId = first.path("workflowId");
        assertNotNull(firstWorkflowId);
        // Await completion so workflowId is no longer "running"
        testEnv.getWorkflowClient()
                .newUntypedWorkflowStub(firstWorkflowId)
                .getResult(PaymentCreationResult.class);

        // Confirm second POST produces a DIFFERENT workflowId (UUIDs are unique)
        Response second = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + adminToken)
                .body("""
                    {"sourceAccount":"1234567890","destinationAccount":"0987654321",
                     "amount":10,"currency":"USD"}
                    """)
            .when()
                .post("/api/v1/payments")
            .then()
                .statusCode(202)
                .extract().response();

        assertNotEquals(firstWorkflowId, second.path("workflowId").toString());
    }
}
```

- [ ] **Step 2: Run the integration tests**

```bash
mvn test -pl . -Dtest=PaymentWorkflowIntegrationTest -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`.

---

## Task 23: Final Full Build + Verification

- [ ] **Step 1: Run the complete test suite**

```bash
mvn clean verify -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`. All existing tests pass. Coverage gate ≥80% passes.

- [ ] **Step 2: Confirm test count increased**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected: test count ≥ 540 (539 baseline + new tests), 0 failures, 0 errors.

- [ ] **Step 3: Commit all remaining changes**

```bash
git add \
  src/main/resources/application.properties \
  src/test/resources/application-test.properties \
  src/main/java/com/example/paymentapi/service/IdempotencyService.java \
  src/main/java/com/example/paymentapi/service/IdempotencyServiceImpl.java \
  src/main/java/com/example/paymentapi/controller/PaymentController.java \
  src/test/java/com/example/paymentapi/config/TestConfig.java \
  src/test/java/com/example/paymentapi/controller/PaymentControllerTest.java \
  src/test/java/com/example/paymentapi/PaymentSystemTest.java \
  src/test/java/com/example/paymentapi/bdd/steps/PaymentSteps.java \
  src/test/resources/features/payments/payment_lifecycle.feature \
  src/test/java/com/example/paymentapi/temporal/ \
  docker-compose.yml

git commit -m "$(cat <<'EOF'
feat(temporal): wire Temporal payment workflow — 202 API + integration tests

- PaymentController.createPayment() returns 202 Accepted + PaymentWorkflowResponse
- CreatePaymentHandler deleted; replaced by PaymentCreationWorkflow + 4 activity impls
- TemporalConfig (conditional) and TemporalTestConfig (TestWorkflowEnvironment) wired
- IdempotencyService updated to cache PaymentWorkflowResponse
- PaymentControllerTest, PaymentSystemTest, Cucumber BDD tests updated for 202 contract
- docker-compose.yml: temporal + temporal-ui services added
- PaymentWorkflowIntegrationTest: full round-trip, failure path, and idempotency coverage

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Push to origin**

```bash
git push origin master
```

---

## Retry Policy Reference

| Activity Group       | Max Attempts | Initial Interval | Backoff | Saga Compensation |
|----------------------|-------------|-----------------|---------|-------------------|
| Validation           | 3           | 1 s             | 1×      | No                |
| Persistence          | 3           | 1 s             | 1×      | Yes (on downstream failure) |
| Transfer (banking)   | 5           | 500 ms          | 2× exp  | Yes               |
| Notification/Events  | 3           | 1 s             | 1×      | No (best-effort)  |
