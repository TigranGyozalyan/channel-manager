---
name: writing-tests
description: >
  INVOKE THIS SKILL FIRST before writing or modifying any backend tests — this is a blocking requirement.
  TRIGGER when: the task touches any file matching `*DaoTest.java` or `*ApiTest.java`; the user asks to add, update, or fix a test; or the task involves a DAO/API change that requires a test update.
  Covers the two test styles (DAO vs API), Scenario/ApiClient/TestDao/AssertException helpers, naming conventions, and the permission-test template.
---

# Writing tests in boardeaux

This repo has **two distinct test styles**. Pick the right one before you start, then follow the conventions for that style.

## Running tests

Run `./mvnw` from the repo root (Testcontainers spins up Postgres automatically):

```bash
# Full suite
./mvnw test

# Single test class
./mvnw test -Dtest=CardDaoTest

# Single test method
./mvnw test -Dtest=CardDaoTest#getsCard
```

Exit code mirrors Maven — 0 means all green. Test output and failure details go to stdout/stderr and to `target/surefire-reports/`. The first run on a cold machine is slower (Docker image pull + Maven download); subsequent runs reuse the cached Testcontainers container.

After writing or modifying a test, always verify it passes before reporting the task done:

```bash
./mvnw test -Dtest=MyNewDaoTest
```

## Picking the style

| You want to test... | Use |
|---|---|
| DAO method behavior (SQL, exceptions, return values) | DAO test |
| HTTP-level behavior (status codes, JSON bodies, validation) | API test |
| Permission enforcement at the SQL layer | DAO test |
| User-facing error messages | API test |

When in doubt: if you're touching a `XxxDao.java` method, write a DAO test. If you're touching a `XxxApi.java` endpoint, write an API test. Most permission-aware methods deserve both — the DAO test proves the SQL raises the right error, the API test proves it surfaces correctly.

## Boilerplate

Both styles use:

```java
@ComponentTest
@RunWith(SpringJUnit4ClassRunner.class)
public class XxxTest {
    @Autowired private TestDao testDao;
    @Autowired private FeatureFlags featureFlags;   // if you toggle billing
    // ... + the DAOs / ApiClient / Scenario you need
}
```

`@ComponentTest` (in `io.elsci.boardeaux.annotation`) loads `main-context.xml` + `test-context.xml`, enables MockMvc, and applies `@WithMockUser`. Tests run on Testcontainers PostgreSQL 14 — one container is reused, state is isolated by random data.

## Style 1: DAO test

Direct injection. Build state through `TestDao` and direct DAO calls. No `Scenario`.

```java
@Autowired private CardDao cardDao;
@Autowired private BoardDao boardDao;
@Autowired private TestDao testDao;

@After
public void tearDown() { featureFlags.setBilling(false); }   // only if you flipped it

@Test
public void insertsCard() throws SQLException {
    User owner = testDao.createRandomUser();
    String boardId = boardDao.insert(owner.id, alphanumeric(5, 10));
    String colId = randomColId(owner.id, boardId);

    String result = cardDao.insert(owner.id, cardReq(boardId, colId, alphanumeric(5, 10)));

    assertNotNull(result);
}
```

Failure assertions (DAO):

```java
// ELSCI-403 (insufficient access, non-member)
SQLException ex = assertThrows(SQLException.class,
        () -> cardDao.insert(stranger.id, cardReq(boardId, colId, alphanumeric(5, 10))));
assertTrue(ex.getMessage().contains(PgErrorParser.DB_ERROR_PREFIX_ELSCI_403));

// ELSCI-404 (resource missing inside elsci_get_access)
SQLException ex = assertThrows(SQLException.class,
        () -> boardDao.updateName(user.id, fakeBoardId, alphanumeric(5, 10)));
assertTrue(ex.getMessage().contains(PgErrorParser.DB_ERROR_PREFIX_ELSCI_404));

// EntityNotFoundException (Java-side, when SQL returns 0 rows or the DAO throws it)
assertEntityNotFound(String.format("Couldn't find Card#%s", fakeCardId),
        () -> cardDao.get(owner.id, fakeCardId));
```

