# ADR-20260415: BDD scenario alignment with synchronous payment completion

**Status:** Accepted  
**Date:** 2026-04-15  
**Deciders:** OMEGA Lifecycle Engineer (automated), PhilipW1222

---

## Context

`BankingAPIServiceImpl` is the in-process banking simulation used in all profiles (local, test, production). It executes `transferFunds()` synchronously within the same HTTP request thread as `createPayment()`. Immediately after a successful transfer, `PaymentServiceImpl.createPayment()` sets the payment status to `COMPLETED` and saves the entity.

This means a payment created via `POST /api/v1/payments` will always return `status: "COMPLETED"` in its 201 response — there is no window during which the status is `PENDING` observable by an API caller.

The original BDD scenarios were written with an asynchronous banking integration in mind:

- `payment_lifecycle.feature` — "Create a payment": asserted `status = "PENDING"` (wrong: always COMPLETED).
- "Cancel a payment": created a payment then immediately cancelled it; since the payment was COMPLETED the cancel returned 409 (only PENDING payments can be cancelled).
- "Reverse a completed payment": called `POST /api/v1/payments/{id}/reverse` — this path does not exist; the correct endpoint is `POST /api/v1/payments/{id}/reversal` with a `ReversalRequest` body.

Three approaches to fix these were considered:

1. **Make the banking service asynchronous** — deferring the `PENDING → COMPLETED` transition to a background thread. This is a significant production change that introduces complexity (async state, race conditions, transactional event ordering) and is deferred until a real external banking API is integrated.
2. **Update BDD scenarios to reflect actual API behaviour** — minimal change, keeps tests honest and passing, documents real contracts.
3. **Add a test-only hook to keep payment in PENDING state** — rejected as it pollutes production code with test-only branches.

---

## Decision

Update BDD feature files and step definitions to reflect the actual behaviour of the synchronous `BankingAPIServiceImpl`:

- "Create a payment" asserts `status = "COMPLETED"`.
- "Cancel a payment" is renamed "Cancel a completed payment returns conflict" and asserts HTTP 409 — this tests the real API contract (COMPLETED payments cannot be cancelled).
- "Reverse a completed payment" uses the correct `POST /api/v1/payments/{id}/reversal` endpoint via a new `initiateReversalWithReason(String reason)` step definition.

A `TODO` comment is added to `BankingAPIServiceImpl` documenting the intention to convert to an async client once a real banking integration is introduced, at which point the BDD scenarios can be reverted to test PENDING status.

---

## Consequences

**Positive:**
- 5 pre-existing Cucumber scenario failures resolved without changing production behaviour.
- BDD scenarios now document the actual API contracts enforced in production.
- The "Cancel completed payment returns 409" scenario improves test coverage of error paths.

**Negative:**
- The PENDING status is no longer tested end-to-end via BDD. Unit tests in `PaymentServiceTest` cover the PENDING→COMPLETED transition at the service layer.

**Future action:**
- When `BankingAPIServiceImpl` is replaced by a real async banking client returning `CompletableFuture<T>`, reintroduce a "Create a payment — verify PENDING status" BDD scenario and remove the COMPLETED assertion from the current scenario.
