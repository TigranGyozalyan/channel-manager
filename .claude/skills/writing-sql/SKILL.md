---
name: writing-sql
description: >
  INVOKE THIS SKILL FIRST before writing or modifying any SQL in this repo — this is a blocking requirement.
  TRIGGER when: the task touches SQL embedded in a `*Dao.java` file; a `.sql` file under `src/main/resources/io/elsci/...`; or a Flyway migration under `src/main/resources/db/migration/`.
  Covers where SQL lives, the JDBC DAO method shape, enforcing permissions inside SQL via `elsci_get_access`, the CTE-driven write idiom, returning JSON, common idioms (upserts, soft deletes, arrays, IDs), migrations, and raising `[ELSCI-4xx]` errors.
---

# Writing SQL in boardeaux

This project has **no ORM**. Every query is hand-written SQL run through plain JDBC, and **permission
checks live inside the SQL**, not in Java. Generic SQL knowledge is not enough here — follow these
conventions exactly. For the Java around the SQL (controllers, wiring, validation) see
`writing-backend-code`; for tests see `writing-tests`.

## Where SQL lives

Three forms, pick by complexity — always start at the lowest tier that works:

| Form | When | Example |
|---|---|---|
| **Inline Java text block** in a `*Dao.java` method | simple / medium queries (most reads, single-statement writes) | `CardDao.get`, `CardDao.patch`, `BoardDao.patch`, `CardDao.deleteCard` |
| **External `.sql` file** under `src/main/resources/io/elsci/boardeaux/` | multi-CTE writes that are still declarative pipelines | `InsertBoard.sql`, `InsertAttachment.sql`, `SetPermission.sql` |
| **Stored function** in `R__elsci_procedures.sql` | procedural logic — see "CTE or function?" below | `boardeaux_insert_card`, `clone_card_for_assignees` |

External files are loaded by classpath path:

```java
PreparedStatement st = c.prepareStatement(
        IoUtils.getStringFromClasspath("io/elsci/boardeaux/InsertBoard.sql"));
```

Rule of thumb: if the query is one statement and fits comfortably in a text block, inline it. If it's
a chained-CTE write that you'd want to read on its own, put it in a `.sql` file next to the others.

## CTE or function? The complexity criteria

We don't spawn a function for every write — **start with a CTE statement and only promote it to a
stored function when it stops being declarative.** Calibration: `InsertBoard.sql` chains four inserts
but stays a CTE (a straight-line pipeline with one billing gate); card insert started as a CTE and was
fine, but the task-numbering logic tipped it into `boardeaux_insert_card`.

Signals that a statement wants to be a function — one is a judgment call, **two or more means promote**:

1. **Distinct errors per failed check.** You need to raise different `[ELSCI-4xx]` messages depending
   on *which* validation failed (col missing → 404 vs col on another board → 400). A CTE can only
   filter rows silently or let a called function raise; distinguishing causes from a CTE means
   returning marker columns and re-throwing in Java.
2. **Check ordering matters.** E.g. access must be verified *before* probing other entities (error
   precedence, no info leaks to non-members). Predicate/CTE evaluation order in one statement is
   planner-dependent; plpgsql sequences checks explicitly.
3. **Read-modify-write state feeding later steps.** Counters like `boards.taskseq`:
   `update ... returning into` a variable used by a later insert, where failed paths must not consume
   a value. In a function any raise rolls the whole call back; in a CTE the bump needs careful gating.
4. **Absence must be an error.** "Zero rows matched" should raise, not silently write nothing —
   functions check `found`; CTEs go quiet.
5. **Loops / per-row iteration** (`clone_card_for_assignees`) — automatic function.

Meta-signal: if the CTE version needs comments explaining *planner semantics* (data-modifying CTE
execution rules, one-time filters) to argue it is correct, it is already too complex — the correctness
lives in how Postgres evaluates it, not in what the SQL says. Move it to a function.

