# qala-datagen API reference (v2.5.1)

Full method surface for `io.qala.datagen`, so you don't need to open the jar to check a signature.
This repo depends on two artifacts: `qala-datagen` (core) and `qala-datagen-java8types` (adds
`java.time` support via `RandomDate`).

**In this repo, tests only use `RandomShortApi`** (`import static io.qala.datagen.RandomShortApi.*;`).
The flexible builder classes below (`RandomValue`, `RandomElements`, `Repeater`, `RandomDate`,
`StringModifier`) exist on the classpath but aren't currently used — reach for them only when
`RandomShortApi` genuinely can't express what you need (e.g. a random `LocalDate`, or a
delimiter-formatted repeated string), not as a stylistic preference.

## `RandomShortApi` — the one to use by default

All static; `import static io.qala.datagen.RandomShortApi.*;`.

### Numbers

| Method | Result |
|---|---|
| `integer()` | any int |
| `integer(max)` | `0..max` |
| `integer(min, max)` | `min..max` |
| `positiveInteger()` | `> 0` |
| `Long()` | any long |
| `Long(min, max)` | bounded long |
| `positiveLong()` / `negativeLong()` | sign-bounded long |
| `Double()` | any double |
| `Double(max)` / `Double(min, max)` | bounded double |
| `positiveDouble()` / `negativeDouble()` | sign-bounded double |

### Strings

| Method | Result |
|---|---|
| `alphanumeric(len)` / `alphanumeric(min, max)` | letters + digits |
| `numeric(len)` / `numeric(min, max)` | digits only |
| `english(len)` / `english(min, max)` | letters only |
| `unicode(len)` / `unicode(min, max)` | arbitrary unicode, may include boundary spaces |
| `unicodeWithoutBoundarySpaces(len)` / `(min, max)` | unicode, never starts/ends with a space |
| `specialSymbols(len)` / `(min, max)` | punctuation/symbol characters |
| `mixedCase(s)` | `s` with each letter's case randomized (see `writing-tests` skill's case-variant pattern) |

### Null / blank

| Method | Result |
|---|---|
| `nullOr(value)` | `null` or `value` — generic, works for any `T` |
| `nullOrEmpty()` | `null` or `""` |
| `nullOrBlank()` | `null`, `""`, or whitespace-only |
| `blankOr(s)` | `null`, `""`, whitespace-only, or `s` |

### Booleans

| Method | Result |
|---|---|
| `bool()` | random `true`/`false` |
| `bools(n)` | `boolean[]` of length `n` |
| `weighedTrue(p)` | `true` with probability `p` (0.0–1.0) |
| `nullableBool()` | `null`, `TRUE`, or `FALSE` |

### Collections / sampling

| Method | Result |
|---|---|
| `sample(collection)` / `sample(T...)` | one random element |
| `sample(collection, T...)` | one random element from collection + extra varargs |
| `sampleMultiple(collection)` | shuffled sublist of random size |
| `sampleMultiple(n, collection)` / `sampleMultiple(n, T...)` | `n` random elements, no duplicates |
| `shuffled(collection)` / `shuffled(T...)` | full collection, order shuffled |

### Lambdas (Java8 `Function` = `io.qala.datagen.Function`, a `void`-returning `Runnable`-like SAM)

| Method | Behavior |
|---|---|
| `callOneOf(fns...)` | invokes exactly one of the given functions |
| `callNoneOrMore(fns...)` | invokes zero or more (each independently, ~50/50) |
| `callOneOrMore(fns...)` | invokes at least one, up to all |
| `oneOf` / `noneOrMore` / `oneOrMore` | same semantics, older naming |

Use these to randomize *which* optional field(s) of a test object get populated, e.g.
`callOneOf(() -> req.name = alphanumeric(5, 10), () -> req.seq = integer(100))`.

## Flexible builder API (only if `RandomShortApi` can't express it)

### `RandomValue` — `io.qala.datagen.RandomValue` (implements `RandomString`)

