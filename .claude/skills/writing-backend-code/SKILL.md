---
name: writing-backend-code
description: >
  INVOKE THIS SKILL FIRST before writing or modifying backend Java production code — this is a blocking requirement.
  TRIGGER when: the task touches a `*Api.java` controller; adds or changes a `*Dao.java`, `*Req`, model, or exception class; or edits `src/main/resources/main-context.xml`.
  Covers the package map, controller shape, Spring bean wiring, request validation, the exception→HTTP mapping, DTOs/models, and the service-layer convention.
  Defers SQL internals to writing-sql, tests to writing-tests, and Swagger annotations to add-swagger-docs.
---

# Writing backend code in boardeaux

The backend is Spring 7 MVC on Java 25, JDBC straight to PostgreSQL (no ORM), XML bean wiring. A
request flows `XxxApi` (controller) → `XxxDao` (hand-written JDBC) → PostgreSQL, with
`RestExceptionHandler` translating exceptions to HTTP. This skill covers the Java around the SQL.

- **SQL inside DAOs / migrations** → load `writing-sql` (blocking; permissions live in SQL, not Java).
- **Tests** → load `writing-tests` (blocking).
- **Swagger `@Operation` annotations** → load `add-swagger-docs` (blocking).

## Package map

```
io.elsci.boardeaux            *Api (controllers), *Dao, *Req (requests), models (User, AccessLevel,
                              IdGenerator), HtmlController
io.elsci.boardeaux.err        RestExceptionHandler, EntityNotFoundException, ValidationException,
                              ValidationError, InvalidBoardOperation, ErrorResponse, ConstraintViolationException
io.elsci.boardeaux.validation Validation (static helpers), ValidationResult
io.elsci.boardeaux.attachments AttachmentApi, AttachmentDao, Attachment
io.elsci.boardeaux.settings   GlobalSettingsDao, GlobalProp
io.elsci.boardeaux.external   ImportApi, trello/*
io.elsci.boardeaux.util       FeatureFlags
io.elsci.billing              BillingApi, BillingService, BillingAccountDao, BillingException, ...
io.elsci.web.security         Principal, UserSessionService, AccessForbiddenException, SecurityConfig
io.elsci.db                   PgErrorParser, FlywayContext
```

## Controller shape

```java
@Tags(@Tag(name = "Col API", description = "Functionality for board columns: ..."))
@RestController
@RequestMapping("/api/col")
class ColApi {                                   // package-private class is the norm
    private final ColDao colDao;
    private final UserSessionService sessionService;

    ColApi(ColDao colDao, UserSessionService sessionService) {   // constructor injection
        this.colDao = colDao;
        this.sessionService = sessionService;
    }

    @PatchMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    public void updateColName(@PathVariable("id") String colId,
                              @RequestBody(required = false) String body) throws Exception {
        if (body == null || body.isBlank())
            throw new ValidationException("Col name is required, it cannot be blank or null");
        this.colDao.updateColName(sessionService.getUserId(), colId, body);
    }
}
```

Conventions:

- Class-level `@RestController` + `@Tags`; methods use `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping`.
- **Get the caller** via `sessionService.getUserId()` (a `String`), or `sessionService.getCurrentPrincipal()`
  when you need the full `Principal`. Pass `userId` as the **first argument** to the DAO method.
- **Return** the raw JSON `String` the DAO produces (most reads/writes), a typed object for Jackson to
  serialize, or `ResponseEntity<?>` when you need a custom status/headers/content type. Use
  `@ResponseStatus(NO_CONTENT)` + `void` for writes with no body.
- Declare `throws Exception` — `SQLException` and domain exceptions bubble up to `RestExceptionHandler`.
- **Don't check board permissions in the controller or DAO Java** — that's done in SQL (`writing-sql`).

## Update endpoints — one patch path per resource

A resource gets a **single** update entry point: one controller method calling one `XxxDao.patch(userId,
id, json)` that accepts a JSON body and applies whatever subset of keys is present (`CardDao.patch`,
`BoardDao.patch`). Don't add a separate DAO method per field (`updateName`, `updateProps`, ...) or branch
in the controller on which fields were supplied — the key-presence branching happens once, in the SQL
(`payload ?? 'key'`, see `writing-sql`). Small single-field validations belong inline as a private
controller method (`UserApi.validatePrefs`-style) rather than a dedicated vocabulary/DTO class for one prop.

This is the default, not an absolute — a field can warrant its own operation when it has different
permission/access-level requirements than the rest of the resource, triggers distinct side effects
(billing, notifications, counters), needs its own concurrency/atomicity handling, or the write itself is
complex enough (multi-step, procedural logic, its own stored function) that folding it into the shared
patch path would obscure it rather than simplify it. If a new field seems like it might need to be pulled
out of the shared patch path, don't guess either way — stop and ask for clarification before implementing.

## Bean wiring — don't forget this step

Beans are wired in `src/main/resources/main-context.xml` with `default-autowire="constructor"`, so a new
class is constructed with whatever beans match its constructor parameter types. **Every new
`*Api` / `*Dao` / service must get a one-line `<bean>` entry** — there is no component scan for these
packages, so a missing line means the bean silently doesn't exist and Spring fails to wire dependents.

```xml
<bean class="io.elsci.boardeaux.ColApi"/>
<bean class="io.elsci.boardeaux.ColDao"/>
```

