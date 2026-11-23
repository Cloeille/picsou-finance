# Convention: Error Handling

## The error type

Every handler returns `AppResult<T>`. The error is one enum, `AppError`
(`backend-rs/src/error.rs`), rendered as an RFC 7807 problem body:

| Variant | Status | Detail |
|---------|--------|--------|
| `NotFound(detail)` | 404 | the message |
| `BadRequest(detail)` | 400 | the message |
| `BadCredentials` | 401 | `"Invalid credentials"` |
| `Forbidden(detail)` | 403 | the message |
| `Status { status, detail }` | any | the message |
| `Coded { status, code, detail }` | any | the message, plus a stable `code` |
| `Validation(errors)` | 422 | title `"Validation failed"`, the field map under `errors` |
| `Problem(problem)` | any | a problem body a handler builds for a special case |
| `Internal(message)` | 500 | `"An unexpected error occurred"`; the message is logged, never sent |

## Rules

- **Handlers return errors with `?`** and never build a response for a failure by hand.
- **A module with its own failures keeps its own error type** and converts it into `AppError` at
  the edge. `EbError` (`backend-rs/src/sync/enable_banking/error.rs`) is the model: an upstream
  failure becomes a 422 with a message safe to show and an optional `code`, logged at WARN.
- **External errors** (bank APIs, crypto exchanges, price providers) never reach the client raw:
  the response carries a message written for the user, the log carries the upstream detail.
- **`Internal` never exposes its message.** The client gets the generic 500, the log gets
  `Unhandled exception: …` at ERROR.
- **404 messages are ID-free** (`"Account not found"`). The caller already knows the id, and a
  raw id in a message the user sees is noise at best and a leak at worst.