```java
import static io.qala.datagen.RandomValue.*;

between(1, 10).alphanumeric();                       // ranged length, one string
length(10).english();                                // exact length
length(10).with(StringModifier.Impls.specialSymbol()).english();  // inject a modifier
between(f.parse("2015-01-01"), f.parse("2016-01-01")).date();     // legacy java.util.Date
```

Entry points: `between(long, long)`, `between(Date, Date)`, `upTo(long)`, `upTo(Date)`, `length(long)`.
Terminal methods: `alphanumeric()`, `alphanumerics()/(n)/(min,max)`, `numeric()`, `numerics()/(n)`,
`english()`, `specialSymbols()`, `unicode()`, `unicodeWithoutBoundarySpaces()`, `string(char...)`,
`string(String chars)`, `integer()`, `Long()`, `date()`. Chain `.with(StringModifier...)` before a
terminal method to inject modifiers (see below).

### `StringModifier.Impls` — injected into `RandomValue.with(...)`

`spaces()`, `spaceLeft()`, `spacesLeft(n)`, `spaceRight()`, `spacesRight(n)`, `specialSymbol()`,
`oneOf(String|char...)`, `multipleOf(String|char...)`, `occasional(String)`, `prefix(String)`,
`suffix(String)`, `whitespaceReplacement(String)`.

### `RandomElements<T>` — `io.qala.datagen.RandomElements`

```java
import static io.qala.datagen.RandomElements.*;

from("A", "B", "C").sample();                // one element
from(list).sample(2);                        // 2 elements, no duplicates
from("A", "B").sampleWithReplacement(3);      // 3 elements, duplicates allowed
from("A", "B", "C").shuffled();               // full list, shuffled
```

### `Repeater` — `io.qala.datagen.Repeater`

Builds delimiter-joined repeated random strings, e.g. formatted codes:

```java
import static io.qala.datagen.RandomValue.length;
import static io.qala.datagen.RandomString.Type.NUMERIC;
import static io.qala.datagen.Repeater.repeat;

repeat(length(4), NUMERIC).string("-").times(4);   // "9338-8349-6940-7714"
```

`repeat(RandomValue, RandomString.Type)` sets the per-chunk generator; `.string(sep)` sets the
separator; `.times(n)` or `.times(min, max)` sets the repeat count and returns the joined `String`.
`RandomString.Type` values: `ALPHANUMERIC`, `NUMERIC`, `ENGLISH`, `UNICODE`, `SPECIAL_SYMBOLS`, etc.
(mirrors the `RandomValue` terminal methods).

### `RandomDate` — `io.qala.datagen.RandomDate` (from `qala-datagen-java8types`, `java.time` only)

```java
import static io.qala.datagen.RandomDate.*;

plusMinus100Years().zonedDateTime();
since(yearAgo()).instant();
before(now()).instant();
between(yearsAgo(2), startOfMonth()).localDate();
```

Entry points: `between(...)` (overloads for `Instant`, `ChronoLocalDateTime`, `ChronoZonedDateTime`,
`LocalDate`, or ISO strings), `before(...)`, `after(...)`, `since(...)`, `beforeNow()`, `afterNow()`,
`yesterday()`, `thisYear()`, `plusMinus100Years()`. Terminal methods: `instant()`, `localDateTime()`,
`zonedDateTime()`, `offsetDateTime()`, `localDate()` (each has a plural `list(n)` variant too).
Static anchors: `now()`, `yearAgo()`, `yearsAgo(n)`, `inYears(n)`, `dayAgo()`, `daysAgo(n)`,
`hourAgo()`, `secondAgo()`, `startOfYear()/Month()/Day()/Hour()/Minute()`.

## Nothing else is on the classpath

There's no `RandomEmail`, `RandomUrl`, or similar higher-level generator — compose one from
`alphanumeric`/`english`/`sample` if a test needs it, following the pattern in
`ColCreateReqTest`/`BoardUpdateReqTest` rather than adding a new dependency.
