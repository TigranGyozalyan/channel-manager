---
description: Summarize a Gatling perf test report's latency/throughput/error-rate stats
argument-hint: <gatling-report-dir-or-index.html-or-stats.json>
---

Summarize the Gatling report given below: `$ARGUMENTS`

1. If you haven't already, load the `performance-analysis` skill for context on this
   project's perf-testing conventions.
2. Run `node .claude/skills/performance-analysis/scripts/gatling-stats.js` against the
   given path.
3. Report the per-request-type and aggregate stats (total/ok/ko, mean/min/max, percentiles,
   requests/sec), calling out anything outside expectations (errors, latency spikes).
