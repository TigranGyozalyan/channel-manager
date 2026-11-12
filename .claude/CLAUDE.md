# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Stack

**Backend**: Spring 7 MVC on embedded Tomcat 11, Java 25, JDBC straight to PostgreSQL (no ORM), Flyway migrations, Spring Security with OAuth2, Spring Session backed by Redis, SpringDoc OpenAPI for Swagger.

**Frontend**: vanilla JavaScript ES modules bundled with esbuild — no framework (no React/Vue/Svelte). Entry points are `*Page.js` files paired with `*.html` files in `src/main/ui/`.

> Backend and frontend are developed by separate people in this repo. Detailed conventions for each area live in dedicated skills — see the **Tests** and **Frontend** sections below. This file is the shared orientation layer.

## Commands

```bash
# Backend
./mvnw test                        # full backend test suite (Testcontainers spins up Postgres)
./mvnw test -Dtest=CardDaoTest     # single test class
./mvnw test -Dtest=CardDaoTest#getsCard  # single test method
./mvnw package                     # build jar

# Frontend
npm run build-js          # bundle *Page.js → target/classes/ui/ (no minify)
npm run build-dev         # full dev build (bundles + injects CSS)
npm run build             # production build (inlines CSS+JS, adds CSP sha256 script hashes)
npm run watch-dev         # rebuild on src/main/ui changes
npm test                  # JS tests (node --test + zora, JUnit XML at target/surefire-reports/)
node --test src/test/js/ArrayUtilTest.js  # single JS test file
```

Run modes for the server itself live in `build-scripts/` and `infra/`.

## Architecture

```
HTTP request
  └→ XxxApi (REST controller, @RestController, e.g. CardApi)
       └→ XxxDao (hand-written JDBC, e.g. CardDao)
            └→ PostgreSQL  (permissions enforced via elsci_get_access SQL function)
                  ↑
                  raises [ELSCI-403] / [ELSCI-404] inside SQL
  └→ RestExceptionHandler  (@ControllerAdvice — translates SQLException + custom exceptions to HTTP)
```

Spring beans are wired via `src/main/resources/main-context.xml` (constructor autowire) plus `swagger-context.xml`. Security is the one Java `@Configuration` — `io.elsci.web.security.SecurityConfig`. The entry point is `io.elsci.boardeaux.Main`.

### Permission model

`AccessLevel` enum: `READER(2)`, `COMMENTER(32)`, `CREATOR(64)`, `REGULAR(96)`, `MANAGER(128)`, `ADMIN(4096)`.

Permissions are enforced in SQL via the `elsci_get_access(boardId, userId, minLevel)` function. Its current definition lives in the repeatable migration `db/migration/R__elsci_procedures.sql` (historically introduced in `V1.0.0__initial.sql` and refined in `V1.1.0`/`V1.4.0`, which retain their applied-migration copies). It returns the user's actual access if `>= minLevel`, or raises:

- `[ELSCI-403] User [%] don't have permission [%] to access the board [%]` — insufficient access
- `[ELSCI-403] User [%] is not a member of the board [%]` — non-member, board exists
- `[ELSCI-401] Board [%] is not public, authentication is required to access it` — anonymous caller, board exists but has no public row
- `[ELSCI-404] Board [%] doesn't exist` — board missing

Convention: DAO methods that touch a board take `userId` as the first parameter, and the SQL embeds `... and elsci_get_access(board, ?, ?) > 0` in the WHERE clause. The DAO never checks permissions in Java — always in SQL.

`PgErrorParser` exposes `DB_ERROR_PREFIX_ELSCI_403` / `DB_ERROR_PREFIX_ELSCI_404` constants for matching error messages. `RestExceptionHandler.handleDbErrors` maps:
- ELSCI-401 → HTTP 401 + "Oops. Please log in to see this."
- ELSCI-403 with "not a member" → HTTP 403 + "Oops. You don't have permissions to see this..."
- ELSCI-403 with "don't have permission" → HTTP 403 + the formatted DB message
- ELSCI-404 → HTTP 404 + generic "Resource not found" message

### Error → HTTP mapping