Signals to stay a CTE: straight-line pipeline (insert → use id → insert children), a single
permission/billing gate whose only error path is the called function's own raise, conditions that are
naturally row filters where "filtered out" *is* the correct behavior, and subset updates via
`case when v.j ?? 'key'` (`BoardDao.patch`).

**Naming: every new stored function is prefixed `elsci_`** (`boardeaux_insert_card`, `elsci_get_access`,
`elsci_check_board_limits`). `update_card` and `clone_card_for_assignees` predate this rule — don't
copy their naming, and don't rename them outside a dedicated change.

## The DAO method shape

Always the same skeleton. No `JdbcTemplate`, no `RowMapper`, no named parameters.

```java
public String get(String userId, String cardId) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
        PreparedStatement st = c.prepareStatement("""
                select ... from cards t where id = ? and elsci_get_access(t.board, ?, ?) > 0
                """);
        st.setString(1, cardId);
        st.setString(2, userId);
        st.setInt(3, AccessLevel.READER.asInt());
        ResultSet rs = st.executeQuery();
        if (!rs.next())
            throw new EntityNotFoundException("Card", cardId);
        return rs.getString(1);
    }
}
```

- `dataSource` is the only field, injected via constructor (`CardDao(DataSource, ...)`).
- **Positional parameters only** — `st.setString(1, ...)`, `st.setInt(2, ...)`. Never string-concat values.
- **Manual extraction** from the `ResultSet` — usually `rs.getString(1)` because reads return JSON (below).
- **`userId` is the first parameter** of any method that touches a board.
- `executeQuery()` for reads / `RETURNING` writes; `executeUpdate()` for writes with no result.
- An `executeUpdate()` returning `0`, or an `executeQuery()` with no `rs.next()`, means "not found or no
  access" — throw `new EntityNotFoundException("<Entity>", id)` (see Gotchas for why it's not always 403/404).

## Binding parameters — runtime values via `setX`, constants inline

Bind every **runtime** value as a `?` parameter — ids, user input, and flags computed in Java
(`featureFlags.billing()`). Never string-concat values into SQL. Constants are the exception (below).

- **Constants and enums are NOT passed as parameters.** Access levels, seq steps, fixed
  booleans/ints go directly into the SQL as literals; when a bare literal would obscure intent
  (`4096`), name it in a `vars` CTE instead (`4096 as admin_access`). Older queries in the codebase
  still bind constants as `?` — don't copy that style into new or edited SQL.
- **Dedup repeated binds with a `vars` CTE.** If the same value would be bound through several `?`s
  (e.g. `boardId` once for the `WHERE` and again for `elsci_get_access`), select it **once** in a
  leading `with vars as (select ? as ..., ...)` CTE, add `from vars v` and reference `v.xxx`
  everywhere. This is the single-statement sibling of the param CTE used in multi-CTE writes (below).
- **Nulls**: for a runtime value that may be null, the usual `setString`/`setObject` handles it. For a
  *constant* null argument, a bare `null` literal is untyped and Postgres rejects it where it can't
  infer a type — write a typed literal (`null::text`), or `st.setNull(n, Types.VARCHAR)` if it's bound.

`BoardDao.patch` shows both rules at once — each runtime value bound once, the access level a named
constant in `vars`:

```java
PreparedStatement st = c.prepareStatement("""
        with vars as (
            select ?::jsonb as j, ? as board_id, ? as user_id, 128 as manager_access
        )
        update boards b
        set name  = case when v.j ?? 'name'  then trim(v.j->>'name') else b.name end
          , props = case when v.j ?? 'props' then v.j->'props' else b.props end
        from vars v
        where b.id = v.board_id and elsci_get_access(v.board_id, v.user_id, v.manager_access) > 0
        """);
st.setString(1, json);
st.setString(2, boardId);
st.setString(3, userId);
st.executeUpdate();
```

## Permissions are enforced in SQL — never in Java

The board permission model is enforced by the `elsci_get_access(resource, sid, min_access)` function
(`R__elsci_procedures.sql`):

