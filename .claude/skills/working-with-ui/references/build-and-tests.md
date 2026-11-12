# Build pipeline & JS tests

## npm scripts (`package.json`)

```bash
npm run build-js     # esbuild: src/main/ui/*Page.js → target/classes/ui/  (--bundle --target=esnext, no transpile/minify)
npm run build-dev    # build-js + bundle-dev.js  (injects CSS into HTML, leaves <script src> tags)
npm run build        # build-js + bundle-prod.js (inlines CSS + JS, adds CSP <meta> with sha256 hashes)
npm run watch-js     # build-js --watch
npm run watch-dev    # re-runs bundle-dev.js on src/main/ui changes
npm test             # node --test over src/test/js/**/*Test.js → spec to stdout + JUnit XML
```

Dependencies: `esbuild` and `zora` (dev), `dompurify` (bundled at runtime). `marked` is **not** bundled — it's loaded as a CDN global in the HTML (`cdn.jsdelivr.net/npm/marked@18.0.3`); the `marked` devDependency exists so tests use the matching version. If you bump marked, update both the HTML `<script>` and `package.json`.

## What the bundlers do

**esbuild (`build-js`)** bundles each `*Page.js` entry (plus its imports) into a single `target/classes/ui/XyzPage.js`. `target/` is gitignored — re-run after any UI change before testing the backend (backend integration tests serve from `target/classes/ui/`).

**`bundle-dev.js`** copies HTML/CSS/img to `target/classes/ui/` and inlines the page's CSS into a `<style>` in `<head>`. It leaves `<script src="/ui/XyzPage.js">` tags untouched (the browser loads the esbuild output directly). No CSP. Good for local iteration.

**`bundle-prod.js`** does the same CSS injection, then additionally:
- Replaces each `<script src="/ui/Xyz.js">` with an **inline** `<script>` wrapping the bundle in `window.addEventListener("DOMContentLoaded", () => { ... })`.
- Computes a **sha256** hash of each inline script and adds it to a `Content-Security-Policy` `<meta http-equiv>` injected after `<head>` — `script-src 'self' 'sha256-…' https://cdn.jsdelivr.net/npm/marked@18.0.3/… https://cdn.paddle.com`. (This is CSP hashing, **not** SRI `integrity` attributes.)
- The CSP also sets `default-src 'none'`, `style-src 'self' 'unsafe-inline' …paddle`, `img-src 'self' https: data: blob:`, `connect-src`/`frame-src` for Paddle + marked sourcemap, `media-src *`.

Consequence: any inline script/style added **outside** the build pipeline is blocked in production. Bundle JS through esbuild; styles are allowed via `'unsafe-inline'` but should still go through `pageStyles`. Never hand-edit generated files in `target/`.

### Per-page CSS: the `pageStyles` map

Both bundlers carry an identical `pageStyles` map deciding which stylesheets each HTML gets, e.g.:

```javascript
const pageStyles = {
    "board.html":    ["common.css", "board.css", "boardListPopup.css", "SnackBar.css"],
    "boards.html":   ["common.css", "boards.css", "boardListPopup.css"],
    "billing.html":  ["common.css", "billing.css"],
    "checkout.html": ["common.css", "billing.css", "checkout.css"],
};
```

Adding a page that needs CSS means adding an entry **in both `bundle-dev.js` and `bundle-prod.js`** (landing.html has no entry → no injected CSS). Design tokens (oklch palette, radius, shadow vars) live in `common.css`.

**Oddity:** `styles/AttachmentUtils.js` is a JS module (`MimeTypes`) that lives under `styles/`, imported as `./styles/AttachmentUtils.js`. The bundlers only copy `.css`/`img` from `styles/`, so this file is reached purely via the esbuild import graph, not copied.

## JS tests (`src/test/js/`)

Run with `node --test`, but tests **import `test` from `zora`** (not `node:test`). Zora supplies `t.equal()` / `t.deepEqual()` / `t.throws()`.

```javascript
import { test } from 'zora';
import { insertToOrdered, STEP } from '../../main/ui/ArrayUtil.js';

test("insertBefore: doesn't update sequence numbers when inserting at the start", (t) => {
    const seqs = [1, 10, 100];
    t.equal(seqs[insertToOrdered(seqs, 0)[0]], 1 - STEP);
    t.deepEqual(seqs, [1 - STEP, 1, 10, 100]);
});
```

- Existing suites: `ArrayUtilTest.js` (the fractional-indexing algorithm), `BoardModelTest.js` (card moves, column deletion — uses `MockRepos.js`), `MarkdownUtilsTest.js` (parsing + token offsets).
- Mocks live in `MockRepos.js` (e.g. `MockBoardRepo` with no-op `patchCard`, a `deleteCol` stub). Extend it when a new test needs to swap a repo.
- Tests import production ESM directly — no build step.
- **Scope:** tests target pure-JS modules. There is **no jsdom / DOM testing** set up. Components that touch `document` aren't unit-tested here; if you need DOM behavior coverage, discuss introducing jsdom before adding the dependency.