| Exception | HTTP | Notes |
|---|---|---|
| `EntityNotFoundException("Entity", id)` | 404 | message: `"Couldn't find Entity#<id>"` |
| `AccessForbiddenException` | 403 | |
| `ValidationException` | 400 | carries field errors |
| `InvalidBoardOperation` | 400 | semantic errors (e.g., last admin) |
| `BillingException` | 403 | uses `BillingErrorResponse` |
| `SQLException` w/ ELSCI-401 | 401 | anonymous caller, board not public |
| `SQLException` w/ ELSCI-403 | 403 | see above |
| `SQLException` w/ ELSCI-404 | 404 | |

**Gotcha**: `handleDbErrors` returns `ResponseEntity<ErrorResponse>` (a POJO), unlike the other handlers that go through `createResponse`/`jsonResponseWithHeaders` and force a JSON content type. On endpoints declared `produces = APPLICATION_OCTET_STREAM_VALUE` (e.g. `GET /api/attachment/{id}`), Spring cannot serialize the POJO as octet-stream and bubbles `HttpMediaTypeNotAcceptableException` up as a ServletException. Either change the handler to use `createResponse(...)` or avoid octet-stream `produces` on endpoints that may throw DB errors.

### Billing

Master flag is `FeatureFlags.billing()`. When disabled, no limits are enforced. Plan limits live as JSON in the `global_prop` table (keyed by plan name) with these keys: `boards`, `tasksPerBoard`, `maxAttachmentSizeMb`, `storageMbPerBoard`. A missing key means "not allowed" (0); `null` means unlimited. The `BillingService` and the insert SQL for cards/attachments/boards (`InsertCard.sql`, `InsertAttachment.sql`, `InsertBoard.sql`) enforce these limits within their CTEs — guarded by a `billing_enabled` flag — via the `elsci_check_board_limits(...)` / `elsci_check_account_limits(...)` SQL functions (defined in `R__elsci_procedures.sql`). See **BILLING.md** for the full rules.

### Migrations

Flyway, `src/main/resources/db/migration/`, versioned migrations named `VX.Y.Z__description.sql`. Never edit an applied versioned migration; add a new one. **Stored functions instead live in the repeatable migration `R__elsci_procedures.sql`** (`elsci_get_access`, `elsci_check_board_limits`, `elsci_check_account_limits`, `elsci_get_account_limits`, `elsci_get_board_usage`, `elsci_delete_col`, `clone_card_for_assignees`, …) — Flyway re-runs it whenever its checksum changes, so edit a function in place there rather than adding a versioned migration. Any new permission-gated table/operation should call `elsci_get_access(...)` in its SQL rather than re-checking in Java. **Invoke the `writing-sql` skill before writing any SQL** (DAO queries, `.sql` files, or migrations) — see the Backend code section below.

### Swagger

UI is served at `/swagger-ui.html`. **Invoke the `add-swagger-docs` skill (`/add-swagger-docs`) before adding or updating `@Operation` annotations.** It is the sole source of conventions for this area.

## Backend code

**Invoke the `writing-backend-code` skill before writing or changing any backend Java** (controllers, DAOs, bean wiring, validation, exceptions, models) and the **`writing-sql` skill before writing any SQL** (DAO queries, `.sql` files, migrations). They are the sole source of conventions for these areas — do not proceed without loading them first.

For full vertical features (migration → DAO + SQL → controller/wiring/validation → Swagger → tests), dispatch the **`backend-engineer` agent** (`.claude/agents/backend-engineer.md`). It orchestrates `writing-backend-code`, `writing-sql`, `add-swagger-docs`, and `writing-tests` end-to-end, runs the suite until green, and — once green — always dispatches the read-only **`backend-change-reviewer` agent** (`.claude/agents/backend-change-reviewer.md`) for an unbiased pass over the whole resulting codebase (not just the diff), watching specifically for unnecessary abstractions/dependencies/complexity creeping in.

## Frontend

**Invoke the `working-with-ui` skill before doing any frontend work.** It is the sole source of conventions for this area — do not proceed without loading it first.

## Tests

**Invoke the `writing-tests` skill before writing or modifying any tests.** It is the sole source of conventions for this area — do not proceed without loading it first.

## Repository docs

- `BILLING.md` — quota enforcement details
- `TESTING.md` — `Scenario` builder examples
