---
name: nested-record-precedent
description: TrelloImportPlan.java is the codebase's precedent for a record nesting sub-records (e.g. ColRow, CardRow) — cite it when judging whether a new nested record DTO fits repo idioms.
metadata:
  type: reference
---

`src/main/java/io/elsci/boardeaux/external/trello/TrelloImportPlan.java` declares an outer record
with several `record XyzRow(...)` types nested inside it (`ColRow`, `CardRow`, `LabelRow`,
`CommentRow`, `AttachmentRow`). Callers in `TrelloBoardBuilder.java` construct these with the
qualified `TrelloImportPlan.ColRow(...)` form.

**How to apply:** when reviewing a new "outer record contains a small nested record for one field"
shape (e.g. `CardBodyHistoryEntry` nesting `HistoryAuthor`), this is an established idiom in this
repo, not an invented one — don't flag the nesting itself as overcomplicated. Do still check that the
nested record is actually only used in the outer record's context (i.e. it isn't a shape that several
unrelated records would want, which would argue for pulling it to top-level instead).