DAO tests typically **don't clean up** — random data avoids collisions.

## Style 2: API test

Use the `Scenario` builder + `ApiClient`. Always `scenario.cleanup()` in `@After`.

```java
@Autowired private TestDao testDao;
@Autowired private FeatureFlags featureFlags;
@Autowired private RestAssuredNotStatic restAssured;
@Autowired private ApiClient apiClient;
private Scenario scenario;

@Before public void setUp()    { scenario = new Scenario(testDao, restAssured, featureFlags); }
@After  public void tearDown() throws Exception { scenario.cleanup(); }

@Test
public void postsComment() throws SQLException {
    scenario.billing(false)
            .user("admin")
            .board("b")
                .column("col")
                    .card("c")
            .member("commenter", AccessLevel.COMMENTER)
            .asUser("commenter");

    MockMvcResponse result = apiClient.postComment(scenario.getCardId("c"), alphanumeric(5, 10));

    HttpErrorException.assertStatusEquals(result, HTTP_OK);
}
```

Failure assertions (API) — see the `test-assertions` rule (`.claude/rules/test-assertions.md`) for
the full reference. Always assert the response body, not just the status code. Use `AssertException`
(`src/test/java/io/elsci/boardeaux/util/AssertException.java`):

```java
// 400 — top-level message
assertErrorResponse(result, HTTP_BAD_REQUEST, "txId cannot be empty");

// 400 — field-level errors (null errMsg when ValidationException carries only errors list)
assertErrorResponse(result, HTTP_BAD_REQUEST, null, error("priceId", "must not be blank"));

// 403 — non-member
AssertException.assertAccessForbidden(
        "Oops. You don't have permissions to see this. Ask the board admin to give you an invite link.",
        result);

// 403 — insufficient permission
AssertException.assertAccessForbidden(
        String.format("User [%s] don't have permission [%d] to access the board [%s]",
                userId, AccessLevel.REGULAR.asInt(), boardId),
        result);

// 404
assertNotFound(String.format("Couldn't find Card#%s", fakeCardId), result);
```

## Parsing JSON response bodies

For endpoints that return a single entity or an array, the existing `Xxx.fromJson` / `Xxx.fromJsonArray` static methods on the response records are the idiomatic parsers — don't reach for Jackson directly.

```java
// Single entity (DAO or API)
BoardResponse board = BoardResponse.fromJson(boardDao.get(user.id, boardId));
assertEquals(newName, board.board().name());

// Array — e.g. GET /api/board/{id}/users
List<BoardUser> users = BoardUser.fromJsonArray(result.body().asString());
assertEquals(2, users.size());
assertTrue(users.stream().anyMatch(u -> u.id().equals(owner.id)));
```

If the response record for your endpoint doesn't have a `fromJsonArray` yet, add one — they're a one-liner over `JsonUtil.readValue(json, new TypeReference<>() {})`. See `BoardUser` for the template.

## The `Scenario` builder

State is **hierarchical** — `.column(...)` attaches to the current board, `.card(...)` to the current column, `.attachment(...)` to the current card. Switching `.board(...)` resets column/card pointers; switching `.user(...)` or `.asUser(...)` does not.

```java
scenario
    .billing(true)                                            // feature flag
    .globalProp("plan_name", "{\"boards\": 1, ...}")          // plan JSON for the global_prop table
    .user("alias")                                            // creates random user, sets as current, syncs auth
    .plan("plan_name")                                        // sets the current user's plan
    .board("alias")                                           // creates board owned by current user
        .column("alias")                                      //   creates column in current board
            .card("alias")                                    //     creates card in current column
                .attachment("name", new byte[...])            //       attaches blob to current card
    .member("alias", AccessLevel.COMMENTER)                   // adds a new random user to current board
    .asUser("alias");                                         // switches authenticated user
```

Getters: `scenario.getUser(alias)`, `.getBoard(alias)`, `.getColumn(alias)`, `.getCardId(alias)`, `.getCardId()` (current). For boards created via API (not the builder), call `scenario.addCreatedBoardId(id)` so cleanup picks them up.

## `ApiClient` — what's available