```sql
create or replace function elsci_get_access(resource text, sid text, min_access int) returns int as $$
...
    if actual_access >= min_access then
        return actual_access;                                  -- caller has enough access
    elseif actual_access < min_access then
        raise exception '[ELSCI-403] User [%] don''t have permission [%] to access the board [%]', ...;
    else                                                       -- actual_access is null → not a member
        if (select count(*) from boards where id = resource) = 0 then
            raise exception '[ELSCI-404] Board [%] doesn''t exist', resource;
        end if;
        raise exception '[ELSCI-403] User [%] is not a member of the board [%]', sid, resource;
    end if;
$$ language plpgsql stable parallel safe;
```

Embed `elsci_get_access(board, ?, ?) > 0` in the `WHERE` clause (or a CTE) of every board-scoped query.
**Parameter order is always `(resource = boardId, sid = userId, min_access)`.** In new SQL, `min_access`
is a literal or a `vars`-named constant (see Binding parameters), not a bound `?` — the examples below
predate that rule.

`AccessLevel`: `READER(2)`, `COMMENTER(32)`, `CREATOR(64)`, `REGULAR(96)`, `MANAGER(128)`, `ADMIN(4096)`.

**SELECT** — gate by the lowest level that may read:

```sql
from cards t where id = ? and elsci_get_access(t.board, ?, ?) > 0   -- READER
```

**INSERT** — gate in the `WHERE` of the `insert ... select`:

```sql
insert into comments(id, card, author, body)
select ?, ?, ?, ? from cards where id = ? and elsci_get_access(board, ?, ?) > 0   -- COMMENTER
returning to_jsonb(comments) - comments.body;
```

**UPDATE / DELETE** — the common "creator vs everyone else" idiom: the creator needs a lower level
than other members. Bind `userId` twice (once per branch):

```sql
update cards set deleted = now() where id = ? and deleted is null
and case when creator = ?
      then elsci_get_access(board, ?, ?) > 0   -- CREATOR  (the author)
      else elsci_get_access(board, ?, ?) > 0   -- REGULAR  (anyone else)
end
```

```java
st.setString(2, userId);                          // creator = ?
st.setString(3, userId); st.setInt(4, AccessLevel.CREATOR.asInt());
st.setString(5, userId); st.setInt(6, AccessLevel.REGULAR.asInt());
```

When the row being checked is reached via a join (e.g. patching a comment by its own id), wrap the
permission check in a CTE and gate the write with `exists(select 1 from has_permissions)` — see
`CardDao.patchComment`.

## The CTE-driven write idiom

**Prefer one statement over many.** When an operation needs several reads/writes, combine them into a
single statement with CTEs and return what you need via a trailing `select`, rather than issuing
multiple `PreparedStatement`s and stitching results together in Java. One round-trip is atomic, fewer
network hops, and keeps the related logic in one place. Fall back to separate statements only when the
combined query becomes unreasonably complex to read.

The dominant pattern for non-trivial writes: a leading **param CTE** that names every `?`, then chained
`insert ... returning` CTEs, ending with a `select to_jsonb(x)` that hands the created row back as JSON.
`InsertCard.sql`:

```sql
with p as (select ? as board_id, ? as billing_enabled, ? as card_id, ? as creator, ? as assignee,
                  ? as col, ? as seq, ? as name, ? as body, ? as access_level),
     c as (insert into cards (id, board, creator, assignee, col, seq, name, body)
             select p.card_id, p.board_id, p.creator, p.assignee, p.col, p.seq, p.name, p.body
             from p
             where elsci_get_access(p.board_id, p.creator, p.access_level) > 0     -- permission gate
               and case
                       when p.billing_enabled
                           then exists(select 1 from elsci_check_board_limits(p.board_id, 1))  -- billing gate
                       else true
                   end
             returning *)
select to_jsonb(c) from c;
```

Why the param CTE: it names parameters once so multiple downstream CTEs can reuse them without you
re-binding the same `?`. `InsertBoard.sql` chains four CTEs in one atomic statement — create the board
(account-limit gated), add the creator as a member, seed three columns via `lateral (values ...)`:

