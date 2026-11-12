---
paths:
  - "src/test/**/*.java"
---

# Test assertion conventions

## Always assert the response body, not just the status code

Use `AssertException` (`src/test/java/io/elsci/boardeaux/util/AssertException.java`) for all error
assertions in API tests. Never assert only `getStatusCode()` for error responses.

```java
import static io.elsci.boardeaux.err.ValidationError.error;
import static io.elsci.boardeaux.util.AssertException.assertErrorResponse;
import static io.elsci.boardeaux.util.AssertException.assertNotFound;
```

| Scenario | Method |
|---|---|
| 400 with a top-level message | `assertErrorResponse(result, 400, "txId cannot be empty")` |
| 400 with field-level errors | `assertErrorResponse(result, 400, null, error("priceId", "must not be blank"))` |
| 400 typed param coercion failure | `assertErrorResponse(result, 400, null, error("forceRefresh", "must be true or false"))` |
| 403 | `AssertException.assertAccessForbidden("...", result)` |
| 404 | `assertNotFound("Couldn't find Card#<id>", result)` |
| DAO — `EntityNotFoundException` | `AssertException.assertEntityNotFound("Couldn't find Card#<id>", runnable)` |
| DAO — `AccessForbiddenException` | `AssertException.assertAccessForbidden("...", runnable)` |

`assertErrorResponse(response, expectedStatus, errMsg, ValidationError... errors)`:
- Pass `null` as `errMsg` when the exception carries only field errors (`Validation.notBlank` etc. sets `errorMessage = null`).
- Pass zero `ValidationError` varargs to skip the error-list check (useful when only `errMsg` matters).