`io.elsci.boardeaux.ApiClient` wraps RestAssured MockMvc. If you need a new endpoint, add a one-line method here rather than calling `restAssured` directly in tests. Existing methods:

- Cards: `postCard`, `patchCard`, `getCard`
- Boards: `createBoard`, `patchBoard`
- Columns: `createCol`, `updateColName`, `deleteCol`
- Comments: `postComment`, `patchComment`
- Attachments: `postAttachment`, `getAttachment`
- Permissions / invites: `putPermission`, `createInvite`, `useInvite`
- Billing: `getBilling`, `getBillingConfig`, `switchPlan`, `cancelSubscription`, `resumeSubscription`, `previewSwitch`, `getSubscriptionManagementUrl`, `createPaddleCustomer`
- Misc: `getMe`

## `TestDao` — direct setup helpers

For state that the API doesn't expose conveniently (comments, attachments, board memberships) or for DAO tests that bypass HTTP entirely:

- `createRandomUser()`, `createGlobalProp(name, json)`, `setUserPlan(userId, plan)`
- `insertCard(userId, boardId, colId, name)`, `insertComment(authorId, cardId, body)`, `insertCol(...)`
- `addToBoard(boardId, owner, newMember, AccessLevel)` — grant access
- `createAttachment(cardId, userId, fileName, bytes)`
- `deleteCard()`, `deleteCol()`, `deleteAttachment()`, `deleteBoardAndCascade()`, `deleteUser()` — usually only needed in custom cleanup

## `AssertException`

`src/test/java/io/elsci/boardeaux/util/AssertException.java`

| Method | Use in |
|---|---|
| `assertEntityNotFound(message, ThrowingRunnable)` | DAO test — asserts `EntityNotFoundException` with exact message |
| `assertAccessForbidden(message, ThrowingRunnable)` | DAO test — asserts `AccessForbiddenException` |
| `assertNotFound(message, MockMvcResponse)` | API test — asserts HTTP 404 + JSON `errorMessage` |
| `assertAccessForbidden(message, MockMvcResponse)` | API test — asserts HTTP 403 + JSON `errorMessage` |
| `assertErrorResponse(response, status, errMsg, ValidationError...)` | API test — asserts status + optional `errorMessage` + optional field-level errors list |

## Random data

`io.qala.datagen.RandomShortApi` (`import static io.qala.datagen.RandomShortApi.*;`) covers almost
everything you'll need:
- `alphanumeric(min, max)` — random string
- `sample(list)` — random element
- `integer(min, max)` — random int
- `blankOr(s)` — `s` or a blank string
- `nullOrBlank()` — null or blank
- `mixedCase(s)` — `s` with each letter's case randomized

For the full method list (numbers, strings, booleans, sampling, lambdas) and the flexible builder API
(`RandomValue`, `RandomElements`, `Repeater`, `RandomDate`) for the rare case `RandomShortApi` doesn't
cover, see `references/datagen.md` — don't open the jar to check a signature, it's all there.

For columns of a board, idiomatic pattern is:

```java
private String randomColId(String userId, String boardId) throws SQLException {
    return sample(BoardResponse.fromJson(boardDao.get(userId, boardId)).cols()).id();
}
```

For levels below/above a given access, use `AccessLevel.lt(AccessLevel.MANAGER)`.

### Compacting equivalent tests with randomization

When several would-be tests differ only in the input value but assert the **same behavior**, don't
write one test per variant — write one test whose input is drawn randomly from the whole equivalence
class. Different runs exercise different members of the class; the suite stays small without losing
coverage. Established uses of this pattern:

- **Case variants** — `mixedCase("Bug")`: one duplicate-label-name test covers both the exact-match
  and the differs-only-in-case duplicates, instead of a dedicated "Bug vs BUG" test
  (`whenInsertingLabel_ifNameAlreadyExistsOnBoard_thenThrows`).
- **Invalid input variants** — `sample("red", "12345", "1234567", "zzzzzzzz", ...)`: one 400 test
  covers every rejected color format (`whenCreatingLabel_ifColorInvalid_thenCausesBadRequest`).
- **Permission variants** — `AccessLevel.lt(AccessLevel.MANAGER)`: one 403 test covers every
  insufficient level, instead of one test per level.
