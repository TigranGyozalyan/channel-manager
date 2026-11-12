---
description: Check whether a specific cost (e.g. auth) is amortized per-session or repeated per-request in a JFR recording
argument-hint: <jfr-file> <frame-regex> [--gap-ms N]
---

Run a burst-timing check against the JFR file and frame pattern given below: `$ARGUMENTS`

1. If you haven't already, load the `performance-analysis` skill for context on this
   project's perf-testing conventions and what a healthy cluster pattern looks like.
2. Run `node .claude/skills/performance-analysis/scripts/jfr-burst-timing.js` with the
   given file, regex, and any `--gap-ms` override.
3. Report the cluster breakdown and give a clear verdict: does the cluster count look like
   it's amortized (roughly one per session/login), or does it scale with the request
   count (meaning the cost is being repeated on every request instead of cached)? If it's
   the latter, say so plainly - that's the actionable finding.
