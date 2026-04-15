# Bug Fix: UsernameNotFoundException not caught in changePassword

**Date:** 2026-04-15  
**Type:** Bug fix  
**Status:** Approved

---

## Problem

`AuthController.changePassword()` only catches `BadCredentialsException`. However, `UserServiceImpl.changePassword()` can also throw `UsernameNotFoundException` when the authenticated user's record has been deleted between token issuance and the password-change request. This uncaught exception propagates to Spring's default error handler, returning **HTTP 500** instead of the documented **HTTP 401**.

**Affected file:** `src/main/java/com/example/paymentapi/controller/AuthController.java` (lines 146–164)  
**Root cause file:** `src/main/java/com/example/paymentapi/service/UserServiceImpl.java` (line 63–64)

---

## Design

### Change 1 — Add catch for `UsernameNotFoundException` in `AuthController.changePassword()`

Extend the existing catch block to also handle `UsernameNotFoundException`, returning a 401 `ErrorResponse` consistent with the existing `BadCredentialsException` handler:

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

### Change 2 — Add unit test in `AuthControllerTest`

Add a test that mocks `userService.changePassword()` throwing `UsernameNotFoundException` and asserts a 401 response is returned:

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

---

## Files to Modify

| File | Change |
|------|--------|
| `src/main/java/com/example/paymentapi/controller/AuthController.java` | Merge catch into multi-catch |
| `src/test/java/com/example/paymentapi/controller/AuthControllerTest.java` | Add `changePassword_userNotFound_returns401` test |

---

## Verification

1. Run `mvn test -pl . -Dtest=AuthControllerTest` — new test must pass, existing tests must not regress
2. Run full suite `mvn test` — all tests green