- **Optional/blank variants** — `blankOr(s)` / `nullOrBlank()`: one test covers null, empty, and
  whitespace-only inputs.

Two rules keep this honest:

1. **Only compact within one equivalence class.** If the expected outcome differs (400 vs 403, or a
   different error message), the variants are different scenarios and get separate tests.
2. **Degenerate draws must still be valid.** `mixedCase("Bug")` can randomly return `"Bug"` itself —
   fine for a duplicate test (still a duplicate), but don't use randomization where the degenerate
   draw would silently test nothing. When the assertion depends on the drawn value, capture it in a
   variable and assert against that variable, not a literal.

## Naming conventions

| Test type | Pattern | Example |
|---|---|---|
| DAO happy path | `<methodName>` | `insertsCard`, `patchesCard`, `getsAttachment` |
| DAO failure | `when<Method>_if<Cond>_thenThrows` | `whenInsertingCard_ifCallerNotBoardMember_thenThrows` |
| API happy path | `<method>_shouldSucceed_when<Cond>` or just `<methodName>` | `insert_shouldSucceed_whenBillingDisabled`, `postsComment` |
| API failure | `<method>_shouldFail_when<Cond>` or `when<Method>_if<Cond>_thenCauses<HTTPName>` | `patchCard_shouldFail_whenCallerNotMember`, `whenPostingComment_ifCallerHasInsufficientAccess_thenCausesForbidden` |

## Permission-test template for permission-gated endpoints

When you add a permission-gated DAO method, write **four tests** at the DAO layer plus the corresponding API tests:

1. **Happy path** — caller has exactly the required access.
2. **Insufficient permission** — caller is a member with less than the required level → `SQLException` + `ELSCI_403`.
3. **Non-member** — caller is not on the board → `SQLException` + `ELSCI_403` (the function distinguishes the message, but the prefix is the same).
4. **Resource not found** — fake id → `EntityNotFoundException` (or `SQLException` + `ELSCI_404` if `elsci_get_access` is reached without a row match — depends on SQL structure; check whether the WHERE filter excludes everything before the function is called).

For read endpoints (READER level), step 2 is usually not applicable — READER is the lowest level, so any member can read. Skip it and write only #1, #3, #4.

Read endpoints can also require **higher** than READER — e.g. `BoardDao.listUsersForBoard` requires MANAGER so non-managers can't see who is on a board. In that case step 2 fires (a REGULAR member is below MANAGER) and you write all four tests, same as for a write endpoint.

At the API layer, mirror with the equivalent status-code assertions. Use `AssertException.assertAccessForbidden` for 403, `assertNotFound` for 404, `HttpErrorException.assertStatusEquals` for status-only checks.

## Gotchas

- **`elsci_get_access` short-circuits**. If `WHERE id = ?` filters all rows out (e.g., fake attachment id), the function is never called — you get an empty result set, not ELSCI-404. The DAO converts that to `EntityNotFoundException`. Verify which path your SQL takes before asserting.
- **READER is the lowest level**. Any board member can read. Don't write an "insufficient access" test against a `get` endpoint that requires READER — it can't fire.
- **The `handleDbErrors` octet-stream bug**: tests against endpoints that declare `produces = APPLICATION_OCTET_STREAM_VALUE` and trigger a `SQLException` will fail with a wrapped `ServletException` rather than reach `AssertException.assertAccessForbidden`. This is a backend bug, not a test bug — see CLAUDE.md's "Error → HTTP mapping" gotcha.
- **`scenario.cleanup()` is mandatory in API tests.** Without it the next test inherits boards/users and assertions get flaky.
- **Don't reset `featureFlags` in API tests.** `scenario.cleanup()` restores it. DAO tests that flip `setBilling(true)` should reset it in `@After`.

## Adding a new test endpoint to ApiClient

If your test needs an endpoint not in `ApiClient`, add a short helper rather than inlining `restAssured.given().get(...)`:

```java
public MockMvcResponse getThing(String id) {
    return restAssured.given().get("/api/thing/{id}", id);
}
```

This keeps tests readable and central.
