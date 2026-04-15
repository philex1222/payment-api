# changePassword UsernameNotFoundException Bug Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `AuthController.changePassword()` returning HTTP 500 instead of 401 when the authenticated user's record no longer exists.

**Architecture:** Add a `UsernameNotFoundException` catch branch (merged with `BadCredentialsException` via multi-catch) in the controller method. Follow the existing error-response pattern already used in the same method. Add a unit test that exercises this new branch.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Security, JUnit 5, Mockito, MockMvc

---

## Files

| Action | Path |
|--------|------|
| Modify | `src/main/java/com/example/paymentapi/controller/AuthController.java` |
| Modify | `src/test/java/com/example/paymentapi/controller/AuthControllerTest.java` |

---

### Task 1: Write the failing test

**Files:**
- Modify: `src/test/java/com/example/paymentapi/controller/AuthControllerTest.java`

- [ ] **Step 1: Add the failing test after the existing `changePassword_wrongCurrentPassword_returns401` test**

Open `src/test/java/com/example/paymentapi/controller/AuthControllerTest.java` and insert this test after line 208 (after `changePassword_wrongCurrentPassword_returns401`):

```java
@Test
@WithMockUser(username = "deleted-user", roles = "USER")
@DisplayName("Change password when user no longer exists returns 401")
void changePassword_userNotFound_returns401() throws Exception {
    when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    doThrow(new UsernameNotFoundException("User not found: deleted-user"))
            .when(userService).changePassword("deleted-user", "oldPass1", "newPass123");

    mockMvc.perform(post("/api/v1/auth/change-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            new ChangePasswordRequest("oldPass1", "newPass123"))))
            .andExpect(status().isUnauthorized());
}
```

`UsernameNotFoundException` is already on the classpath via `spring-security-core` — no import change needed beyond what `AuthControllerTest` already imports (check the import block; add `import org.springframework.security.core.userdetails.UsernameNotFoundException;` if missing).

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd "C:/Users/xphil/Desktop/Philip/Workspace/payment-api/.worktrees/fix/bug-fix"
mvn test -Dtest=AuthControllerTest#changePassword_userNotFound_returns401 -q 2>&1 | tail -20
```

Expected: test **FAILS** with status 500 (or an exception propagation error), confirming the bug exists.

---

### Task 2: Implement the fix

**Files:**
- Modify: `src/main/java/com/example/paymentapi/controller/AuthController.java`

- [ ] **Step 3: Extend the catch block in `changePassword()`**

In `src/main/java/com/example/paymentapi/controller/AuthController.java`, replace lines 155–163:

**Before:**
```java
        } catch (BadCredentialsException e) {
            logger.warn("Password change rejected for user '{}': {}", userDetails.getUsername(), e.getMessage());
            ErrorResponse error = ErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    "Current password is incorrect",
                    "/api/v1/auth/change-password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
```

**After:**
```java
        } catch (UsernameNotFoundException | BadCredentialsException e) {
            logger.warn("Password change rejected for user '{}': {}", userDetails.getUsername(), e.getMessage());
            ErrorResponse error = ErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    "Authentication failed: " + e.getMessage(),
                    "/api/v1/auth/change-password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
```

Add `import org.springframework.security.core.userdetails.UsernameNotFoundException;` to the import block if it is not already present (check the top of the file).

- [ ] **Step 4: Run the new test to verify it now passes**

```bash
mvn test -Dtest=AuthControllerTest#changePassword_userNotFound_returns401 -q 2>&1 | tail -10
```

Expected: **BUILD SUCCESS**, 1 test passing.

- [ ] **Step 5: Run the full `AuthControllerTest` suite to check for regressions**

```bash
mvn test -Dtest=AuthControllerTest -q 2>&1 | tail -10
```

Expected: **BUILD SUCCESS**, all existing tests still passing.

---

### Task 3: Full suite verification and commit

- [ ] **Step 6: Run the full test suite**

```bash
mvn test -q 2>&1 | tail -15
```

Expected: **BUILD SUCCESS** with no failures.

- [ ] **Step 7: Commit**

```bash
cd "C:/Users/xphil/Desktop/Philip/Workspace/payment-api/.worktrees/fix/bug-fix"
git add src/main/java/com/example/paymentapi/controller/AuthController.java \
        src/test/java/com/example/paymentapi/controller/AuthControllerTest.java
git commit -m "fix(auth): return 401 instead of 500 when user not found on password change

UsernameNotFoundException thrown by UserServiceImpl.changePassword() was
uncaught in AuthController, causing Spring to return 500. Merged into a
multi-catch with BadCredentialsException so both cases return 401.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
