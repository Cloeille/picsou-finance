# Feature: Frontend Error Display (`formatApiError` / `safeBackendMessage`)

> Last updated: 2026-05-31

## Context

Sync flows used to surface raw payloads to the user — e.g. *"Enable Banking auth
failed: {"code":400,"message":"Redirect URI not allowed",...}"* — because every
caller did its own ad-hoc message extraction (`err.message`, `err.response.data`,
or a hand-written regex). Helpers in `frontend/src/lib/errors.ts` now normalise Axios errors
into a friendly, **leak-free, translated** string.

Two later additions hardened this:

- **Leak guard** (`safeBackendMessage`): some backend strings carry internals
  (a `TransientObjectException`, a `.java:NN` frame, the axios
  `"Request failed with status code N"` boilerplate). Those must never reach the
  user — they're filtered and replaced by a friendly fallback.
- **Status-aware translation** (`formatApiError`): the default for component call
  sites. Maps the HTTP status to an i18n key, falling back to a backend message
  only when it's user-safe.

## How it works

### `safeBackendMessage(err): string | null`

Walks the Axios error in priority order and returns the first **user-safe** string,
or `null`:

1. `err.response.data.detail` — Spring's RFC 7807 `ProblemDetail` field.
   - If the string contains `{`, slice from the first brace and try `JSON.parse`;
     on success use the embedded `message`. This handles adapter strings of the
     form `"Enable Banking auth failed: {...}"` where the upstream JSON body has
     been concatenated into the human message.
   - Otherwise consider `detail` verbatim.
2. `err.response.data.message` — for endpoints that bypass `ProblemDetail`.
3. `err.message` — last resort; if it starts with `{` try the same JSON parse.

Every candidate must pass `isSafeMessage` — non-empty **and** not matching
`LEAK_PATTERN` (`Exception`, `.java`, `java.`/`org.`/`com.picsou`, the axios
`"Request failed with status code N"` boilerplate, "stack trace"). Anything that
fails is skipped; if nothing safe remains the function returns `null`.

### `extractErrorMessage(err, fallback)`

Now a thin wrapper: `safeBackendMessage(err) ?? fallback` (fallback defaults to the
French *"Une erreur est survenue"*). Use it only outside React, where no translator
is in scope.

### `formatApiError(err, t, fallbackKey = 'common.error')`

The preferred helper for components. Status-first, then safe-message, then key:

1. **401 → `common.errors.unauthorized`, 429 → `common.errors.tooManyRequests`,
   ≥500 → `common.errors.serverError`** — always translated (the backend body here
   is absent or the vague "An unexpected error occurred").
