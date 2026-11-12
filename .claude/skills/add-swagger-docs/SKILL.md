---
name: add-swagger-docs
description: >
  Use this skill when adding or updating Swagger/OpenAPI documentation on API endpoints.
  TRIGGER when: the task involves adding @Operation, @ApiResponse, or @ExampleObject annotations
  to any *Api.java file; the user asks to document an endpoint; or a new endpoint is being added
  that should appear in /swagger-ui.html.
  Covers @Operation structure, response shapes, request body examples, query/path parameters,
  multipart uploads, and when to use ExampleObject vs Schema.
  Accepts an optional argument naming the API to document (e.g. "board api", "attachment api");
  if omitted, covers all undocumented endpoints.
argument-hint: "[board api | card api | col api | attachment api | permission api]"
---

# Adding Swagger docs to API endpoints

All API controllers live under `src/main/java/`. The Swagger UI is served at `/swagger-ui.html`.
Only endpoints with `@Operation` appear there — unannotated endpoints are silently omitted.

## Scope

If an API name was passed as an argument (e.g. `/add-swagger-docs board api`), restrict work to
that controller file only. If no argument was given, scan all `*Api.java` files and document every
endpoint that is missing `@Operation`.

## Class-level tag

Every `*Api.java` class needs a single `@Tags` annotation. The `name` value becomes the grouping
label in Swagger UI and must be repeated verbatim in each `@Operation(tags = ...)` on that class:

```java
@Tags(@Tag(name = "Card API", description = "Functionality for cards: creating, updating, and commenting on cards."))
@RestController
class CardApi { ... }
```

## Method-level @Operation structure

```java
@Operation(tags = "Card API",
        summary = "Short one-liner shown in the collapsed endpoint row",
        description = """
                Full description. State required access level (e.g. "Requires at least Creator(64) access.")
                and any non-obvious constraints or billing effects.
                """,
        responses = {
                @ApiResponse(responseCode = "200", ...),
                @ApiResponse(responseCode = "403", ...),
                @ApiResponse(responseCode = "404", ...)
        }
)
@GetMapping("/api/card/{id}")
String get(@PathVariable("id") String cardId) { ... }
```

## Choosing the @ApiResponse content shape

| Situation | Use |
|---|---|
| Method returns `String` or `Map` (raw JSON from DAO) | `examples = @ExampleObject(value = CONSTANT)` + `schema = @Schema(implementation = Map.class)` |
| Method returns a known Java response class | `schema = @Schema(implementation = XxxResponse.class)` |
| Returns a JSON **array of objects** | `array = @ArraySchema(schema = @Schema(implementation = XxxResponse.class))` (see below) |
| Returns no body (204 No Content, 303 redirect) | `schema = @Schema(hidden = true)` |
| Error response (4xx) | `schema = @Schema(implementation = ErrorResponse.class)` |

**Rule**: when the Java return type is `String` or `Map`, the response shape is not
self-documenting — Swagger has nothing to infer from. Always pair it with a realistic
`@ExampleObject` so API consumers can see what fields to expect. `@Schema(implementation = Map.class)`
alone (without an example) is not acceptable for these endpoints.

Always include `mediaType = MediaType.APPLICATION_JSON_VALUE` on the `@Content` unless the
response is truly binary (see multipart section below).

### Arrays of objects

When an endpoint returns a JSON **array** (not a single object), don't put the element type in
`schema = @Schema(implementation = ...)` — that documents it as a single object. Use the `array`
field of `@Content` with `@ArraySchema` so Swagger renders it as a list:

```java
// Returns [ {...}, {...} ] — each element is a BoardUser
@ApiResponse(responseCode = "200",
        description = "Members of the board",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                array = @ArraySchema(schema = @Schema(implementation = BoardUser.class))))
```

For an array of raw-JSON/`Map` rows (DAO returns a JSON string), pair `@ArraySchema` with an
`@ExampleObject` holding the array literal, same Map.class spirit as single objects:

```java
content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
        array = @ArraySchema(schema = @Schema(implementation = Map.class)),
        examples = @ExampleObject(value = """
                [{"id": "id1", "seq": 0}, {"id": "id2", "seq": 16384}]"""))
```

Import: `io.swagger.v3.oas.annotations.media.ArraySchema`.

### The Map.class workaround

Whenever an `@ExampleObject` contains a JSON literal, you must also set
`schema = @Schema(implementation = Map.class)` on the same `@Content`. Without it, Swagger
sees the Java type as `String` and escapes the entire example into a quoted string literal in
the UI:

```
// What Swagger renders without the workaround:
"{\n  \"name\": \"In Progress\",\n  \"seq\": 2\n}"

// What Swagger renders with @Schema(implementation = Map.class):
{
  "name": "In Progress",
  "seq": 2
}
```

This applies in both places where `@Content` + `@ExampleObject` are used:

- **Responses** — the `content` field inside `@ApiResponse`
- **Request bodies** — the `content` field inside `@io.swagger.v3.oas.annotations.parameters.RequestBody`

The pattern is always the same: `examples` carries the value, `schema` carries the type hint.

```java
content = @Content(
        mediaType = MediaType.APPLICATION_JSON_VALUE,
        examples = @ExampleObject(value = SOME_JSON_CONSTANT),
        schema = @Schema(implementation = Map.class)   // prevents JSON string escaping
)
```

### Example constant pattern

Define the JSON example as a `private static final String` at the top of the class, then
reference it in `@ExampleObject`. This keeps the annotation readable and lets the compiler
validate it as a constant.