DAOs take `DataSource` (bean `dataSource`); some also take `FeatureFlags` (bean `featureFlags`). Both
already exist in the XML, so a plain `<bean class="..."/>` is all you add for a typical DAO/Api.

## Request validation

Request bodies are `*Req` POJOs with **public fields** and a no-arg constructor (Jackson). They
self-validate via `assertIsValid()`, which the controller calls **before** touching the DAO:

```java
public class ColCreateReq {
    static final int NAME_MAX_LENGTH = 64;
    public String name;
    public Integer seq;

    public void assertIsValid() {
        ValidationResult result = new ValidationResult();
        Validation.notBlank(result, name, "name");
        Validation.size(result, name, "name", 0, NAME_MAX_LENGTH);
        Validation.notNull(result, seq, "seq");
        result.throwIfErrors();                  // throws ValidationException (→ 400) if any errors
    }
}
```

`io.elsci.boardeaux.validation.Validation` has `notBlank`, `notNull`, `size`, `positive`, `max`,
`pattern`, `email`. For a single ad-hoc check, throwing `new ValidationException("...")` inline in the
controller is acceptable (see `ColApi.updateColName`); for multi-field requests prefer the `*Req` +
`ValidationResult` pattern.

### Where validation lives

| What to check | Where | How |
|---|---|---|
| Format, blank, type checks on HTTP params (`@RequestParam`, `@PathVariable`, request body fields) | **Controller** (`*Api.java`) | `Validation.notBlank(r, param, "name")` + `r.throwIfErrors()`, or inline `throw new ValidationException("...")` |
| Entity existence, membership, business rules, billing limits | **Service or DAO** | `EntityNotFoundException`, `ValidationException`, or SQL `[ELSCI-4xx]` |

**Rule**: the controller owns HTTP-param validation — blank checks, format rules, range constraints — and throws before any service or DAO call. Services assume params arrive non-blank and focus on whether referenced entities exist and whether semantic invariants hold. Never push blank/format checks into the service.

For typed `@RequestParam` bindings (e.g. `Boolean forceRefresh`), Spring throws `MethodArgumentTypeMismatchException` on bad input; `RestExceptionHandler` maps it to a field-level 400. Don't change a typed param to `String` just to validate manually — add a handler in `RestExceptionHandler` if one is missing.

## Exceptions → HTTP

Throw a domain exception and let `RestExceptionHandler` (`@ControllerAdvice`) map it. **Never build
error `ResponseEntity` bodies by hand.**

| Throw | HTTP | When |
|---|---|---|
| `EntityNotFoundException("Card", id)` | 404 | DAO query returned no rows / no such entity (message `"Couldn't find Card#<id>"`) |
| `AccessForbiddenException` | 403 | Java-side access denial (rare; board access is in SQL) |
| `ValidationException` | 400 | bad input; carries field errors |
| `InvalidBoardOperation` | 400 | semantic rule (e.g. deleting the last column) |
| `BillingException` | 403 | quota/billing; uses `BillingErrorResponse` |
| `SQLException` w/ `[ELSCI-403]` | 403 | raised by `elsci_get_access` |
| `SQLException` w/ `[ELSCI-404]` | 404 | board missing |
| `SQLException` w/ `[ELSCI-402]` | 403 | quota, via `elsci_check_*_limits` |

`SQLException` mapping is automatic via `PgErrorParser` — you don't catch it in the controller. See
`writing-sql` for how the SQL raises these.

**DB-error translation is centralized — never reinvent it in a DAO.** Don't catch/inspect `SQLException`
inside a DAO to turn a specific DB error (e.g. a unique-constraint violation) into an HTTP response;
DAOs stay pure JDBC and let it propagate. To handle a new constraint-backed error, extend
`PgErrorParser` (detection/parsing, e.g. a new SQLSTATE or constraint-name check) and add a branch in
`RestExceptionHandler.handleDbErrors` (HTTP mapping) instead. If you find a leftover, never-thrown
exception/handler pair for a case like this, delete it rather than wiring new code into it — reviving
dead code creates a second, parallel error path.

## Models / DTOs

- **Requests**: `*Req` POJOs, public fields, `assertIsValid()`.
- **Responses**: most DAO reads return a JSON `String` built in SQL — no Java model needed. When a typed
  response exists it's a `record` or a public-field POJO; test-side parsers like `BoardResponse.fromJson`
  read them (see `writing-tests`).
- **IDs**: `IdGenerator.generate()` — short base62 strings, minted in Java, never DB sequences.

## Service layer

Controllers call DAOs **directly** for plain CRUD. Introduce/use a service only for multi-step logic or
external integrations — e.g. `BillingService` (Paddle + plan math), `TrelloService`. Don't add a
pass-through service that only forwards to a single DAO.

## Gotchas

- **Forgetting the `main-context.xml` bean** is the most common backend mistake — the app starts but
  fails to wire the dependent, or the endpoint 404s. Add the `<bean>` line as part of the same change.
- **Permission belongs in SQL, not Java.** Don't add `if (access < ...)` checks in a DAO/controller.
- **Octet-stream + DB errors**: don't declare `produces = APPLICATION_OCTET_STREAM_VALUE` on an endpoint
  whose SQL may raise `[ELSCI-4xx]` — `handleDbErrors` can't serialize the error POJO as octet-stream and
  a `ServletException` leaks. Return `ResponseEntity<Resource>` and set the content type at runtime
  instead (see `AttachmentApi.getAttachment`).