2. Otherwise `safeBackendMessage(err)` — a user-safe backend reason passes through
   verbatim (this is why 4xx guard messages like *"Cannot delete the last
   administrator"* survive and aren't flattened to a generic string).
3. Else `403 → common.errors.forbidden`, anything else → `t(fallbackKey)`.

### `skipGlobalErrorRedirect` on Axios requests

The shared Axios client still redirects ordinary GET 5xx failures to the global
`/error/500` page. A query may opt out with `skipGlobalErrorRedirect: true` only
when it is scoped to a self-contained UI surface that can display the error
inline. The first use is bank institution search inside `AddAccountModal`: an
Enable Banking connector failure is actionable in the modal and should not tear
down the Accounts page.

### Key files

- `frontend/src/lib/errors.ts` — `safeBackendMessage`, `extractErrorMessage`,
  `formatApiError`, plus private `tryParseJson` / `isSafeMessage` / `LEAK_PATTERN`.
  No external deps.
- `frontend/src/lib/api-client.ts` — global 403 / setup-required / 5xx / 401
  interceptors, plus `skipGlobalErrorRedirect` for inline-handled GET failures.
- `frontend/src/lib/errors.test.ts` — 18 Vitest cases: the 8 original
  `extractErrorMessage` branches, 5 leak-guard cases on `safeBackendMessage`
  (rejects exception classes, `.java`/package strings, axios boilerplate, stack
  traces; accepts a genuine message), and 5 `formatApiError` cases (status mapping,
  4xx safe-message passthrough, 403 fallback, fallback key, no-leak-on-400).

Used by:

- `frontend/src/pages/sync/BankSyncTab.tsx` — replaces hand-written extraction in
  `completeMutation.onError` and `initiateMutation.onError`.
- `frontend/src/pages/sync/TradeRepublicTab.tsx` — `formatAuthError` fallback.
- `frontend/src/pages/sync/BoursoTab.tsx` — `formatError` fallback.
- `frontend/src/pages/sync/FinaryTab.tsx` — replaces `err instanceof Error ? err.message : ...`.
- `frontend/src/pages/sync/CryptoExchangeTab.tsx`,
  `frontend/src/pages/sync/CryptoWalletTab.tsx` — error states show
  `extractErrorMessage(error)`.
- `frontend/src/pages/admin/sections/{Security,EnableBanking}Section.tsx` — TanStack
  Query mutation `error` rendered through the helper.
- `frontend/src/pages/admin/sections/MembersSection.tsx` and
  `frontend/src/pages/settings/FamilySettingsPage.tsx` — member-delete failure shown
  **inside** `ConfirmDialog` via `formatApiError(deleteMember.error, t)`.
- `frontend/src/pages/settings/security/{ExportDataDialog,RecoveryCodesDialog,MfaEnrollDialog}.tsx`
  — replaced raw `err.message` / `` `${status} — …` `` displays with
  `formatApiError(err, t)` (keeping their existing 401/429-specific branches).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `detail.indexOf('{')` + `slice` + `JSON.parse` | Spring `ProblemDetail.detail` is a flat `String`; adapters concatenate upstream JSON bodies into it. Slicing from the first brace + parsing handles arbitrary nesting. | Regex (`/\{.*\}/`) — fragile on nested braces from nested upstream errors and on multi-line payloads. |
| Skip `"Request failed with status code N"` | Axios's default `err.message` for non-2xx — completely useless to a non-technical user. | Returning it as a fallback (regression on the very UX bug this fixes). |
| Caller-supplied `fallback` | Each page wants a domain-specific default (`sync.tr.errors.unknownError`, `sync.bourso.errors.serverError`, `common.retry`). | One global fallback string — pages would still wrap it in their own `||`. |
| Default fallback in French | The app's primary locale is FR; English users hit the explicit `t(...)` override path anyway. | English default — would surface to FR users on the rare path where the caller forgot to pass a fallback. |
| Pure function in `lib/`, no React import | Reusable from non-component code (e.g. mutation `onError` callbacks, test files) and trivially mockable. | A custom hook (`useErrorMessage`) — overkill for a string transform. |

## Gotchas / Pitfalls

- **The fallback is for "no message at all", not "message but ugly".** If
  `err.response.data.detail` is `"Server error"` the helper returns `"Server error"`
  even when you passed a nicer fallback. Pages that want to map status codes to
  friendly strings (TradeRepublicTab's `formatAuthError`) must do the mapping
  *before* calling `extractErrorMessage` — only the unmapped tail should fall
  through to the helper.
- **`err.message` vs `err.response.data.message`.** Axios sets both, with
  different semantics: `err.message` is *Axios's* description ("Request failed with
  status code 400"), `err.response.data.message` is the server's body field. The
  helper consults the body first to avoid showing the Axios string when a real
  message is one level deeper.
- **`detail.indexOf('{')` matches the first `{` anywhere.** A detail string like
  *"Operation failed for {customerId}"* will trigger a (failed) parse, which
  silently falls through to returning the raw `detail`. That's the correct
  behaviour but worth understanding before tweaking the regex/slice logic.
- **Type cast `err as { response?: ...; message?: ... }`.** The helper accepts
  `unknown` for safety but does no runtime guards beyond the `typeof string`
  checks. If a non-Axios shape (e.g. a thrown plain string) reaches it, none of
  the branches match and the fallback is returned — which is the intended
  behaviour.

## Tests

- `frontend/src/lib/errors.test.ts` — 18 cases, all green via `bunx vitest run`.
  - `extractErrorMessage` (8): Spring detail with embedded JSON; plain Spring
    detail; `data.message` when no detail; JSON inside `err.message`; plain
    `err.message`; the Axios `"Request failed with status code N"` skip; fallback
    when only the Axios boilerplate is present; fallback when nothing matches.
  - `safeBackendMessage` (5): rejects exception-class strings, `.java`/package
    references, axios boilerplate, and stack traces; accepts a real user-facing
    message; `null` when nothing safe is present.
  - `formatApiError` (5): 401/429/5xx → generic translated keys ignoring the body;
    user-safe 4xx reason passthrough; 403 → forbidden key when no safe message;
    provided `fallbackKey` honoured; no internals leaked even on a 400.
- No integration test wires this through a real Axios call — by design; the
  helpers are pure functions and the contract is asserted at the unit level.

## Links

- Backend error contract this consumes:
  [`docs/conventions/error-handling.md`](../conventions/error-handling.md) —
  `ProblemDetail` shape and the `detail` field semantics.
- First consumer outside `pages/sync/`:
  [`admin-page.md`](./admin-page.md) (mutation error rows).
