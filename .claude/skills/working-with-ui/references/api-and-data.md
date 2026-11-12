# API layer, error flow & data shapes

All network access lives in `repositories.js`. Pages use repositories; repositories use `http()`. Data classes live in `entities.js`.

## The `http()` wrapper (`repositories.js`)

A thin `fetch` wrapper that returns the `Response` on success and **throws a typed error otherwise**. It never redirects and never renders UI.

```javascript
async function http(url, options) {
    const response = await fetch(url, options);
    if (response.ok) return response;
    if (response.status === 401)
        throw new HttpError(401, { errorMessage: "Seems you were logged out. Refresh the page." });
    if (response.status === 400) {
        const body = await response.json();
        if (body.errorCode === 'validation_error')        throw new ValidationError(400, body);
        if (body.errorCode === 'billing_generic_error')    throw new BillingError(400, body);
        throw new HttpError(400, body);
    }
    // other statuses: parse errorMessage (fall back to text), then:
    throw new HttpError(response.status, { errorMessage });
}
```

Error classes (all exported): `HttpError extends Error` with `{ status, body }`; `ValidationError extends HttpError`; `BillingError extends HttpError`. So `instanceof HttpError` matches all three.

### Where thrown errors go: `GlobalErrorHandler` (`GlobalErrorHandler.js`)

Call `GlobalErrorHandler.init()` once per page (early in `init()`). It installs `window.onerror` and `window.onunhandledrejection`, routing to `handleError(error)`, which shows a `BaseDialog`:

- `ValidationError` → `"Invalid data provided: <body.errors[0].message>"`.
- `HttpError` with `status === 403` → dialog with a **Refresh** button (`location.reload()`); otherwise a plain dialog with `body.errorMessage`.
- `DOMException` (e.g. `AbortError` from a cancelled upload) → ignored.
- Anything else with a `.name` → generic "Something went wrong" dialog with the stack trace.

**Handling an error inline** (instead of the global dialog): `await` the repository call in a `try/catch` and read `error.status` / `error.body.errorMessage`. `BoardModel.init` does this via `handlePageError` to render the 403/404 full-page template.

## Repository catalog (`repositories.js`)

Convention: JSDoc-typed `async` methods that call `http()`, then `.json()` (or read a header). Some normalize dates before returning (see below).

- **`AppRepository`** — `getMe()` → `User2`; `putPrefs(prefs)` (e.g. favorite boards).
- **`BoardRepository`** — `get(boardId)`, `list()`, `postBoard(name)` (returns `Location` header), `patchBoard(boardId, name)`, `postCol/patchCol/deleteCol` (delete returns moved-card seqs, or `null` on 204 when cards are deleted too), `postCard/patchCard/deleteCard/getCard/cloneCard`, `createInvite(boardId, seats, access)`, `getUsersWithAccess(boardId)`.
- **`PermissionRepository`** — `updateUserAccess(boardId, userId, access)`.
- **`BillingRepository`** — `getBillingData(forceRefresh)` → `BillingData.fromJson`, `createOrUpdateCustomer`, `cancel/resumeSubscription`, `getSubscriptionManagementUrl`, `switchPlan/previewSwitch(priceId)`, `createDraftTransaction`, `getPaddleCheckoutConfig(txId)`.
- **`CommentRepository`** — `postComment(cardId, body)` (text/plain body), `patchComment(commentId, body)`.
- **`AttachmentRepository`** — `post(cardId, formData, signal)`; takes an `AbortSignal` (cancellation surfaces as a `DOMException` `AbortError`, which `GlobalErrorHandler` ignores). Sets `json.url = "/api/attachment/" + json.id`.

### Date normalization quirk

Helpers `noramlizeCards` / `noramlizeComments` (note the misspelling in source) convert `inserted`/`updated` strings to `Date` objects after fetch; comments are sorted newest-first. So `card.inserted`, `comment.inserted/updated` are already `Date`s — don't re-parse. `BoardModel.cloneCard` does its own `new Date(card.inserted)` for the same reason.

## Entities (`entities.js`)

Data classes (mostly JSDoc-typed field bags). **Field names matter** — these are the real ones:

- **`Board2`** — `id`, `name`, `creator`.
- **`Col2`** — `id`, `name`, `seq`.
- **`Card2`** — `id`, `name`, `body`, `seq` (private `#seq` with `assertNumber` guard via getter/setter), `assignee` (nullable), `col`.
- **`CardFull`** — full card from `getCard`: `id`, `name`, `body`, `assignee`, `creator`, `inserted` (`Date`), `comments: Comment[]`, `users: User2[]`.
- **`User2`** — `id`, `name`, `firstname`, `lastname`, `picurl` (nullable), `billing` (bool), `prefs` (`{ favboards: string[] }`). Static helpers: `getFullName(u)`, `getInitials(u)`, `setAccessLevel(json)` (adds `json.accessLevel = new AccessLevel(json.access)`), `setAccessLevelToMany(jsonArray)`. **Note the field names are `firstname`/`lastname`/`picurl`/`name`** (not camelCase, no `email`).
- **`Comment`** — `id`, `card`, `inserted` (`Date`), `updated` (`Date`), `author`, `body`.
Billing entities (`BillingData`, `Quota`, `PriceDetails`, `Subscription`, `Plan`, `CustomerDetails`; most with a static `fromJson`) are **defined in `repositories.js`**, not `entities.js`, and are returned by `BillingRepository`.

## Backend contract

The `GET /api/board/{id}` response is `{ board, cols, cards, users }`. Error responses (`ErrorResponse`):

- 400 → `errorMessage`, optional `errors[]` for field validation, `errorCode` discriminator (`validation_error`, `billing_generic_error`).
- 403 → `errorMessage` (often "Oops. You don't have permissions to see this...").
- 404 → `errorMessage` (often "Couldn't find Xyz#id").
- 413 → file-size-exceeded message.

If you need a new field, ask the backend dev to extend the existing response rather than adding an endpoint.
