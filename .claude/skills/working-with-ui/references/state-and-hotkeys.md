# Client state, fractional indexing & hotkeys

How the board page holds state, reorders cards without re-indexing, and handles keyboard input.

## BoardModel — the board's client state (`BoardModel.js`)

Default export. `BoardPage` constructs one as `new BoardModel(this, new BoardRepository(), new PermissionRepository())` and exposes it as `window.model`. It is the single source of truth for the open board.

State fields:
- `board` (`Board2`), `cols` (`Col2[]`), `cards` (`Card2[]`, sorted by `seq`), `users` (`User2[]`).
- `colCards: number[][]` — for each column, the indexes (into `cards`) of its cards, in display order.
- Lookup maps for O(1) access: `colPk: Map<colId, colIdx>`, `cardPk: Map<cardId, cardIdx>`, `userPk: Map<userId, userIdx>`.
- `currentlyOpenCard` / `currentlyOpenCardIdx`, `me`, `boardUserCnt` (board members come first in `users`; commenters who left the board are appended lazily when a card opens), `snackBar`.

`init(boardId)` loads the board (via `#loadBoardJson` → `handlePageError` on failure), sorts cards/cols by `seq`, and builds `colCards` + all three maps.

### The state-mutation idiom: sync model update + fire-and-forget persist

Mutating methods update the in-memory arrays/maps **synchronously**, tell `this.page` to update the DOM, and call the repository **without awaiting** (the UI already reflects the change). Example (`moveCard`):

```javascript
moveCard(moveeIdx, insertBeforeIdx) {
    // ...splice colCards, recompute seqs via insertToOrdered...
    for (let i = updatedSeqStartIdx; i <= updatedSeqEndIdx; i++) {
        const card = this.cards[newColCards[i]];
        card.seq = seqs[i];
        this.boardRepo.patchCard(card.id, { col: card.col, seq: card.seq }); // not awaited
    }
}
```

Other notable methods: `createNewCards(colIdx, text)`, `moveCardToColEnd(cardIdx, toColIdx)`, `deleteColumn(idx, moveTo)` (+ `deleteColumnAndMoveCards`), `openCard(idx)`, `addComment`/`updateCommentBody`, `updateCurrentCardBody/Name`, `addCol(name)`, `cloneCard`, `deleteCurrentCard` (shows an Undo `SnackBar` that re-`patchCard({deleted: null})` and restores the node).

**Deletion keeps indexes stable:** deleted cards/cols are set to `null` in `cards`/`cols` (not spliced out) so existing indexes — and the `data-idx` attributes in the DOM — stay valid until a refresh. They're removed from `colCards` though, since that drives seq math. When the backend's returned card set disagrees with local state (a concurrent edit), the model calls `location.reload()`.

## Fractional indexing (`ArrayUtil.js`)

`seq` values let users reorder cards/columns without renumbering everything. `insertToOrdered(seq, idx)` inserts at a position, creating a "hole" between neighbors; `STEP = 1 << 14` (16384) is the default gap. When a region is too tight to fit a hole it spaces neighbors out, and if a tight run reaches the array end it renumbers that run from a clean slate. Returns `[insertedIdx, updatedRegionEndIdx]` so callers know which seqs changed and must be persisted. This is the one module with thorough unit tests (`ArrayUtilTest.js`) — keep them green if you touch it.

## Hotkeys (`HotkeyManager.js`)

`new HotkeyManager()` then `.init()` (done once per page) installs capturing `keydown`/`keyup` listeners and an F1 → help-popup binding.

- **Global shortcuts:** `register(shortcut, callback, description)`. The `shortcut` string is built from the event's `code` with `ctrl`/`alt`/`shift` prefixes (e.g. `'KeyB'`, `'ctrl+Enter'`, `'1'`). `Digit`/`Numpad` prefixes are stripped, so register digit keys as `'1'`..`'9'`. `ctrl` covers both Ctrl and Meta (⌘).
- **Scopes (for dialogs/popups):** `pushScope(name, {preventDefault})` returns a `HotkeyScope`; `popScope(name?)` removes it. Only the **top-most** scope is consulted first; if it has no match (and no `fallback` that returns non-undefined), handling falls through to the global scope. `HotkeyScope` has chainable `register(shortcut, cb, description)`, `fallback(cb)` (called when nothing matches — return `true` to `preventDefault`, any non-undefined to stop fall-through), and `onModifier(modifier, cb)` (fires on modifier key down/up with `{keyPressed}`).
- **Callbacks** receive `(e, options)` where `options` includes `isTyping` (focus is in an input/textarea/contenteditable — guard shortcuts with this), `isArrowKey`, `repeat`, `keyPressed`. Returning `false` from a shortcut suppresses the automatic `preventDefault`.
- **Descriptions** (`{hotKeyText, descriptionText}`) feed `HotkeyHelpPopup`. `BoardListPopup` is a full worked example of scoped hotkeys.

## Drag-scroll (`ProgrammaticHorizontalElementScroller.js`)

Auto-scrolls the board horizontally while a card is dragged near an edge (uses `requestAnimationFrame`). `BoardPage` wires it up; reuse it rather than re-implementing edge-scroll.
