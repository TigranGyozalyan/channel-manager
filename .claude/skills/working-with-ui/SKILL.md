---
name: working-with-ui
description: >
  INVOKE THIS SKILL FIRST before writing, modifying, building, or testing any frontend code — this is a blocking requirement.
  TRIGGER when: the task touches any file under `src/main/ui/` or `src/test/js/`; the user asks to add or change UI behaviour, a page, a component (dialog/popup/list/snackbar), a hotkey, client-side board state, or a JS test; or the task involves running the frontend build.
  Covers the vanilla-JS architecture, the `http()`/repository layer and its error flow, DOM safety (`safeText`/`safeMarkdown`), the `*Page.js` bootstrap convention, the component catalog, the `BoardModel` state model, the hotkey system, the esbuild + CSP build pipeline, and JS test patterns.
---

# Working with the UI

The frontend is intentionally framework-free: ES modules, DOM APIs, template strings. esbuild bundles per-page entry files. There is no router — each HTML page loads its own bundle and self-instantiates.

This file is the orientation layer and the must-know rules. Detailed catalogs live in `references/` — read the one that matches your task **before** writing code:

| When you are touching… | Read |
|---|---|
| dialogs, popups, snackbars, lists, the `<dialog>` pattern, `HtmlUtil` shims | `references/components.md` |
| `repositories.js`, the `http()` error flow, `GlobalErrorHandler`, entities/DTOs, the backend contract | `references/api-and-data.md` |
| `BoardModel` state, card/column moves, fractional indexing, hotkeys, drag-and-drop | `references/state-and-hotkeys.md` |
| the esbuild build, CSP/hashing, CSS injection, running JS tests | `references/build-and-tests.md` |

## File layout

```
src/main/ui/
  *.html                  Page templates (board, boards, billing, checkout, landing)
  *Page.js                Page entry points — one per HTML page, bundled by esbuild
  repositories.js         API client (BoardRepository, AppRepository, BillingRepository, ...) + the http() wrapper + error classes
  entities.js             Data classes shared by repositories (Board2, Col2, Card2, CardFull, User2, Comment + billing entities)
  HtmlUtil.js             safeText, key-event helpers, URL search-param helpers, browser polyfills, page-error rendering
  HtmlChunks.js           Reusable inline HTML snippets (htmlUserPic)
  MarkdownUtils.js        safeMarkdown (marked + DOMPurify)
  AccessLevel.js          UI mirror of the backend AccessLevel enum (class + AccessLevels map)
  BoardModel.js           Client-side board state model (cards, columns, moves, indexes)
  BaseDialog.js           Reusable <dialog> wrapper (the canonical modal pattern)
  List.js                 Keyed, DOM-diffing list renderer
  SnackBar.js             Bottom-of-screen toast (one at a time)
  BoardListPopup.js, HotkeyHelpPopup.js   Popups built on List + hotkey scopes
  HotkeyManager.js        Keyboard-shortcut system with nested scopes
  GlobalErrorHandler.js   Catches unhandled errors/rejections → BaseDialog
  ArrayUtil.js            Fractional-indexing insertion (insertToOrdered, STEP)
  CommonUtil.js           formatDate, assertString/Number, capitalize
  StringToColorConverter.js, ProgrammaticHorizontalElementScroller.js
  styles/                 CSS (common.css + per-page) — and note AttachmentUtils.js (a JS file) lives here too
  img/                    Static assets

src/test/js/
  *Test.js                Unit tests (zora)
  MockRepos.js            Mock repository stubs

build-scripts/
  bundle-prod.js          Production: inlines CSS + JS, adds a CSP <meta> with sha256 script hashes
  bundle-dev.js           Dev: injects CSS only, leaves <script src> tags as-is
```

The backend serves the built bundles from `/ui/*Page.js` (output of `target/classes/ui/`).

## Page entry-point pattern

