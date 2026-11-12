---
name: performance-analysis
description: >
  Use this skill when analyzing Gatling perf test reports and/or JFR (Java Flight Recorder)
  profiles from this project. TRIGGER when: the user shares a Gatling report path (an
  index.html under target/gatling/<sim>-<timestamp>/, or the report directory itself)
  and/or a .jfr file and asks to evaluate, analyze, or compare a performance run; asks
  "what's the hotspot", "why is this slow", "is this being cached correctly"; or wants a
  breakdown of where CPU time went. Covers: reading Gatling's stats.json for
  latency/throughput/error-rate, ranking and categorizing JFR execution-sample leaf frames
  into hotspot buckets, and burst-timing detection for confirming whether an expensive call
  is amortized (paid once per session) or repeated per-request. Bundles three Node scripts
  under scripts/ that do the actual parsing - always run them rather than re-deriving
  ad-hoc `jfr print` / grep / awk pipelines by hand.
---

# Performance analysis (Gatling + JFR)

This project's perf tests live under `src/test/java/io/elsci/boardeaux/perf/` (Gatling
simulations named `*Perf.java`, run via the `gatling` Maven plugin - see individual
simulation classes for how to launch them). The user typically also attaches a `.jfr`
recording captured separately (e.g. via IntelliJ's profiler while the Gatling run was
in flight), usually shared as a `file:///.../index.html` Gatling link alongside an
absolute path to a `.jfr` file.

## Where the data lives

- **Gatling report**: `target/gatling/<simulation-name>-<timestamp>/` - `index.html` is
  the human-facing report, `js/stats.json` is the machine-readable aggregate the scripts
  read (total/ok/ko, mean/min/max response time, percentiles, requests/sec - per request
  type and overall).
- **JFR file**: any `.jfr` path the user gives you. It's a CPU execution-time recording
  unless stated otherwise - `jfr print --events jdk.ExecutionSample` is what the scripts
  use. (Other event types like `jdk.ObjectAllocationSample` or `jdk.JavaMonitorEnter`
  answer different questions - allocation pressure, lock contention - and aren't covered
  by these scripts; run `jfr summary <file>` to see what event types a recording contains
  if CPU sampling doesn't explain what you're looking for.)

## Scripts (`scripts/`, run with `node`, no dependencies)

- **`gatling-stats.js <report-dir-or-index.html-or-stats.json>`** - prints one line per
  request group (and the "All Requests" aggregate): total/ok/ko, mean/min/max, p50/p75/p95/p99,
  mean requests/sec. Accepts the report directory, its `index.html`, or `js/stats.json`
  directly.
- **`jfr-leaf-frames.js <jfr-file> [--top N] [--exclude REGEX ...] [--categorize]`** -
  aggregates the innermost (leaf) frame of every execution sample: what the CPU was
  actually running at the moment it was sampled. `--categorize` buckets every frame into
  json/database/network-io/idle-blocking/framework-dispatch/collections/string-ops/other,
  plus one `app: <package>` bucket per distinct area of this project's own code - so a
  hotspot in application code always surfaces as its own labeled bucket instead of being
  lumped into "other". Use `--exclude` to drop a frame you've already root-caused (e.g. a
  known, expected cost like bcrypt) so it stops drowning out whatever you're investigating
  next.
- **`jfr-burst-timing.js <jfr-file> <frame-regex> [--gap-ms N]`** - clusters samples whose
  event block matches `frame-regex` into time-bursts (default 500ms gap). This answers "is
  this cost paid once per session, or once per request?" - a handful of short, widely
  spaced clusters means it's amortized; a cluster count that scales with the request count
  means it isn't cached and is being repeated on every call.

Run scripts directly via Bash, e.g.:
```
node .claude/skills/performance-analysis/scripts/jfr-leaf-frames.js /path/to/run.jfr --top 20 --categorize
```

## Methodology

1. **Gatling stats first** - run `gatling-stats.js` to get the headline numbers (mean/max
   latency, error rate, throughput) per request type. This tells you *whether* something
   is slow and *how much*, before looking at *why*.
2. **Hotspot ranking + categorized breakdown** - run `jfr-leaf-frames.js --categorize`
   (`--top 20`-`40` is usually enough to see the shape). Read the categorized summary
   first for the big picture, then drill into individual leaf frames within whichever
   category dominates.
3. **Burst-timing for anything suspicious** - if a cost looks like it should be cached
   (auth, a connection pool checkout, a lazily-computed value) but shows up as a sizable
   percentage of samples, run `jfr-burst-timing.js` against that frame. Compare the
   cluster count to the number of distinct sessions/users/logins the test created - roughly
   matching means it's healthy; a cluster count that tracks the total request count instead
   is a real bug (a cost being paid every request instead of once).
4. **Comparing two runs** (e.g. a "regular" case vs. a "worst case" like huge payloads) -
   run the categorized breakdown on both and compare category percentages side by side.
   Look for which category's share grew and by how much; that's what's actually different
   about the two runs, not a guess.
5. **Synthesize in plain English** - connect what Gatling showed (the symptom) to what the
   categorized JFR breakdown showed (the cause). Don't just dump both tables - say what's
   dominating and why that's expected or not.

## Known-good baseline for this project

`elsci_get_access`-gated auth via `BasicAuthenticationFilter` in this codebase caches the
authenticated session per client (see `io.elsci.web.security.SecurityConfig` and
`EagerSessionCookieBasicAuthenticationFilter`) - password verification (`BCrypt.key` leaf
frames) should burst-cluster once per new session (roughly: once for perf-harness setup,
plus once per concurrent virtual user), never once per request. If a burst-timing check on
`BCrypt\.key` (or any other per-request-looking auth cost) shows a cluster count scaling
with the request count instead, that's a real regression, not perf-test noise.