```sql
with p as (select ? as user_id, ? as billing_enabled, ? as board_id, ? as board_name, ? as access_level,
                  ? as col_todo_id, ? as col_in_progress_id, ? as col_done_id),
     b as (insert into boards (id, name, creator)
             select p.board_id, p.board_name, p.user_id from p
             where case when p.billing_enabled
                        then exists(select 1 from elsci_check_account_limits(p.user_id, 1))
                        else true end
             returning id),
     members as (insert into board_members (board, member, access)
             select b.id, p.user_id, p.access_level from b, p returning 1),
     cols as (insert into cols (id, board, name, seq)
             select c.id, b.id, c.name, c.seq from b, p,
             lateral (values (p.col_todo_id, 'TODO', 0),
                             (p.col_in_progress_id, 'In progress', 1024),
                             (p.col_done_id, 'Done', 2048)) as c(id, name, seq)
             returning 1)
select b.id as board_id from b;
```

Note the gates differ by scope: account-level operations (creating a board) use
`elsci_check_account_limits(...)`; board-level operations (creating a card) use
`elsci_check_board_limits(...)`. Both raise `[ELSCI-402]` when over quota and are no-ops when
`billing_enabled` is false. See `BILLING.md` for the quota rules.

## Returning data as JSON

Reads and `RETURNING` clauses build JSON in SQL and the DAO returns the raw string — there is usually
no Java model to map into.

- `returning to_jsonb(table)` — returns **every column** of the row (handy for the swagger example).
- `to_jsonb(table) - 'col'` — drop a column (e.g. `- comments.body`, `- 'email'`, `- 'blob'`).
- `jsonb_build_object('k', v, ...)` wrapped in `jsonb_strip_nulls(...)` — hand-shaped objects that omit nulls.
- `coalesce(json_agg(to_json(x)), '[]')` — aggregate a child collection, never null. See `CardDao.get`:

```sql
select jsonb_strip_nulls(jsonb_build_object(
    'id', t.id, 'name', t.name, 'body', t.body, 'creator', t.creator,
    'attachments', (select coalesce(json_agg(to_json(a)), '[]') from attachments a where card = t.id),
    'comments',    (select coalesce(json_agg(to_json(c)), '[]') from comments c where card = t.id)
))
from cards t where id = ? and elsci_get_access(t.board, ?, ?) > 0
```

## Idioms

- **IDs**: generate in Java with `IdGenerator.generate()` and pass as a `?`. **There are no DB sequences
  and no `gen_random_uuid()`** — IDs are short base62 strings minted by the app.
- **Upsert**: `insert ... on conflict(<col>) do update set ...` using `excluded.<col>` (`UserDao`), or
  `on conflict(id) do update set refcnt = blobs.refcnt + 1` for refcounting (`AttachmentDao`).
- **Soft delete**: cards are never hard-deleted — `update cards set deleted = now() ... where deleted is null`,
  and reads filter `where deleted is null`. Composite indexes back this, e.g. `idx_cards__board_deleted`.
- **Arrays**: bind with `c.createArrayOf("text", javaArray)` and use `= any(?)` or `unnest(?)` in SQL
  (`CardDao.clone`, `UserDao`).
- **JSON params**: from a Java text block or `.sql` file, pass a JSON string and cast with `?::jsonb`,
  testing key presence with the `??` operator. When the payload instead drives a stored function
  (see below), bind it with `st.setObject(n, json, Types.OTHER)` and test presence with a single `?`
  inside the function body — e.g. `CardDao.patch` calls `select 1 from update_card(?, ?, ?)`, and
  `update_card` (in `R__elsci_procedures.sql`) does `case when payload ? 'name' then ... end`.
- **Reading a jsonb array once inside a PL/pgSQL function**: `jsonb_array_elements_text(...)` is a
  set-returning function — if a function body needs the same array more than once, don't re-invoke it
  each time. Parse it into a `text[]` variable up front and reuse it via `unnest(ids)` (as a rowset) or
  `= any(ids)` (as a membership test):
  ```sql
  label_ids := array(select jsonb_array_elements_text(payload->'labels'));
  ...
  select unnest(label_ids) as id except select id from labels where board = board_id  -- rowset
  ...
  delete from card_labels cl where cl.card = card_id and not (cl.label = any(label_ids));  -- membership
  ```
  See `update_card` in `R__elsci_procedures.sql`.
