---
name: rtk-bash-hook-use-proxy-for-exact-output
description: This user's global settings.json runs every Bash command through an `rtk` PreToolUse hook that filters/summarizes output - use `rtk proxy <cmd>` when a command's exact, complete output matters
metadata:
  type: reference
---

This user has a global Claude Code hook (`~/.claude/settings.json`, `PreToolUse` on the
`Bash` matcher, running `rtk hook claude`) that transparently rewrites every bash command
through `rtk` (a Rust CLI documented in `~/.claude/RTK.md`) to cut token usage - it can
silently truncate/redirect output to a side log file (seen: a `find` for a skill directory
came back filtered, with the real hits only visible in
`~/Library/Application Support/rtk/tee/*.log`).

**Why this matters for performance analysis specifically**: this work is precision-sensitive
(sample counts, percentages, ms durations feed directly into the report) - a silently
truncated `find`/`grep`/`node script` output could mean a missed file or an undercounted
category without any visible error.

**How to apply**: for any exploratory or data-producing command in a performance-analysis
task (locating skill/source files, running the skill's Node scripts, `jfr print` dumps,
`grep -c` counts over stack dumps) prefix with `rtk proxy` to get raw, unfiltered output -
e.g. `rtk proxy node .claude/skills/performance-analysis/scripts/gatling-stats.js ...`. This
is a standing environment quirk, not specific to this repo - expect it in any session for
this user.

**Caveat found 2026-09-24 (huge-body-patch 1M+-event run)**: `rtk proxy` is not itself a
guarantee of stable output on repeat reads. After dumping a large `jfr print` stack trace to
a scratch file with `rtk proxy jfr print ... > file.txt` (confirmed correct: `wc -l` gave
51729 immediately after), a *later*, unrelated `rtk proxy python3 - <<EOF ... EOF` heredoc
reading that same still-unmodified file parsed only ~440 of the ~890 real events - silent,
undercounted, and non-obvious (no error, just wrong totals) - then a subsequent `wc -l`
re-check under `rtk proxy` also came back short (37295 instead of 51729). Re-running the
exact same `jfr print` dump and analysis *without* any `rtk proxy` prefix gave the correct,
byte-for-byte-stable count (confirmed via two consecutive `md5` checksums of the file and two
consecutive runs of the parsing script). Root cause unconfirmed (possibly the hook doing its
own buffering/caching on stdin-heavy or heredoc invocations) - but the practical fix that
worked: for the *analysis* step over an already-dumped file (as opposed to the *dumping*
command itself), skip `rtk proxy` and call `python3`/`wc`/`grep` directly, and verify
determinism by running the read/analysis twice (or checksumming the source file) before
trusting the numbers, especially when a python heredoc or multi-stage pipeline is involved.