```java
// Bad — no example, caller has no idea what fields come back
@ApiResponse(responseCode = "200",
        description = "Column created successfully",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                           schema = @Schema(implementation = Map.class)))

// Good — realistic example with Map.class workaround
private static final String CREATE_COL_EXAMPLE = """
        {
            "id": "col-abc123",
            "board": "board-xyz456",
            "name": "In Progress",
            "color": null,
            "seq": 2
        }
        """;

@ApiResponse(responseCode = "200",
        description = "Column created successfully, returns the new column as JSON",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                           examples = @ExampleObject(value = CREATE_COL_EXAMPLE),
                           schema = @Schema(implementation = Map.class)))
```

To find what fields the response contains, check the SQL: `returning to_jsonb(table_name)`
returns every column of that table. For more complex shapes, look at the DAO method or
an existing API test that asserts specific field values.

### 204 / hidden body

```java
@ApiResponse(responseCode = "204",
        description = "Updated successfully",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(hidden = true)))
```

### Redirect with Location header

```java
@ApiResponse(responseCode = "303",
        description = "Successfully added to the board",
        headers = @Header(name = HttpHeaders.LOCATION, description = "URL of the board"),
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(hidden = true)))
```

## Request body documentation

For JSON request bodies, annotate at the `@Operation` level with `@io.swagger.v3.oas.annotations.parameters.RequestBody`.
Apply the Map.class workaround (see above) whenever the body is a JSON example:

```java
requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
        description = "Fields to update",
        content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(value = """
                        {"name": "new board name"}"""),
                schema = @Schema(implementation = Map.class)
        ),
        required = true
)
```

### Multiple examples for one request body

When the request body's structure alone doesn't make clear *what the request does* — e.g. a PATCH that
accepts any subset of fields, where each field combination is a distinct operation — pass a list of
named `@ExampleObject`s instead of one. Each gets a `name` (the dropdown label in Swagger UI) and a
`summary` (the human description); together they turn an opaque "send some fields" body into a menu of
concrete operations. See `CardApi.patch`:

```java
requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
        description = "Fields to update on the card. These operations can be combined by setting multiple keys.",
        content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = {
                        @ExampleObject(name = "Assign card",
                                summary = "Assign the card to a board member",
                                value = """
                                        {"assignee": "user-abc123"}"""),
                        @ExampleObject(name = "Move to a different column",
                                summary = "Move the card to another column on the same board",
                                value = """
                                        {"col": "col-newcolid"}"""),
                        @ExampleObject(name = "Undo deletion",
                                summary = "Restore a previously deleted card by setting deleted to null",
                                value = """
                                        {"deleted": null}""")
                },
                schema = @Schema(implementation = Map.class)
        ),
        required = true
)
```

Reach for this whenever a single example would be misleading: partial-update PATCHes, endpoints whose
behaviour branches on which keys are present, or "do X or Y" bodies. A one-line `{"name": "..."}` body
where the only operation is obvious does **not** need multiple examples — keep that as a single one.

## Query and path parameters

Use `parameters = { @Parameter(...) }` when the parameter names or semantics need clarification
beyond what the method signature conveys — especially optional query params:

```java
parameters = {
    @Parameter(name = "id",     in = ParameterIn.PATH,  description = "ID of the column to delete"),
    @Parameter(name = "moveTo", in = ParameterIn.QUERY, description = """
            ID of the column to move tasks into before deletion. \
            Omit to delete tasks along with the column.""")
}
```

Import: `io.swagger.v3.oas.annotations.Parameter` and `io.swagger.v3.oas.annotations.enums.ParameterIn`.

## Multipart file uploads

When a parameter is `MultipartFile`, Swagger won't infer binary format automatically. Annotate
the parameter directly — the `@Schema` must go on the parameter, not inside `@Operation`:

```java
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
String postAttachment(
        @RequestParam("card") String cardId,
        @Schema(type = "string", format = "binary")   // ← on the parameter itself
        @RequestPart("card-attachment") MultipartFile file) { ... }
```

The `@Operation` response for 200 should use `schema = @Schema(implementation = Map.class)` as
usual. Do **not** declare `produces = APPLICATION_OCTET_STREAM_VALUE` on endpoints that may also
throw permission/404 SQL errors — Spring cannot serialize the `ErrorResponse` POJO as octet-stream
and will surface a `ServletException` instead. See the `getAttachment` endpoint in
`AttachmentApi.java` for the safe pattern: return `ResponseEntity<Resource>` without a `produces`
constraint, and let the `contentType(...)` call on the response builder set the MIME type at
runtime.

## Checking API tests for flows

Before writing the description, read the corresponding `*ApiTest.java` to understand what the
endpoint actually accepts and rejects. The tests are the authoritative source for which status
codes fire and under what conditions:

```bash
# Find the test file for the controller you're documenting
find src/test/java -name "XxxApiTest.java"
```

Look for:
- Which access levels are tested → determines the required level to state in the description
- Which 4xx status codes are asserted → determines which `@ApiResponse` entries to include
- Any billing scenarios → add a note to the description if present

## Required imports

Add only what you use. The full set used in this codebase:

```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tags;
```

## Gotchas

- **`tags` value must match the class `@Tag(name = ...)`** exactly — a mismatch silently creates
  a second group in Swagger UI with no description.
- **`@Schema(hidden = true)` on 204/303 responses.** Without it, Swagger UI shows a confusing
  empty schema box.
- **`@ExampleObject` value must be a compile-time constant** — a `static final String` field or an
  inline string literal. It cannot be a method call or runtime expression.
- **Do not add `@Operation` to internal/form endpoints** (e.g. `createBoardFromForm`) — they are
  not intended for external API consumers and pollute the Swagger UI.
