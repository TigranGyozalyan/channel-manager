---
name: plpgsql-declared-but-unassigned
description: When reviewing plpgsql function edits in R__elsci_procedures.sql, verify every declared variable is actually assigned via select-into before use, not just that the logic reads correctly.
metadata:
  type: feedback
---

While reviewing the `feature/public_boards` branch (2026-07-16), found that `elsci_get_access`'s
public-row fallback logic in `R__elsci_procedures.sql` declared `public_access smallint;` but never
populated it with a `select ... into public_access from board_members where member is null`. The
`least(public_access, reader_access)` and `greatest(explicit, public_access)` calls read as correct
plpgsql on a surface pass — the bug only shows up if you check that every `declare`d variable has a
matching assignment somewhere in the function body before its first use.

**Why:** This is an easy category of bug to miss because the *logic* (the `greatest`/`least` shape) was
exactly right per the design doc — only the wiring (the missing `select into`) was missing. Skimming
plpgsql for "does the algorithm make sense" without tracing each variable's assignment site will miss
this class of bug entirely, and it silently makes an entire feature's permission fallback into dead
code (falls back to old behavior, doesn't error).

**How to apply:** When a plpgsql function in `R__elsci_procedures.sql` (or any migration) declares
local variables in its `declare` block, grep for each variable name within the function and confirm at
least one `select ... into <var>` (or a direct `:=`/`=` assignment from a real expression) precedes
every place it's read. Don't rely on "the shape of the logic looks right" — trace the data flow for
each declared variable explicitly. This complements [[prefer-existing-patterns-over-new-abstractions]]
type checks; it's a correctness check specific to SQL/plpgsql review, worth doing even though this
agent's primary job is complexity/simplicity review, because the user's task prompt explicitly asked
"could the SQL changes weaken existing access checks."
