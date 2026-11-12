---
name: verify-provenance-before-trusting-run-label
description: A Gatling report + JFR pair's directory/file name is not proof it reflects the branch/design it's claimed to - verify against the checked-out code before analyzing
metadata:
  type: feedback
---

Before analyzing a Gatling report + JFR pair, confirm the pair actually reflects the design
it's labeled as - don't just trust the directory name or the dispatch's description of what
branch/commit it came from.

**Why**: a prior run in the [[events-schema-rework-perf-investigation]] series was mislabeled
(named/described as one design but actually captured against different code) and had to be
caught by cross-checking specific code facts, not by trusting the filename or timestamp.

**How to apply**: when given a specific set of code facts to verify (e.g. "grep X should
return ~1 hit", "table/column Y should exist", "method Z should call helper W"), run all of
them before touching the Gatling/JFR data, and say explicitly whether each one passed. If the
JFR's actual behavior is inconsistent with the claimed design (e.g. no JDBC activity for a
code path that should fire on every request), report that mismatch explicitly rather than
proceeding as if the label were correct - this is itself a finding, not just a preflight
check.
