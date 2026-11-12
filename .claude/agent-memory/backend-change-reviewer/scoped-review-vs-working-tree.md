---
name: scoped-review-vs-working-tree
description: The calling agent's "small scoped change" description may cover only part of an uncommitted working tree — check git status/diff --stat against HEAD first to see the real blast radius before narrowing to the named files.
metadata:
  type: feedback
---

Observed on the `feature/events` branch (2026-09-02): a review task described as "a small change to
CardBodyHistoryEntry's author field" arrived while the working tree actually had ~560 uncommitted
lines spanning the whole body-history endpoint, a new diff-reconstruction package
(`io.elsci.boardeaux.diff`), and their tests — none of it yet in any commit (all commits on the branch
are generic "wip"). `git log --oneline --all -- <file>` returned nothing for the "new" files, confirming
they were never committed at all.

**Why:** the task-giving agent scopes review instructions narrowly around what it just touched in its
own turn, but that scope is a claim, not a fact — the actual git state can be broader (or the "already
existed" framing can be inaccurate) if earlier work in the same session was never committed.

**How to apply:** always run `git status` + `git diff --stat HEAD` (and `git log --oneline --all -- <path>`
for any file the task calls "pre-existing") before trusting a task's framing of what's old vs. new.
Then honor the task's explicit scope for what to *judge* (don't relitigate decisions marked as already
made), but read the full surrounding diff so findings aren't based on a false picture of what changed.
