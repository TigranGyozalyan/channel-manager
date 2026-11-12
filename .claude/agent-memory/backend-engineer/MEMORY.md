# backend-engineer memory

## Prefer existing patterns over new abstractions

Review feedback on the board-props/card-numbering change (July 2026): don't design a new backend surface
from scratch — find the closest existing analog (card insert/patch, user prefs, labels) and copy its
shape. The specific rules this surfaced (one patch path per resource, inline controller validation,
stored-function complexity criteria, SQL binding style) are now documented in `writing-backend-code` and
`writing-sql` — load those skills rather than relying on this note.

**Why:** the repo values uniformity with established idioms over new abstractions.

**How to apply:** before designing a new backend surface, check the skills first; only fall back to
inventing a new shape when no existing analog fits.