- **Validating a set of ids against a reference table**: use `except` between the requested ids and the
  valid ones to find what's missing/invalid, rather than `cross join` + `not exists`/`not (... and ...)`
  — it's directly aggregatable with `string_agg(...)` for the error message. When a request can be
  invalid in more than one way, layer the checks from most to least specific and raise the matching
  error for each — e.g. `update_card`'s label validation first checks for ids that don't exist *at all*
  (`[ELSCI-404]`), then for ids that exist but belong to a different board (`[ELSCI-400]`).
- **Ordering**: positions use a spaced `seq` (step `ColDao.SEQ_STEP`, e.g. `16384`) so rows can be inserted
  between neighbours without renumbering.
- **Multi-statement transactions**: when one connection runs several statements, manage the transaction
  explicitly — `c.setAutoCommit(false)` then `c.commit()` / `c.rollback()` on error, and `addBatch()` /
  `executeBatch()` for bulk inserts (`AttachmentDao`, `TrelloImportDao`).

## Migrations

Location `src/main/resources/db/migration/`. Two kinds:

- **Versioned** `VX.Y.Z__description.sql` — schema changes, run once in order. **Never edit an applied
  migration; add a new one.**
- **Repeatable** `R__description.sql` — re-run whenever their checksum changes. **All stored functions
  live in `R__elsci_procedures.sql`** (`elsci_get_access`, `elsci_get_account_limits`,
  `elsci_check_account_limits`, `elsci_check_board_limits`, `boardeaux_insert_card`,
  `clone_card_for_assignees`, ...). To add a new function or change a function's body, edit it in
  place here — don't add a `V` migration to redefine it. Before adding a function at all, check the
  "CTE or function?" criteria; new function names are prefixed `elsci_`.

