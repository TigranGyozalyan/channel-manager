---
name: events-schema-rework-perf-investigation
description: Context behind the feature/events_alt perf runs (card-patch, huge-body-patch, get-events) - what changed in the events audit-log schema and why three runs are being compared
metadata:
  type: project
---

On branch `feature/events_alt`, boardeaux reworked its `events` audit-log table (baseline
design vs. this branch):

- **Baseline**: every event row stored a full valprev/valnew (and refprev/refnew) pair
  directly, so reconstructing "before" was a zero-cost read of the row itself. Only
  body-text diffs used a `diffbase` pointer, and that pointer always pointed at one shared
  "star" anchor row per diff-run (up to 5 diffs fetchable in one indexed lookup).
- **This branch**: dropped valprev/refprev from the schema entirely and generalized
  diffbase-linking to every attribute (name/seq/assignee/col/deleted/body) - every event
  now links to its immediate predecessor in a genuine linked list. The write path
  (`boardeaux_insert_card_event` in `R__elsci_procedures.sql`) now does one extra indexed
  SELECT before the INSERT to find that predecessor's id — a cost that did not exist
  before at all. Supporting index: `idx_events__entity_type_attr_id on
  events(entityid, entitytype, attr, id) include (diffbase)`, added in
  `V1.19.0__add_events.sql` (part of this branch's own migration, not baseline).
- Relevant code: `CardDao.patch` (JDBC entry point, src/main/java/io/elsci/boardeaux/CardDao.java),
  `boardeaux_insert_card_event`, `update_card`, `boardeaux_get_diffs_up_to_snapshot` (all
  in `R__elsci_procedures.sql`).

**Why**: user is running this same analysis across three sibling Gatling+JFR perf runs -
card-patch (mixed small-field + body-diff patches), huge-body-patch, and get-events - to
form one combined verdict on whether the generalized-linked-list rework is worth its added
per-write SELECT cost.

**How to apply**: when analyzing any of the sibling runs, frame findings in terms of this
same schema change (predecessor-lookup cost, diffbase generalization, index added in
V1.19.0) so results are directly comparable across the three reports. Don't re-derive the
schema story from scratch each time - it's the same rework being stress-tested from three
angles (regular writes, worst-case body-diff writes, and reads).

card-patch run findings (2026-09-23, cardpatchperf-20260923122716664 / Main_2026_09_23_162704.jfr):
mean latency 6ms (regular-field patch) vs 9ms (diff-field/body patch), 0 errors, 1000
requests at light load (rampUsers(5), 250-400ms pacing, ~13 req/s). JFR (394 total
ExecutionSamples) showed no isolated hotspot frame for the new predecessor SELECT - of the
33 samples with `CardDao.patch` anywhere on the stack (26 after excluding one-time Spring
startup noise), the observable cost split roughly evenly between c3p0 connection
checkout/release (13) and `PgConnection.commit()` round-trip wait (9), not query-text
execution itself. Dominant JFR categories overall were Spring MVC framework dispatch
(37.6%) and idle/blocking (23.6%) - both load-shape artifacts of a light, paced test, not
schema-change-specific. BCrypt auth (11.4% of samples) burst-timing-confirmed as amortized
per-session (6 clusters, matching 5 ramped users), not a regression.

get-events (read side) run findings (2026-09-23, cardgeteventperf-20260923123109418 /
Main_2026_09_23_163102.jfr) - this is the read-path counterpart: `GET /api/card/{id}/events`
→ `CardDao.getEvents` → SQL function `boardeaux_get_latest_card_events`
(`R__elsci_procedures.sql`), which now runs a recursive CTE (`ancestor_walk`, one indexed
point-lookup per page event's `diffbase` predecessor, further hops while `valdiff is not
null`) instead of the baseline's self-contained row read. 1000 requests, 0 errors, mean 8ms,
p50 6ms, p95 17ms, p99 82ms, max 178ms, 40 req/s. JFR had only 324 total ExecutionSamples
(sparser than card-patch's 394, over a 75s recording vs ~25s of actual 40rps load, so most
samples are idle/poller noise outside the load window) - categorized breakdown: framework
dispatch 33.0%, idle/blocking 18.8% (BCrypt burst-confirmed amortized: 5 clusters), json
(jackson) 9.6%, database (jdbc) leaf-only 1.9%. Because the recursive CTE executes entirely
inside Postgres, it cannot show up as CPU in this Java-process JFR at all - the only place
it could show is increased *off-CPU* wait on the JDBC socket read. Cross-checking the
recording's separate `profiler.WallClockSleeping` events (not covered by
`jfr-leaf-frames.js`, which only reads `jdk.ExecutionSample`) found 28 distinct blocking
bursts on http worker threads inside `QueryExecutorImpl.execute`/`processResults`, totaling
~431ms across the whole run, longest single burst ~49ms - none of which stands out as an
outlier against Gatling's own p95/p99. Grepping `CardDao.getEvents` inclusive (not just
leaf) found 29/324 samples; of those, Java-side replay (`CardHistoryEntry.buildHistory` -
JSON deserialize of the events+ancestors payload, `resolveAfter`'s memoized diffbase walk,
`Diff.apply`'s line-based patch replay) accounted for roughly half (14/29), noticeably more
than raw JDBC/postgres wire-protocol leaf frames (~5/29) or c3p0 checkout (~5/29). Response
JSON serialization (`writeWithMessageConverters` inclusive, 21/324) cost more CPU than the
DB layer's own leaf frames. Verdict signal: no CPU-side signature of the ancestor-walk CTE
being expensive under this load; if the redesign costs something, it isn't visible as raw
CPU in the JVM - would need `EXPLAIN (ANALYZE, BUFFERS)` under concurrent load or Postgres-side
wait-event stats (not covered by this skill) to see it directly.

huge-body-patch run findings for `feature/events_blobs` (2026-09-24,
cardhugebodypatchperf-20260924142621431 / Main_2026_09_24_182605.jfr), compared against the
same scenario on the two prior designs:

| | baseline (star) | alt (recursive chain) | blobs (recursive + blob upsert) |
|---|---|---|---|
| mean | 50ms | 55ms | 122ms |
| p50 | 48ms | - | 54ms |
| p95 | 96ms | 120ms | 423ms |
| p99 | 174ms | 187ms | 1578ms |
| max | 229ms | - | 3344ms |
| Myers diff % of CPU samples | 41.8% | ~50% | 58.5% (leaf) / 63.1% (inclusive) - comparable, unchanged code, small run-to-run variance expected |
| off-CPU JDBC wait (WallClockSleeping, http workers, gap-clustered **per-thread**) | 6.44s / 413 bursts / max 190ms | not measured (skipped) | 25.9s / 239 bursts / max 1740ms |

The off-CPU DB wait (not CPU) is what explains the blobs design's much higher p95/p99/max -
Myers diff CPU share barely moved. Splitting the 356 http-worker+`QueryExecutorImpl` wait
events by which `CardDao.patch` JDBC call site they came from (each `timer.timed(...)` lambda
is a distinct `lambda$patch$N` frame, cleanly distinguishable in the stack - no need to guess
from SQL text):
- `db_blob_upsert` (`CardDao.upsertBodyBlob`, the new insert): 106 events, 10.2s total, 103
  bursts, avg 96ms/event - the single most expensive per-call average, consistent with
  transferring the full (huge) body bytes in the insert payload.
- `db_patch` (`lambda$patch$3`, the `update_card` RPC that also does the alt design's
  predecessor-chain lookup internally): 219 events, 13.6s total, 208 bursts, avg 62ms/event -
  still the largest total contributor, but not distinguishable further from the chain-lookup
  cost alone since both happen inside the same RPC.
- `db_body_lock` (`lambda$patch$0`, the new `for no key update of c` row-lock read): 19
  events, 2.0s total, 19 bursts, avg 105ms/event - smallest total, but only lightly sampled
  (row lock reads are usually fast; few crossed the profiler's minimum-duration threshold).

Verdict signal: the blob upsert **is** cleanly distinguishable from the diff-chain lookup in
wait bursts (separate lambda call sites, not conflated) and accounts for ~39% (10.2s/25.9s)
of the categorized off-CPU DB wait on its own - a real, additive cost on top of the alt
design's chain-walk cost, not an artifact of measurement. Responsible code:
`CardDao.upsertBodyBlob` (src/main/java/io/elsci/boardeaux/CardDao.java) for the insert
itself; `BodyBlobUtil.blobId` for the MD5 hex computation, which also shows up as ~4.4% of
CPU leaf samples (`java.util.HexFormat.formatOptDelimiter`) - i.e. this design adds both a
client-side hashing cost and a server-side round trip that didn't exist in either prior
design, and both are exercised on literally every request in this scenario since every patch
changes the (huge) body.

**Third variant added 2026-09-24: `feature/events_blobs`** (branches off `feature/events_alt`,
i.e. it has the diffbase generalization AND adds one more change on top) - `cards.body`
moves from inline text to a content-addressed row in the existing `blobs` table
(`cards.body_blob`, `V1.20.0__cards_body_blob.sql`), reusing the attachments' dedup
mechanism (`md5(bytea)`, no pgcrypto needed) so a full-snapshot body event's `refnew` can
point at the same blob as `cards.body_blob` instead of storing a guaranteed-duplicate copy.
New cost: `CardDao.patch` (src/main/java/io/elsci/boardeaux/CardDao.java) now calls a
private `upsertBodyBlob(Connection, String)` helper - a second explicit JDBC round trip
(`insert into blobs(...) on conflict(id) do update set refcnt = refcnt + 1`) - before
calling `update_card`, on top of the pre-existing `db_body_lock` SELECT (now `select b.data
from cards c left join blobs b on b.id = c.body_blob where c.id = ? for no key update of c`,
joined against blobs instead of a plain column) that already existed to fetch the current
body for the Myers diff. So body-changing patches now do two extra round trips
(`db_body_lock` + `db_blob_upsert`) instead of the one the alt design added (the
diffbase-predecessor lookup, done server-side inside `update_card`/`boardeaux_insert_card_event`,
not a separate JDBC call).

card-patch run findings (2026-09-24, cardpatchperf-20260924141443624 / Main_2026_09_24_181357.jfr):
1000 requests (511 regular-field/489 diff-field/body), 0 errors. Gatling: regular mean 7ms
(p50 5, p75 8, p95 18, p99 57, max 102), diff mean 11ms (p50 9, p75 12, p95 27, p99 58, max
131), overall rps 13.16. JFR: 421 total ExecutionSamples over a 310s recording (light,
paced load same as prior two runs). Categorized: idle/blocking 43.0% (181), framework
dispatch 28.5% (120, includes BCrypt.key's package-prefix match), other/jvm internals 10.9%
(46), network io 5.2% (22), collections 5.0% (21), database (jdbc) leaf-only just 1.2% (5) -
same categorization-gap as before ([[jfr-leaf-frame-categorization-gaps]]). Grepping
`CardDao.patch` inclusive (full stack, not leaf) found 50/421 samples (11.9%) - noticeably
higher than baseline's 33/394 (8.4%) - breaking down by which of patch's 4 sequential
`timer.timed` lambdas each sample landed in (`lambda$patch$0..3` map 1:1 to source order:
`db_body_lock`, myers diff, `db_blob_upsert`, `db_patch`/`update_card`), plus samples caught
directly in `CardDao.patch` outside any lambda (commit call at line 96, connection
release/try-with-resources close at line 97, Spring CGLIB proxy entry overhead at lines
70/89, and Map bookkeeping at line 66):
  - `db_body_lock` (predecessor/current-body SELECT, now blob-joined): 9/421 (2.1%)
  - Myers diff calculation: 3/421 (0.7%)
  - `db_blob_upsert` (upsertBodyBlob - the NEW round trip, confirmed present, absent in both
    prior designs): 5/421 (1.2%)
  - `db_patch`/`update_card` call (includes the alt design's server-side diffbase-predecessor
    lookup, invisible as a separate JDBC round trip since it runs inside the same
    stored-function call): 11/421 (2.6%)
  - `c.commit()` round-trip wait (line 96, outside any timer): 9/421 (2.1%) - same magnitude
    as baseline's ~9 commit-wait samples
  - connection release/close (line 97): 5/421 (1.2%)
  - Spring proxy entry overhead + Map bookkeeping (lines 66/70/89): 8/421 (1.9%)
  - Total reconciles exactly: 9+3+5+11+9+5+8 = 50.
  Cross-checked `upsertBodyBlob` appears in 6 total samples project-wide; 5 from
  `CardDao.patch` (the per-patch cost above) and 1 from `CardDao.insert` (card-creation
  setup, not part of the measured PATCH scenario). BCrypt burst-timing: 50/421 samples
  (11.9%), 6 clusters (`14:14:42` to `14:14:52`, ~60-90ms each) - matches the ramped-VU
  count, same amortized-per-session verdict as both prior runs, not a regression.
  **Verdict signal**: the new `db_blob_upsert` round trip IS separable and measurable
  (1.2% of all samples, ~5/489 diff-field requests) but is smaller than the pre-existing
  `db_body_lock` SELECT (2.1%) and the `update_card` call itself (2.6%) - it is a real,
  additive cost but not a dominant one at this (light, ~13 rps) load; whether it matters
  more under concurrent/heavier load (row lock contention on `blobs.refcnt` updates) is not
  visible in this single-threaded-recording light-load profile.

**huge-body-patch at 1M+ events (2026-09-24, `feature/events_blobs`,
cardhugebodypatchperf-20260924174543595 / Main_2026_09_24_214534.jfr)** - the missing third
data point for the huge-body-patch comparison: same scenario (300 requests, 5 ramped VUs
over 10s, 20-card pool, 300k-800k char bodies - unchanged from the 168K-event run), run
against a DB seeded to ~1M+ events instead of ~168K. Provenance re-verified against
`190aaca` (see [[verify-provenance-before-trusting-run-label]] methodology) before analysis -
all 4 checked facts (no live valprev/refprev, recursive chain in
`boardeaux_get_diffs_up_to_snapshot`, `V1.20.0__cards_body_blob.sql` present,
`CardDao.upsertBodyBlob` called from `patch`) passed.

Result: **the regression did not get worse at higher scale - it got dramatically better**,
contradicting the naive expectation that more background rows would mean more contention/cost:

| | 168K events | 1M+ events |
|---|---|---|
| Gatling mean | 122ms | 54ms |
| p95 | 419-423ms | 128ms |
| p99 | 1509-1578ms | 322-333ms |
| max | 3344ms | 477ms |
| Myers diff CPU % (leaf/inclusive) | 58.5% / 63.1% | 52.0% / 63.2% (inclusive essentially identical - confirms diff cost itself didn't change) |
| off-CPU DB wait total (WallClockSleeping, http workers, per-thread clustered) | 25.9s / 239 bursts / max 1740ms | 6.22s / 238 bursts / max 613ms |
| `db_blob_upsert` (lambda$patch$2) | 106 events, 10.2s, 103 bursts, avg 96ms/event | 47 events, 1.25s, 47 bursts, avg 26.5ms/event |
| `db_patch`/update_card (lambda$patch$3) | 219 events, 13.6s, 208 bursts, avg 62ms/event | 194 events, 4.44s, 179 bursts, avg 22.9ms/event |
| `db_body_lock` (lambda$patch$0) | 19 events, 2.0s, 19 bursts, avg 105ms/event | 6 events, 0.44s, 6 bursts, avg 73.1ms/event |
| blob-upsert's share of total DB wait | ~39% (10.2/25.9s) | ~20% (1.25/6.22s) |

Burst *count* stayed almost exactly flat (239 -> 238 - same call frequency behavior, no
scaling-with-request-volume issue), but per-event average latency and total wait dropped
2.7-3.6x across every call site. This rules out "the blob/chain design degrades further as
the table grows" as a live concern at this scale - if anything the opposite happened.
Plausible (unconfirmed - would need Postgres-side `EXPLAIN ANALYZE`/wait-event stats to
verify) explanations worth naming without asserting: warmer buffer cache / settled
autovacuum by the time the 1M+-event run was captured, or a more stable query plan for the
predecessor-chain and blob-upsert index lookups at steady-state table size vs. whatever
transient state the table was in during the smaller 168K run. Responsible code for the
by-call-site split: `CardDao.patch`'s four `timer.timed(...)` lambdas
(src/main/java/io/elsci/boardeaux/CardDao.java) - `lambda$patch$0`=db_body_lock,
`$1`=myers, `$2`=db_blob_upsert, `$3`=db_patch, cleanly distinguishable in both
`jdk.ExecutionSample` and `profiler.WallClockSleeping` stacks by frame name, no SQL-text
guessing needed.

get-events on `feature/events_blobs` @ ~1M+ events (2026-09-24, matched-scale rerun,
cardgeteventperf-20260924174745694 / Main_2026_09_24_214740.jfr) - provenance verified
directly against checked-out code (commit 190aaca) before analysis: `boardeaux_get_latest_card_events`
has the `ancestor_walk` recursive CTE AND resolves body full-snapshot text via
`convert_from(bl.data,'UTF8')` joined to `blobs` on `refnew`; `CardHistoryEntry.java` has
`resolveAfter`/`AncestorEvent`, no `valprev`. Gatling: 1000 requests, 0 errors, mean 6ms,
p50 5/p75 7/p95 13/p99 64/max 93ms, 40 rps - essentially identical to baseline @ 1M+ (mean
6ms, p95 11, p99 48-52, max 131). JFR only had 200 total ExecutionSamples (sparse, light
40rps/5-VU load, same shape as prior get-events runs). `CardApi.getEvents`/`CardDao.getEvents`
inclusive: 55/200 (27.5%, matches baseline's 28.0%). Split of those 55 (verified via combo
breakdown, no double-counting - the two sub-buckets are mutually exclusive and sum exactly
to 55): `CardHistoryEntry.buildHistory` 30/55 (54.5%, of which 26/30 is the Jackson JSON
deserialize step itself and 4/30 is `resolveAfter`/`Diff.apply`/history-record construction)
vs JDBC/c3p0 25/55 (45.5%, of which 21/25 is actual postgres wire activity and 4/25 is pure
c3p0 pool checkout/release). This is a different split than baseline's stated 73%/27% -
likely sampling noise given only 55 matching samples total (not a table-size signal; the
off-CPU wait-burst check below is the more reliable signal for that question). BCrypt
burst-timing: 5 clusters (17:47:47 to 17:47:55, ~60-80ms each) matching the 5 ramped VUs -
amortized, not a regression, consistent with every prior run in this series.

Off-CPU JDBC wait check (`profiler.WallClockSleeping`, http-8080-N worker threads only,
`QueryExecutorImpl` on stack, clustered **per-thread**): of 371 raw matching events, only 8
carried an explicit multi-sample `duration` field (the rest were single-sample
`samplesCount=1` blips with no `duration` line at all - i.e. the profiler didn't see the
thread continuously parked long enough to register a duration, so they're noise, not real
waits). Filtering to just those 8 real-duration events and clustering per-thread (500ms gap)
gave **8 bursts, ~88ms total wait, max single burst ~19.9ms** - this reproduces baseline
@ 1M+'s independently-reported 9 bursts/~119ms/max 20ms almost exactly, and is in the same
order of magnitude as blobs @ 168K's 6 bursts/~181ms/max 30ms. **Methodology note**: don't
cluster on raw `duration=0` (single-sample) WallClockSleeping events - they vastly
outnumber (363/371 here) and dilute/mislead per-thread clustering into multi-second false
mega-bursts per thread (tested and discarded); only events with an explicit `duration` field
represent sustained off-CPU blocking worth burst-timing. See
[[wallclock-burst-cluster-per-thread]] for the pooling pitfall, and add this duration-field
filter as a second precondition alongside per-thread grouping.

**Verdict for the three-way get-events comparison**: baseline @ 1M+ and blobs @ 1M+ are
statistically indistinguishable on both Gatling latency and off-CPU JDBC wait-burst count/
duration - the added blob-join correlated subquery in `boardeaux_get_latest_card_events`
shows no table-size-dependent cost at 1M+ events, matching the "no table-size cost" result
already established for baseline. blobs @ 1M+ vs blobs @ 168K also don't differ meaningfully
(same order of magnitude on latency and wait bursts). The open question in the dispatch is
answered: this design's read-path holds up at 1M+ scale with no regression from the blob
hop.

card-patch run re-tested at ~1M+ events for `feature/events_blobs` (2026-09-24,
cardpatchperf-20260924174334240 / Main_2026_09_24_214319.jfr, verified against commit
190aaca) - same scenario, same design, much larger dataset (matching the scale the baseline
design was already re-verified against: 1000 req, 492/508 split, mean 7/9ms, p99 60/62ms, no
regression vs. baseline's own smaller-scale run). This run: 1000 requests (487 regular/513
diff), 0 errors, mean 10ms/16ms (up from blobs@168K's 7ms/11ms), p50 6/10, p95 28/47, p99
71/76ms (up from blobs@168K's 57/58ms), max 100/110ms, rps 12.99. So going from 168K→1M+
events, the *blobs* design's own mean grew +43%/+45% and p99 grew +25%/+31% - and at this
same 1M+ scale it now sits clearly above baseline (mean +43%/+78%, p99 +18%/+23%), whereas
blobs@168K's gap over an equivalent-scale baseline was small (mean +0%/+22% only, per the
168K finding above). JFR: only 331 ExecutionSamples over a 214s recording (lighter than the
168K run's 421/310s, same light ~13rps paced load) - BCrypt burst-timing still amortized (6
clusters, matches ramped VUs, not a regression). `CardDao.patch` inclusive: 44/331 (13.3%,
up from 168K's 11.9%) - this JFR's stacks show literal `CardDao.patch(...) line: N` frames
directly (no synthetic `lambda$patch$N` names this time - JIT/inlining artifact, not a
methodology change), classified by line number instead:
  - `db_body_lock` (line 70): 11/331 (3.3%, up from 168K's 2.1%)
  - Myers diff (lines 82-83): 3/331 (0.9%, flat)
  - `db_blob_upsert` (line 86): 4/331 (1.2%, flat vs 168K's 1.2%)
  - `db_patch`/`update_card` (line 89, includes server-side diffbase-chain lookup): 15/331
    (4.5%, up from 168K's 2.6%)
  - `c.commit()` (line 96): 8/331 (2.4%, flat vs 168K's 2.1%)
  - connection close (line 97): 2/331 (0.6%)
  **CPU-side signal: `db_blob_upsert`'s CPU share did NOT grow at 1M+ scale - it's
  `db_body_lock` and `db_patch`/`update_card` (both touching tables/indexes that grow with
  total event volume) that grew.**

  New for this run: an off-CPU JDBC wait-burst check (`profiler.WallClockSleeping`, filtered
  to `http-8080-N` threads with `QueryExecutorImpl` on the stack, clustered **per-thread**
  per [[wallclock-burst-cluster-per-thread]] - 8 distinct worker threads seen) - not
  previously measured for card-patch at any scale (only CPU samples were, until now).
  Classified the same way by `CardDao.patch` line number (747 matching events total):
  - `c.commit()` (line 96): 295 events, 1927.9ms total, 270 bursts, max burst 40.1ms, avg
    6.5ms/event - largest total (59.1% of the 4 phases' summed wait) but that's volume, not
    per-call cost.
  - `db_blob_upsert` (line 86, i.e. `upsertBodyBlob` called from `patch`, excluding 7
    events/59.8ms of `CardDao.insert`-path noise from initial card-creation setup, same
    exclusion logic as the 168K run): 120 events, 988.2ms total, 117 bursts, max burst
    39.9ms, avg **8.2ms/event** - second largest total (30.3% of summed wait) and the
    **highest average per-call round-trip time of all four phases**, higher even than
    `c.commit()`'s 6.5ms/event.
  - `db_patch`/`update_card` (line 89): 229 events, 283.3ms total, 220 bursts, max 83.7ms,
    avg 1.24ms/event (8.7% of summed wait).
  - `db_body_lock` (line 70): 74 events, 64.3ms total, 72 bursts, max 40.7ms, avg
    0.87ms/event (2.0% of summed wait).
  Burst count for `db_blob_upsert` (117, per-thread clustered) is well under the ~513
  diff-field request count - confirms one round trip per request, not repeated per-request
  (not a caching bug); most calls are just fast enough to fall under the profiler's minimum
  sampling threshold.
  **Verdict signal**: on CPU, `db_blob_upsert` is unchanged and still the smallest of the
  three DB-facing phases at 1M+ scale - no CPU regression. But the (newly measured, no
  168K-scale baseline exists for direct comparison) off-CPU wait view shows it as the single
  most expensive *individual* DB round trip in the whole `patch` call (8.2ms avg, vs 1.24ms
  for `update_card` and 0.87ms for the body-lock read) - a different signal than the
  CPU-sample view alone would suggest, consistent with
  [[jfr-leaf-frame-categorization-gaps]]'s general point that DB-side cost often only shows
  up as off-CPU wait, not CPU. No 168K-scale off-CPU-wait number exists for card-patch to
  confirm whether *this specific* 8.2ms average grew with scale (only the huge-body-patch
  scenario had off-CPU wait measured at smaller scale, and that used far larger body
  payloads, so it isn't a like-for-like comparison) - flagged as a real data gap, not
  papered over. What IS comparable and conclusively shows growth is the Gatling-observed
  latency itself (above) and the widening gap vs. baseline at matched 1M+ scale.

**Third variant confirmed on-disk 2026-09-25 (still `feature/events_blobs`, uncommitted on
top of `190aaca`)**: `cards.body` moves from content-addressed blobs to a **mutable per-card
blob slot that only forks when shared with a historical event**. Write path changed
(`CardDao.writeBodyBlob`, replaces `upsertBodyBlob` - src/main/java/io/elsci/boardeaux/CardDao.java
line 360; `BodyBlobUtil.java` deleted, no more client-side MD5 hashing). Read path
(`boardeaux_get_latest_card_events`, the `ancestor_walk` recursive CTE, and the
`convert_from(bl.data,'UTF8')` blob join keyed off `refnew`) is byte-for-byte unchanged from
the content-addressed variant - confirmed by grep, not assumed, per
[[verify-provenance-before-trusting-run-label]].

get-events run (2026-09-25, cardgeteventperf-20260925084414995 /
Main_2026_09_25_124412.jfr), **DB reseeded to ~3M events** (not the ~1M+ the prior two
designs were measured at - a real scale confound, not matched like-for-like). Gatling: 1000
requests, 0 errors, mean 7ms, p50 5/p75 8/p95 20/p99 67/max 140ms, 40rps - close to but
slightly above content-addressed-blobs@1M+ (mean 6, p95 13, p99 64, max 93) and baseline@1M+
(mean 6, p95 11, p99 48-52, max 131); still same order of magnitude, no error-rate or
throughput anomaly. JFR: 237 total ExecutionSamples (light 40rps/5-VU load, same shape as
prior get-events runs). `CardApi.getEvents`/`CardDao.getEvents` inclusive: 59/237 (24.9%,
matches prior runs' 27-28%). Split of those 59 (mutually exclusive, sums exactly): Java-side
`CardHistoryEntry.buildHistory` 37/59 (62.7%, of which 34/37 is Jackson JSON
serialize/deserialize and 1/37 is `resolveAfter`/`Diff.apply` replay) vs JDBC/c3p0 22/59
(37.3%, of which 15/22 reach actual postgres wire frames and the rest stop at c3p0
checkout/release). BCrypt burst-timing: 5 clusters (08:44:15 to 08:44:23, ~60-90ms each)
matching the 5 ramped VUs - amortized, not a regression, consistent with every prior run.

Off-CPU JDBC wait check (`profiler.WallClockSleeping`, http-8080-N threads only,
`QueryExecutorImpl` on stack, only events with an explicit `duration` field, clustered
**per-thread**, per [[wallclock-burst-cluster-per-thread]]): of 446 raw matching events, only
34 carried an explicit `duration` (rest were `duration`-less single-sample blips, same
noise pattern as every prior run). Clustering those 34 per-thread (8 threads) gave **29
bursts, ~436ms total wait, max single event ~25.1ms**. This is notably more than
content-addressed-blobs@1M+'s 8 bursts/~88ms/max 19.9ms (max burst size is comparable, but
burst *count* and *total wait* are ~3.6x and ~5x higher respectively) - the one number in
this run that does NOT look statistically identical to the matched-scale prior result.
Given the ~3x larger table (3M vs 1M+ events) is a genuine, acknowledged scale difference
(not a code change - read-path SQL is confirmed unchanged), this is most plausibly explained
by more index/page traversal work in the `ancestor_walk` CTE and/or colder buffer cache in a
freshly reseeded database, not a regression from the write-path redesign. Still, none of
these individual bursts (max 25ms) stands out against Gatling's own p95/p99 (20ms/67ms), so
it does not explain the small p95/max upward drift on its own either.

**Verdict for this run**: Gatling latency and CPU-side hotspot shape (framework dispatch,
idle/blocking, buildHistory JSON cost dominating over raw JDBC) are consistent with every
prior get-events run in this series - no CPU regression from the mutable-slot write-path
change, as expected since the read path is unchanged. The off-CPU DB-wait burst count/total
did increase from the last matched comparison, but that comparison was NOT at matched scale
(1M+ vs ~3M events) - flag this as an unmatched-scale caveat for whoever reads this next,
not as evidence of a read-path regression. To close this out cleanly, the get-events
comparison should be re-run for at least one prior design (content-addressed blobs or
baseline) reseeded to the same ~3M events, rather than reusing the older 1M+ numbers.

huge-body-patch run for this same mutable-slot design (2026-09-25,
cardhugebodypatchperf-20260925084322383 / Main_2026_09_25_124313.jfr) - this is the scenario
that mattered most: on the content-addressed design this was the investigation's single
worst regression (122ms mean/1578ms p99@168K). Provenance re-verified directly against the
working tree (same uncommitted state as the get-events run above, no new commit) before
analysis: `writeBodyBlob(Connection, String, int, String)` signature confirmed, no
content-hashing anywhere in it, mutate-in-place branch (`update blobs set data=?,byte_len=?
where id=?`, taken when `refcnt<=1`) vs fork branch (fresh `IdGenerator.generate()` id, plain
`insert into blobs(...)`, no `ON CONFLICT`, taken when `refcnt>1`) both confirmed by reading
the method body; `update_card`'s `update blobs set refcnt = refcnt + 1 where id =
payload->>'bodyBlob'` right after the full-snapshot-event insert confirmed in both branches
of `R__elsci_procedures.sql`. Same DB as the get-events run above: reseeded fresh to ~3M
events for this variant, no leftover bloat.

Result: **the regression is essentially gone.** 300 requests, 0 errors, mean 47ms, p50
41/p75 59/p95 97/p99 238/max 317ms, 9.68rps - close to baseline@168K (mean 50/p95 96/p99
174/max 229) despite running at ~3M events, and far below content-addressed-blobs@168K (mean
122/p95 423/p99 1578/max 3344) or even blobs@1M+ (mean 54/p95 128/p99 322-333/max 477):

| | baseline@168K | blobs(content-addr)@168K | blobs(content-addr)@1M+ | **mutable-slot@~3M** |
|---|---|---|---|---|
| mean | 50ms | 122ms | 54ms | **47ms** |
| p95 | 96ms | 423ms | 128ms | **97ms** |
| p99 | 174ms | 1578ms | 322-333ms | **238ms** |
| max | 229ms | 3344ms | 477ms | **317ms** |
| Myers diff CPU % (leaf/inclusive) | 41.8% | 58.5%/63.1% | 52.0%/63.2% | 57.5%/66.4% (same ballpark - diff cost is body-size-driven, not table-size-driven, unaffected by the write-path redesign) |
| off-CPU DB wait total (WallClockSleeping, http workers, per-thread, duration-field-filtered) | 6.44s/413 bursts/max190ms | 25.9s/239 bursts/max1740ms | 6.22s/238 bursts/max613ms | **4.22s/231 bursts/max200ms** |

527 ExecutionSamples; `CardDao.patch` inclusive (stack-depth 40) = 402/527 (76.3%, expected -
patch's call tree contains body-lock/diff/blob-write/update_card); `writeBodyBlob` itself is
only 9/527 (1.7%) of CPU samples - its true cost is off-CPU wait, not CPU
([[jfr-leaf-frame-categorization-gaps]]). No `HexFormat`/MD5 frames anywhere in this
recording (present at ~4.4% CPU in the content-addressed design) - the client-side hashing
cost is fully gone, not just reduced, matching `BodyBlobUtil.java`'s deletion.

**The key mutate-vs-fork off-CPU comparison** (isolates whether the old design's cost was
"shipping huge bytes to Postgres" itself, vs. the INSERT/hash/on-conflict machinery around
it - both branches here ship the identical huge payload). Classified 245
`profiler.WallClockSleeping` events (http worker threads, `QueryExecutorImpl` on stack,
explicit `duration` field only, per-thread clustered per
[[wallclock-burst-cluster-per-thread]]) by exact `writeBodyBlob` line number (367=`UPDATE`
mutate branch, 375=plain `INSERT` fork branch - directly distinguishable from the stack):
- mutate (`UPDATE blobs set data=...`, line 367, `refcnt<=1`): 37 events, 669.7ms total, 36
  bursts, max 200ms, **avg 18.10ms/event**.
- fork (plain `INSERT into blobs(...)`, no `ON CONFLICT`, line 375, `refcnt>1`): 12 events,
  270.1ms total, 12 bursts, max 130ms, **avg 22.51ms/event**.
- `db_patch`/`update_card` (line 106): 189 events, 3232.3ms total, 179 bursts, avg
  17.10ms/event (largest total by volume, unrelated to the blob write itself).
- `db_body_lock` (line 71): 4 events, 48.3ms total, avg 12.08ms/event (lightly sampled, as
  in every prior run).
Roughly 3:1 mutate:fork ratio observed (both in this filtered sample and in the wider
unfiltered grep: 119 vs 33 raw `writeBodyBlob` wait-events called from `patch`) - consistent
with `update_card`'s full-snapshot-every-~6-diffs cadence
(`boardeaux_get_diffs_up_to_snapshot`'s `diff_chain_count >= 6` triggers a snapshot that
bumps `refcnt`, forcing the *next* write on that card to fork) - an expected consequence of
the chain-length cap, not a bug.

**Verdict on the key question**: mutate (18.10ms/event) and fork (22.51ms/event) cost nearly
the same, and both are dramatically cheaper than the content-addressed design's
`db_blob_upsert` (`INSERT ... ON CONFLICT DO UPDATE` + client-side MD5 hash) at
96ms/event@168K or 26.5ms/event@1M+. Since mutate and fork both transfer the identical huge
payload yet land within ~25% of each other, **the old design's cost was not fundamentally
"shipping huge bytes to Postgres"** - mutation doesn't reduce payload size and is still
cheap - **it was specifically the `ON CONFLICT`/upsert machinery plus the client-side hash
computation**, both now completely absent (confirmed by code and by the missing `HexFormat`
CPU frames). Responsible code: `CardDao.writeBodyBlob`
(src/main/java/io/elsci/boardeaux/CardDao.java) and `update_card`'s `refcnt` bump
(`R__elsci_procedures.sql`) - this mechanism is what made the investigation's worst-observed
regression effectively disappear.

card-patch run for this same mutable-slot design (2026-09-25,
cardpatchperf-20260925084146926 / Main_2026_09_25_124143.jfr) - the mixed regular/diff-field
scenario, at the same freshly-reseeded **~3M events**. Provenance re-verified independently
(same 3 facts: `writeBodyBlob` signature/branch logic, `refcnt+1` placement, bare `if payload
? 'bodyBlob'`) - all passed.

Gatling: 1000 requests (521 diff/479 regular), 0 errors, rps 13.51. Regular-field mean 6ms
(p50 5/p75 7/p95 11/p99 26/max 105), diff-field mean 9ms (p50 7/p75 9/p95 17/p99 78/max 104).
Matches or beats content-addressed-blobs@168K (7ms/11ms, p99 57/58) on every metric except
diff-field p99 (78ms vs 58ms - noisy at n=521, not corroborated by any JFR signal below), and
is dramatically better than that design's 1M+-event run (10ms/16ms, p99 71/76ms) **despite
this run having 3x the event volume of that comparison point**.

JFR: only 297 total ExecutionSamples over 159s (light-load, same shape as every run in this
series - phase percentages below are directional given single-digit sample counts per phase).
Categorized: framework dispatch 39.7%, idle/blocking 32.0%, database (jdbc) leaf-only 1.3% -
same categorization-gap as always. BCrypt burst-timing: 6 clusters matching the 5 ramped VUs,
amortized as in every prior run. `CardDao.patch` inclusive: 33/297 (11.1%), by
`lambda$patch$N`/line: db_body_lock 6/297 (2.0%), myers 0/297 (0%), `writeBodyBlob` 5/297
(1.7%), db_patch/`update_card` 8/297 (2.7%), `c.commit()` 7/297 (2.4%), conn acquire/release
7/297 (2.3%). A sampled `writeBodyBlob` leaf confirmed the mutate (`UPDATE`) path, not a fresh
INSERT. Zero `HexFormat`/`MessageDigest`/`md5` frames anywhere in the recording (matches the
huge-body-patch run's finding - hashing cost is structurally gone, not just infrequent).

Off-CPU JDBC wait (`profiler.WallClockSleeping`, http workers, `QueryExecutorImpl` on stack,
duration-field-filtered, per-thread clustered): far sparser than the huge-body-patch run on
this same design (23 qualifying events total here vs 245 there) - consistent with these
being small-payload patches that mostly complete too fast to register a sustained wait: commit
15 events/14.7ms avg/max 20.7ms, db_patch 7 events/9.6ms avg/max 11.5ms, `writeBodyBlob` only
1 qualifying event (9.7ms), db_body_lock zero qualifying events. Too sparse to compare
per-call averages confidently against other runs, but nothing suggests a blowup.

**Verdict, answering this run's two dispatch questions**: (1) mutate-in-place's CPU-sample
share for `writeBodyBlob` (1.7%) isn't distinguishable from the content-addressed design's
1.2% at this sample size, but the hash-computation cost is structurally eliminated (0
`HexFormat` hits) and the sampled query is confirmed to be a single-row UPDATE, not an
INSERT-with-conflict-check - a real, structural cost removal for the common case, consistent
with the huge-body-patch run's much larger-sample finding that mutate/fork cost roughly the
same and both are far cheaper than the old upsert-with-hash. (2) The 1M+-scale
`db_body_lock`/`db_patch` growth previously seen on the content-addressed design (2.1%→3.3%,
2.6%→4.5%) does **not** reappear here even at 3M events (2.0%/2.7%, matching that design's
*168K*-event levels) - combined with the Gatling latency comparison above, this is now
corroborated by two independent scenarios (this run and the huge-body-patch run) pointing at
content-addressed blob-table bloat (1.25M orphaned rows/2.74GB) as the actual cause of that
earlier growth, not a general table-size/index-depth effect - removing the leak removed the
regression even at larger absolute scale. Gatling's n=1000 percentiles are the statistically
robust half of this verdict; the JFR phase percentages are corroborating, not independently
conclusive at 297 total samples.