### Validation error shape (422)

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": null,
  "errors": {
    "name": "must not be blank",
    "targetAmount": "must be greater than 0.01"
  }
}
```

A request body implements `Validate` (`backend-rs/src/http/json.rs`); the `Json<T>` extractor
runs it before the handler. When two errors hit the same field, the first wins.

### Codes

Use `AppError::coded` for a domain error the frontend must translate or react to
(`PORTFOLIO_INCOMPLETE`, `MFA_ACTIVE`). Codes are API contracts; messages remain English
diagnostics and must not be parsed.

## Don'ts

- **Never answer a failure with a hand-built body** — return an `AppError`.
- **Never put an upstream message, a path or a type name in a detail** the user sees.
- **Never turn a missing upstream value into a plausible number.** A wallet RPC response that
  is an error or lacks its `result` fails the sync with a 422, it does not report a balance
  of 0. See [`docs/features/crypto-tracking.md`](../features/crypto-tracking.md).

## Swallowing rules (backend)

An error that is neither returned nor logged is a bug unless the failure is *expected and
irrelevant* (a POSIX `chmod` on Windows, a non-numeric cell while sniffing a CSV header).
Everything else follows two rules:

- **Keep the cause.** The user-facing message is deliberately generic, so the log line is the
  only remaining record of what actually broke: log the original error where it is replaced.
- **Log at the level the failure deserves.** Expected upstream flakiness → `tracing::warn!`
  with the message. A failure that should not happen is a bug → `tracing::error!` with the
  error. Auto-sync entry points split the two: an upstream failure is WARN, anything else is
  ERROR.

## Logging outside-controlled values (backend)

The log output does not rewrite messages. Wrap a value an outsider controls in a one-line
helper at the call site (`log_safe` in `backend-rs/src/identity/common.rs`, `safe` in
`backend-rs/src/sync/enable_banking/wire.rs`): request parameters and headers, usernames
(user-chosen and renamable), user-supplied tickers, uploaded file names and content, upstream
response bodies and status texts. The helper replaces line breaks and control characters, so a logged value
cannot forge a log line. Log an identifier that
must stay private as a fingerprint (`fingerprint` in the same file), never in full. Don't wrap
ids, enums, numbers or the app's own constants.

## Swallowing rules (frontend)

Degrade only when the degraded state is *honest*: a failed live-price call keeps
the backend prices (values stay right, only freshness is lost), a failed
clipboard write leaves text the user can select. Never degrade in a way that
turns missing data into a plausible number — a failed holdings fetch must reject
its query so the surface renders `ErrorState`, not a portfolio total that quietly
omits an account.

## Frontend display

All user-facing error strings derived from an Axios error MUST go through
`extractErrorMessage(err, fallback)` in `frontend/src/lib/errors.ts`. It walks the
Axios error in priority order: `response.data.detail` (the RFC 7807 problem body,
with embedded-JSON detection on adapter strings of the form
`"Enable Banking auth failed: {...}"`) → `response.data.message` → `err.message`
(skipping the Axios boilerplate `"Request failed with status code N"`) →
caller-supplied `fallback`.

Don't:

- Render `err.message` directly — it is usually `"Request failed with status code N"`.
- Render `err.response?.data?.detail` directly — adapter strings may end in a JSON
  blob and dump it to the user.
- Hand-roll a regex on the error body — nested upstream JSON objects break naive
  patterns. The helper uses `indexOf('{') + slice + JSON.parse` for that reason.

Status-to-message mapping (as in `TradeRepublicTab.formatAuthError` and
`BoursoTab.formatError`) must happen **before** the helper — only the unmapped tail
should fall through. Those formatters read the status/detail through the typed
`unknown`-safe accessors `getErrorStatus(err): number | undefined` and
`getErrorDetail(err): string | undefined` (same file) — never an inline
`(err as any).response?.status` cast. These two helpers centralise the single
`as { response?: … }` cast so call sites stay `unknown`-typed and lint-clean. Pages that need a domain-specific default pass it as the
`fallback` argument (e.g. `t('sync.tr.errors.unknownError')`).

### `formatApiError(err, t, fallbackKey?)` — the default for most call sites

`extractErrorMessage` returns a *string fallback*; `formatApiError` (same file) is
the **translated, status-aware** wrapper and is what new code should reach for. It
maps the HTTP status to an i18n key and only falls back to a backend-supplied
message when that message is genuinely user-safe:

- **401 → `common.errors.unauthorized`, 429 → `common.errors.tooManyRequests`,
  ≥500 → `common.errors.serverError`** — the backend body here is absent or the
  deliberately-vague "An unexpected error occurred", so we always translate.
- **Other 4xx →** the backend's *specific* reason **when it's user-safe** (e.g.
  "Cannot delete the last administrator") — this is why guard messages survive —
  otherwise `403 → common.errors.forbidden`, anything else → `fallbackKey`
  (default `common.error`).

"User-safe" is decided by `safeBackendMessage`, which rejects any string matching
`LEAK_PATTERN` (`Exception`, `.java`, `java.`/`org.`/`com.picsou`, the axios
`"Request failed with status code N"` boilerplate, "stack trace") and returns
`null` so the caller substitutes a friendly translation. `extractErrorMessage` is
now a thin `safeBackendMessage(err) ?? fallback`.

**Rule of thumb:** in a component with `t` available, use `formatApiError(err, t)`
(optionally a feature `fallbackKey`). Reserve bare `extractErrorMessage` for
non-React contexts where no translator is in scope. Never display `err.message`,
`err.response.data.detail`, or a `` `${status} — …` `` string directly.

The shared Axios client redirects ordinary GET 5xx errors to `/error/500`. Use
`skipGlobalErrorRedirect: true` only for queries whose failure is expected to be
shown inline by the current surface (for example bank institution search inside
the add-account modal).

### Backend message language

The backend is **English-only** (no i18n layer, apart from the validation messages that
follow `Accept-Language`); messages are fixed where the error is built. User-facing English
must be friendly and free of internals: no upstream error text appended (log it instead), no
French strings reaching the English UI, no PEM/wizard/PKCS#8 jargon for non-operator
audiences. Translation to the user's locale happens **only** on the frontend through
`formatApiError`'s i18n keys; specific backend 4xx reasons pass through verbatim.

Full feature note: [`docs/features/frontend-error-display.md`](../features/frontend-error-display.md).
