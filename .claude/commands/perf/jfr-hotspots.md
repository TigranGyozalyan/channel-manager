---
description: Rank and categorize CPU hotspots (leaf frames) from a JFR recording
argument-hint: <jfr-file> [--top N] [--exclude REGEX] [--categorize]
---

Analyze CPU hotspots in the JFR file given in the arguments below: `$ARGUMENTS`

1. If you haven't already, load the `performance-analysis` skill for context on this
   project's perf-testing conventions.
2. Run `node .claude/skills/performance-analysis/scripts/jfr-leaf-frames.js` against the
   given file, passing through any flags in the arguments (`--top`, `--exclude`,
   `--categorize`). Default to `--top 25 --categorize` if the user didn't specify.
3. Report the ranked/categorized breakdown, then explain in plain English what's actually
   dominating - tie the top frames/categories back to what that code does (`Grep`/`Read`
   the relevant classes if the frame names alone aren't self-explanatory).