Each page is a class with an `async init()`, paired with a same-named HTML file. The HTML loads `marked` from a CDN first (it's a global the markdown renderer relies on), then defer-loads the page bundle:

```html
<!-- board.html <head> -->
<script type="application/javascript" src="https://cdn.jsdelivr.net/npm/marked@18.0.3/lib/marked.umd.js"></script>
<script defer type="application/javascript" src="/ui/BoardPage.js"></script>
```

The bundle runs browser-polyfill setup at module load (before the class), then self-instantiates at the bottom:

```javascript
// BoardsPage.js
import { AppRepository, BoardRepository } from './repositories.js';
import { addSupportForCommandForAttribute, addSupportForPopoverElementsInOldIosVersions,
         addSupportForPopoverPositioning, initMeMenu, safeText } from './HtmlUtil.js';
import GlobalErrorHandler from './GlobalErrorHandler.js';

addSupportForCommandForAttribute();              // <-- module-level polyfill setup
addSupportForPopoverElementsInOldIosVersions();
addSupportForPopoverPositioning();

class BoardsPage {
    async init() {
        GlobalErrorHandler.init();               // wire global error → dialog handling
        const me = await new AppRepository().getMe();
        initMeMenu(me);
        // fetch data, render, wire events
    }
}

new BoardsPage().init();                         // <-- ALWAYS self-instantiate at the bottom
```

`BoardPage` additionally exposes `window.page = this; window.model = this.model;` for DevTools debugging — keep that idiom when adding a stateful page.

To add a new page:
1. Create `XyzPage.html` and `XyzPage.js` in `src/main/ui/`.
2. Put the polyfill setup calls at module top, end the JS file with `new XyzPage().init();`.
3. Call `GlobalErrorHandler.init()` early in `init()` so uncaught errors surface as dialogs.
4. Add the HTML to the backend page routing (controller returning the view name) and, if it needs CSS, add it to the `pageStyles` map in **both** `bundle-dev.js` and `bundle-prod.js`.
5. The esbuild glob `src/main/ui/*Page.js` picks the new bundle up automatically.

## API calls — always through a repository

Never call `fetch()` from page code. Define a method on the matching `XxxRepository` in `repositories.js`, which routes through the `http()` wrapper. `http()` **throws typed errors** (`HttpError` / `ValidationError` / `BillingError`) — it does **not** redirect or render anything itself; `GlobalErrorHandler` turns thrown errors into dialogs. Full error contract and the repository catalog are in `references/api-and-data.md`.

```javascript
async get(boardId) {
    return (await http(`/api/board/${boardId}`)).json();
}
async patchBoard(boardId, name) {
    await http(`/api/board/${boardId}`, {
        method: 'PATCH', body: JSON.stringify({ name }),
        headers: { 'Content-Type': 'application/json' }
    });
}
```

## DOM rendering and XSS safety

HTML is built with template strings. **All** user-controlled content must go through one of:

- **`safeText(s)`** (`HtmlUtil.js`) — HTML-escapes `& < > " '`. Use for attributes, plain text, anything not meant to be markup.
- **`safeMarkdown(s)`** (`MarkdownUtils.js`) — `marked.lexer/parser` then `DOMPurify.sanitize` with a tag allowlist (and an `afterSanitizeAttributes` hook that forces `target="_blank" rel="noopener noreferrer"` on links). Use for card bodies, comments, descriptions.

```javascript
element.innerHTML = `
    <div class="card" data-id="${safeText(card.id)}">
        <span class="name">${safeText(card.name)}</span>
        <div class="body">${safeMarkdown(card.body)}</div>
    </div>`;
```

**Never** `innerHTML = userInput`. **Never** interpolate user input without escaping. **`safeMarkdown` ≠ `safeText` + `marked`** — don't pre-escape before the markdown renderer, you'll double-escape inside `<code>` blocks (see the comment at the top of `MarkdownUtils.js`).

## Gating UI by access level

`AccessLevel.js` exports a default `AccessLevel` class (static instances `AccessLevel.COMMENTER`, etc.; methods `meetsOrExceeds(other)` / `lessThan(other)` / `intValue()`) and a named `AccessLevels` map of raw numbers (`NONE 0`, `READER 2`, `COMMENTER 32`, `CREATOR 64`, `REGULAR 96`, `MANAGER 128`, `ADMIN 4096`, `OWNER 8192`). The board response includes `meaccess` (a raw number that `BoardModel` wraps as `new AccessLevel(meaccess)`). Hide affordances rather than letting users click into a 403:

```javascript
if (this.model.meaccess.lessThan(AccessLevel.COMMENTER))
    this.container.querySelector('#add-comment-form').remove();
if (this.model.meaccess.lessThan(AccessLevel.MANAGER))
    this.newColBtn.setAttribute('hidden', 'true');
```

The backend still enforces every access check — UI gating is purely UX, not a security boundary.

## Gotchas

- **Every modal must be a `<dialog>` or a popover — no plain `<div>` overlays.** `BoardListPopup#isBlocked()` finds open modals via `dialog[open]` / `:popover-open` to block global hotkeys while one is open; anything else is invisible to that check. See `references/components.md`.
- **`safeMarkdown` ≠ `safeText` + `marked`.** Double-escapes code blocks. See `MarkdownUtils.js`.
- **`http()` throws, doesn't redirect.** Errors propagate as `HttpError`/`ValidationError`/`BillingError` and are rendered by `GlobalErrorHandler`. To handle one inline, catch the rejection and read `error.status` / `error.body.errorMessage`.
- **Self-instantiation + polyfill setup.** A page that doesn't run the module-level polyfill calls and `new XyzPage().init()` at the bottom will load but do nothing (or break popovers/dialogs on mobile).
- **No router.** Navigation is full HTML loads. URL state lives in query params (`getSearchParam`/`setSearchParam`/`deleteSearchParam` in `HtmlUtil.js`).
- **Bundle freshness.** Backend integration tests serve from `target/classes/ui/`. If UI changes aren't visible, you skipped `npm run build-js` (or `npm run watch-dev`).
- **CSP, not SRI.** The prod build inlines each bundle inside `DOMContentLoaded` and adds its **sha256** hash to a `script-src` CSP `<meta>`. A new inline script/style added outside the build pipeline is blocked in production. Don't hand-edit generated HTML in `target/` — re-run the build.
- **Never write a `/pattern/` regex literal inside a function body.** It gets recompiled on every call. Always declare it once as a module-level `const` (see `TASK_CHECKED_REGEX` and friends in `MarkdownUtils.js`) and reference that from the function — regardless of which file it's in or how often the function is expected to run.

## When you need backend changes

The backend is owned by separate folks. When you need a new endpoint, a new field on a response, or a validation tweak, leave a clearly scoped request rather than editing Java yourself. If you need a new field, prefer extending an existing response over adding an endpoint. Backend conventions live in `.claude/CLAUDE.md` and the backend skills.