**Changing a function's return type or parameters needs a one-time `drop` migration.** `create or
replace` only safely swaps a function **body**. It will **not**:

- **change the return type** — Postgres raises `cannot change return type of existing function`;
- **replace on a changed parameter list** — a different signature creates a *new overload* alongside
  the old function instead of replacing it, so callers may hit the stale definition.

Neither failure shows up in tests, because tests always start from a **fresh DB** — `R__elsci_procedures.sql`
creates the function cleanly with no pre-existing version to conflict with. The break only surfaces on a
real, already-migrated database. So when you change a function's **return type or signature**, add a
one-time **versioned** migration that drops the old definition (using its *old* parameter types):

```sql
-- VX.Y.Z__drop_elsci_get_board_usage.sql
drop function if exists elsci_get_board_usage(text);   -- old signature, so the R__ recreate is clean
```

Flyway runs all pending versioned migrations first and **repeatable migrations always run last**, so on
an existing DB the order is: old function present → the new `V` migration drops it →
`R__elsci_procedures.sql` recreates it with the new return type/signature. (Editing only the body needs
no `drop`.)

DDL conventions: name constraints `pk_<table>`, `fk_<table>__<col>`, `uq_<table>__<col>`,
`chk_<table>__<rule>`; timestamps are `timestamptz not null default now()`; schema-less config goes in
`global_prop(key, value)` as JSON text. **Any new permission-gated table must enforce access via
`elsci_get_access(...)` in the queries that touch it** — never re-check permissions in Java.

## Raising errors from SQL

Functions and constraints signal app errors with an `[ELSCI-4xx]` prefix that `PgErrorParser` matches
and `RestExceptionHandler.handleDbErrors` maps to HTTP:

| Code | Meaning | HTTP | Raised by |
|---|---|---|---|
| `[ELSCI-400]` | validation / semantic error | 400 | e.g. deleting the last column |
| `[ELSCI-402]` | quota exceeded | 403 (billing) | `elsci_check_*_limits` |
| `[ELSCI-403]` | no access / not a member | 403 | `elsci_get_access` |
| `[ELSCI-404]` | board doesn't exist | 404 | `elsci_get_access` |

```sql
raise exception '[ELSCI-403] User [%] don''t have permission [%] to access the board [%]', sid, min_access, resource;
```

Match these prefixes via `PgErrorParser.DB_ERROR_PREFIX_ELSCI_403` / `_404` / `_402` / `_400`. The DAO
generally lets the `SQLException` propagate; `RestExceptionHandler` translates it. The only DAO-side
mapping you add is `EntityNotFoundException` when a query returns no rows (next section).

## Gotchas

- **`elsci_get_access` short-circuits.** If `where id = ?` filters out every row (e.g. a fake id), the
  function is never evaluated — you get an **empty result set, not ELSCI-404**. The DAO converts that
  to `EntityNotFoundException("Card", id)`. So a missing card → `EntityNotFoundException` (404 via the
  handler), while a real card on a board you can't access → `[ELSCI-403]`/`[ELSCI-404]` from the function.
  Decide which path your `WHERE` takes before relying on a specific error.
- **Never put `elsci_get_access` in the WHERE of a multi-table join when it references only one side.**
  A predicate like `... from attachments a join cards c on c.id = a.card where a.id = ? and
  elsci_get_access(c.board, ?, ?) > 0` references only `cards`, so the planner may push it down to the
  cards *scan* and evaluate it for unrelated boards before the id filter — raising `[ELSCI-401]`/`403`
  for boards the requested row isn't even on (bit `AttachmentDao.getAttachment` under seqscan/hash-join
  plans). Fix: join the function in laterally — `cross join lateral elsci_get_access(c.board, ?, ?)` —
  so the lateral dependency pins its evaluation after the join (`AttachmentDao.getAttachment`; verified
  robust under plan-forcing experiments). Alternatives: a `with x as materialized (...)` CTE fence, or a
  predicate referencing both joined tables so it can only run at the join node (the `patchComment` case).
  When the caller already knows the board id, pass it as a parameter with the function in `FROM`
  (`BoardDao.get`'s `from elsci_get_access(?, ?, ?)`) — no table dependency, evaluated exactly once.
  Single-table queries are safe: within one scan, quals are cost-ordered, so the cheap `id = ?` runs
  before the plpgsql function.
- **`??` vs `?`**: in a Java text block the JSON-key-exists operator must be written `??` (a single `?`
  is a JDBC bind placeholder). In a `.sql` file *or inside a migration/stored-function body*
  (`R__elsci_procedures.sql`) — neither goes through JDBC literal parsing — use a single `?`. Getting
  this backwards inside a function doesn't fail at migration time, only when the function is actually
  called: `operator does not exist: jsonb ?? unknown`.
- **Alias set-returning functions with an explicit column name.** `jsonb_array_elements_text(...) e`
  (no column list) names the output column after the function itself, not `value` — `e.value` then fails
  with "column e.value does not exist". Alias it explicitly: `jsonb_array_elements_text(...) e(value)`.
- **Don't name a stored function parameter the same as a column it queries.** A function declared
  `update_card(id text, ...)` that does `where c.id = id` raises "column reference \"id\" is ambiguous" —
  Postgres can't tell if the unqualified `id` on the right means the parameter or the column. Give the
  parameter a distinct name (`card_id`) instead of relying on qualification to disambiguate; this
  repo's convention for stored-function parameters is a `p_` prefix or a name that can't collide with a
  column (`card_id`, `user_id`), never a bare `id`.
- **No string concatenation in production SQL.** Only `TestDao` cleanup concatenates ids — do **not**
  copy that style into a DAO.
- **Octet-stream handler bug**: endpoints declared `produces = APPLICATION_OCTET_STREAM_VALUE` can't
  serialize the error POJO when a DB error fires. Don't use that `produces` on endpoints whose SQL may
  raise. See CLAUDE.md's "Error → HTTP mapping" gotcha.
