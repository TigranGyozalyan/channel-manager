---
paths:
  - "src/**/*.java"
---

# Java conventions

## Jackson

This project uses Jackson 3.x — the package root is `tools.jackson`, **not** `com.fasterxml.jackson`. For example: `tools.jackson.databind.exc.InvalidFormatException`, `tools.jackson.databind.exc.MismatchedInputException`.

## Request validation

Never use `@Valid`, `@Validated`, `@NotBlank`, `@NotNull`, `@Size`, or any Jakarta Bean Validation annotation — the project has its own system:

- Use `io.elsci.boardeaux.validation.Validation` (static helpers: `notBlank`, `notNull`, `size`, `positive`, `max`, `pattern`) + `ValidationResult` to accumulate errors, then call `result.throwIfErrors()` — throws `ValidationException` → HTTP 400.
- For a single ad-hoc check, `throw new ValidationException("...")` inline in the controller is acceptable.
- For multi-field requests use a `*Req` POJO with `assertIsValid()`.

## Method ordering

Within a class, order methods by visibility, instance methods before static:

1. public
2. package-private
3. protected
4. private
5. public static
6. package-private static
7. protected static
8. private static

**Exception**: a static factory method that constructs an instance of the class (e.g. `of(...)`, `from(...)`, `create(...)`) is placed near the constructors, not down with the other static methods.

## Comments

Don't write redundant comments that just restate what the code already says. Only comment when the *why* isn't obvious from the code — a non-obvious constraint, a workaround, or behavior that would surprise a reader.
