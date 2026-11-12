# Components & DOM helpers

Reusable UI building blocks. All are plain classes — instantiate, then call a method. They render with template strings and escape via `safeText`. Reuse these before hand-rolling DOM.

## Modal convention: always a `<dialog>` or a popover

**Every modal window must be either a native `<dialog>` element or a Popover-API element (`popover` attribute).** No plain `<div>` overlays, no manual `position: fixed` + click-outside handling.

This isn't just style — `BoardListPopup#isBlocked()` detects "is something modal currently open" by querying `document.querySelectorAll('dialog[open]')` and `document.querySelector(':popover-open')`, and blocks global hotkeys (`B`, `0`-`9`) while any of those are open. A modal built as a plain `<div>` is invisible to that check and won't block hotkeys — this is exactly the bug `SnackBar` had (it rendered a `<div class="snackbar-container">`) until it was converted to a `<dialog>` with `showModal()`. `#isBlocked()` also checks `document.fullscreenElement`, so a card's `<video>` played in native fullscreen (e.g. `BoardPage.js`'s attachment-preview `<video controls>`) blocks these hotkeys too — otherwise pressing space to pause/resume the video and then `B`/digits leaks through to the page.

- Use `BaseDialog` for confirmation/alert/form dialogs.
- Use the Popover-API pattern (`BoardListPopup`, `HotkeyHelpPopup`) for popups/menus.
- If you build a new modal-ish component instead of reusing `BaseDialog`, it still must be a `<dialog>` (opened via `showModal()`) or carry `popover`, so it participates in hotkey blocking automatically.

## BaseDialog — the canonical modal (`BaseDialog.js`)

Default export. Wraps a native `<dialog>`, created and appended to `<body>` on first show, removed on close. This is how every confirmation/alert/form modal should be built.

```javascript
import BaseDialog from './BaseDialog.js';

const dialog = new BaseDialog({
    title: 'Delete card?',
    btnOk: 'Delete',        // omit / pass null to drop the primary button
    btnCancel: 'Cancel',    // omit / pass null to drop the cancel button
    btnSecondary: null,     // optional third action (e.g. "Submit")
    autofocusEl: '.btn-ok', // CSS selector inside the dialog to focus on open
    cssClass: 'base-dialog' // extra class added to the <dialog>
});
dialog.onOk(async () => { /* awaited; dialog closes after it resolves */ });
dialog.onCancel(() => { /* runs then closes */ });
dialog.onSecondary(() => { /* runs, does NOT auto-close */ });
dialog.showText('This cannot be undone.');   // escaped plain text
// or: dialog.showHtml(`<main class="dialog__main-content">...already-safe HTML...</main>`)
// or: dialog.showWithStackTrace(message, error.stack)
```

Key behaviours:
- `show({title, bodyHtml, ...})` is the low-level entry; `showText` / `showHtml` / `showWithStackTrace` are conveniences that reuse the constructor properties.
- Buttons: `.btn-ok` (primary), `.btn-secondary` (bordered), `.btn-cancel` (flat). Passing `null`/falsy for a button label omits it.
- Closes on the cancel button, the native `cancel` event (Esc), and backdrop `mousedown`.
- `onOk` is awaited before `close()` — return a promise to keep the dialog open during async work.
- `isAlreadyShown()` returns whether a dialog element currently exists (guard against double-show).
- Body text is escaped by `showText`; if you build HTML yourself with `showHtml`, you own the escaping (use `safeText`/`safeMarkdown`).

## List — keyed DOM-diffing renderer (`List.js`)

Named export `List`. Renders an array into a container, reusing existing DOM nodes when an item's rendered HTML is unchanged (diffed by key). Use for any re-rendered collection.

```javascript
import { List } from './List.js';

const list = new List(containerEl, {
    keyFunction: board => board.id,                              // stable unique key
    template: (board, idx) => `<li class="board-item" data-id="${safeText(board.id)}">${safeText(board.name)}</li>`,
});
list.setData(boards);        // sets dataset + render()
list.getNode(boardId);       // -> the rendered element for a key (or undefined)
```

- `template` must return a single root element's HTML (the renderer takes `template.content.firstElementChild`).
- Duplicate keys throw. An empty dataset renders `<li class="empty">No items</li>`.
- `setData()` calls `render()`; call `render()` directly only when you mutated `dataset` in place.

## SnackBar — bottom toast (`SnackBar.js`)

Default export. One snackbar on screen at a time (a new `show` removes the old). Auto-dismisses after `ttl` (default 10s). Used for undoable actions.

```javascript
import SnackBar from './SnackBar.js';
const snackBar = new SnackBar();              // BoardModel already owns one as this.snackBar
snackBar.show({
    msgText: 'Card deleted',
    btnText: 'Undo',                          // optional; if set, btnCb is required
    btnCb: () => restore(),                   // runs on button click, then removes
    ttl: 10000                                // optional ms
});
snackBar.remove();                            // dismiss programmatically
```

`show` validates its props (throws on missing `msgText`, mismatched `btnText`/`btnCb`, non-function `btnCb`, non-number `ttl`). It mounts into a `.snackbar-container` it creates on `<body>`.

## Popups built on List + hotkeys

- **`BoardListPopup.js`** — favorites/recent board switcher. Built on `List`, lazy-loads board data on first `show()`, has a live filter input, drag-to-reorder favorites (pointer events), and registers a hotkey scope (`static HOTKEY_SCOPE_NAME`, `KeyB` to open, `0` → all boards, `1-9` → favorites). Good reference for combining `List`, hotkey scopes, and popover anchoring.
- **`HotkeyHelpPopup.js`** — F1 help modal listing registered shortcuts + markdown syntax, with a live filter. Reads the registry from `HotkeyManager`.

## HtmlChunks — shared snippets (`HtmlChunks.js`)

- **`htmlUserPic(user)`** — returns avatar HTML: an `<img>` if `user.picurl`, otherwise a colored `.userpic--faceless` div with initials (`StringToColorConverter.convert(user.id)` for the background, `User2.getInitials(user)` for the text). Falls back to a hidden div if `user` is null.

## HtmlUtil — escaping, key/URL helpers, browser shims (`HtmlUtil.js`)

- **Escaping:** `safeText(unsafe)` — the XSS-safe escaper for text/attributes.
- **Key events:** `isCtrlEnter(e)`, `isSpace(e)`, `isEsc(e)`.
- **URL state (the "router"):** `getSearchParam(key)`, `setSearchParam(key, val)`, `deleteSearchParam(key)` — all use `history.replaceState`.
- **Browser polyfills (call at page module load):**
  - `addSupportForCommandForAttribute()` — shims `<button command="close|show-modal" commandfor="dialogId">` on browsers lacking native `commandForElement` (incl. a `MutationObserver` for dynamically added buttons). `commandForShim(el)` is the per-element helper.
  - `addSupportForPopoverElementsInOldIosVersions()` — light-dismiss popovers on old iOS.
  - `addSupportForPopoverPositioning()` — CSS-anchor-positioning fallback for popovers (no-op when `CSS.supports('anchor-name')`).
- **Menus / chrome:** `initMeMenu(user)` — fills `.me` with `htmlUserPic`, strips the billing item when `!user.billing`, wires the shortcuts item to dispatch F1.
- **Page-level errors:** `handlePageError(error)` — renders the access-denied (403) / not-found (404) full-page template into `[data-id="main-container"]`; rethrows other statuses. `BoardModel.init` calls this when the initial board load fails.
- **Misc:** `formatMBytes(mb)`, `formatMoney(amount, currency)`, `SVG_ELEMENT.DROPDOWN_ARROW`.
