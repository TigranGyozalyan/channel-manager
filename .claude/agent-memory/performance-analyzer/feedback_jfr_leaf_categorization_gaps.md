---
name: jfr-leaf-frame-categorization-gaps
description: jfr-leaf-frames.js --categorize buckets by leaf (innermost) frame only - drill into full stacks by function name when a specific method's total cost is needed
metadata:
  type: feedback
---

`jfr-leaf-frames.js --categorize` (from the `performance-analysis` skill) categorizes each
sample by its innermost frame only. This under-counts a specific method's true footprint in
two ways worth checking for on any non-trivial investigation:

1. **Generic bottom frames hide the caller.** Samples whose leaf is
   `sun.nio.ch.SocketDispatcher.read0`/`write0` or `sun.nio.ch.Net.poll` all land in the
   "network io" bucket regardless of whether the socket belongs to Postgres (JDBC) or
   Tomcat (HTTP). To split these, grep the full stack (`jfr print --events
   jdk.ExecutionSample --stack-depth N`) for `postgresql` vs `tomcat|coyote` rather than
   trusting the category label alone.
2. **A method's total observed cost is spread across many different leaf frames.** e.g.
   `CardDao.patch`'s true footprint (connection checkout, query execution, commit, release)
   only showed up as 1 sample under the app-level leaf bucket, but 33 samples had
   `CardDao.patch` somewhere in the stack once grepped directly — the rest were leaf-counted
   under c3p0 (`AbstractNewProxyConnection.close`), `PgConnection.commit`, etc.

**Why**: caught during the card-patch run analysis in
[[events-schema-rework-perf-investigation]] — the categorized table alone would have
under-reported DB-related cost by roughly 3x.

**How to apply**: after the categorized breakdown, if a specific application method's cost
is in question, grep full `jfr print --stack-depth N` output for that method name across
all samples (not just its leaf-frame count) before concluding its cost is negligible.
