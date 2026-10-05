# Convention: Error Handling

## Exception hierarchy

```
RuntimeException
  +-- ResourceNotFoundException      404 NOT_FOUND
  +-- SyncException                  422 UNPROCESSABLE_ENTITY
  +-- WalletRpcException             422 UNPROCESSABLE_ENTITY (adapter-level, see below)
  +-- BadCredentialsException        401 UNAUTHORIZED   (Spring Security)
  +-- IllegalArgumentException       400 BAD_REQUEST
  +-- MethodArgumentNotValidException 422 UNPROCESSABLE_ENTITY (via @Valid)

Exception (catch-all)               500 INTERNAL_SERVER_ERROR
```

There is **no** `AppException` base class. Each exception type is standalone.

## Rules

- **Services throw business exceptions** — controllers never catch or handle them.
- **Controllers never wrap responses in try/catch** — `GlobalExceptionHandler` handles everything.
- **External errors** (bank APIs, crypto exchanges, price providers) are wrapped in `SyncException` with the original cause.
- **Stack traces are never exposed** to clients (`server.error.include-stacktrace: never`, `include-message: never`).
- **Generic 500 errors** always return `"An unexpected error occurred"` — the real exception is logged server-side via `log.error()`.

## GlobalExceptionHandler

**File:** `com.picsou.exception.GlobalExceptionHandler`

A `@RestControllerAdvice` that extends `ResponseEntityExceptionHandler`. Returns `ProblemDetail` (RFC 7807) for every case.

| Handler method | Exception | Status | Detail |
|---------------|-----------|--------|--------|
| `handleNotFound` | `ResourceNotFoundException` | 404 | `ex.getMessage()` |
| `handleSync` | `SyncException` | 422 | `ex.getMessage()` plus optional stable `code` (logged at WARN) |
| `handleWalletRpc` | `WalletRpcException` | 422 | generic `"Could not reach the blockchain network…"` (logged at WARN) |
| `handleBadCredentials` | `BadCredentialsException` | 401 | `"Invalid credentials"` |
| `handleIllegalArgument` | `IllegalArgumentException` | 400 | `ex.getMessage()` |
| `handleMethodArgumentNotValid` | `MethodArgumentNotValidException` | 422 | Field map under `"errors"` key |
| `handleGeneric` | `Exception` (fallback) | 500 | `"An unexpected error occurred"` |

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

The `errors` map is built from `FieldError.getField()` and `FieldError.getDefaultMessage()`. When two errors hit the same field, the first wins.

## Custom exceptions

### ResourceNotFoundException

```java
// Static factories for consistent messages
ResourceNotFoundException.account(Long id);       // "Account not found"
ResourceNotFoundException.goal(Long id);          // "Goal not found"
ResourceNotFoundException.requisition(String id); // "Requisition not found"
ResourceNotFoundException.transaction(Long id);   // "Transaction not found"
```

**Messages are ID-free.** The factories take the id (handy for logging / call-site
clarity) but deliberately do **not** interpolate it into the user-facing message.
Leaking a raw resource id into a 404 the user sees is noise at best and an
information leak at worst — the id is already known to the caller. Keep new
factories ID-free for the same reason.

### SyncException

```java
// Wraps upstream provider failures
new SyncException("Enable Banking API error: ...");
new SyncException("Binance API timeout", cause);  // with original cause
new SyncException("Incomplete portfolio", cause, "PORTFOLIO_INCOMPLETE");
```

Logged at WARN level so upstream flakiness is trackable without alert fatigue.
Use the optional code for domain errors that the frontend must translate or
react to. Codes are API contracts; messages remain English diagnostics and must
not be parsed.

## Adding a new exception type

1. Extend `RuntimeException` directly (no base class).
2. Add a handler method in `GlobalExceptionHandler` returning `ProblemDetail`.
3. Throw it from a service — never from a controller.

## Don'ts

- **Never catch exceptions in controllers** — let `GlobalExceptionHandler` handle them.
- **Never create an `AppException` base class** — each exception type extends `RuntimeException` directly.
- **Never expose stack traces to clients** — the generic 500 handler returns "An unexpected error occurred".
- **Never throw business exceptions from adapters** — wrap external errors in `SyncException`.
  The one sanctioned exception is `WalletRpcException`: a *technical* signal (no user-facing
  message) a wallet adapter throws when a blockchain JSON-RPC response is an error / missing
  its `result`, so it can't silently report a 0 balance. `WalletSyncService.sync()` catches
  it and wraps it in a friendly `SyncException`; `GlobalExceptionHandler` maps it to a generic
  `422` as defense-in-depth. The business rule still holds — this is a technical, not a
  business, exception. See [`docs/features/crypto-tracking.md`](../features/crypto-tracking.md).

## Swallowing rules (backend)

A `catch` that neither rethrows nor logs is a bug unless the failure is *expected
and irrelevant* (a POSIX `chmod` on Windows, a non-numeric cell while sniffing a
CSV header). Everything else follows two rules:

- **Keep the cause.** Wrapping (`SyncException`, `MfaException`,
  `IllegalArgumentException`) always passes the original exception as the cause.
  The user-facing message is deliberately generic, so the cause is the only
  remaining record of what actually broke.
- **Log at the level the failure deserves.** Expected upstream flakiness →
  `log.warn` with the message. Anything that reaches a `catch (Exception |
  RuntimeException)` fallback is a bug → `log.error(msg, ex)` *with the
  exception object*, never `ex.getMessage()` alone (which prints `null` for an
  NPE). Auto-sync entry points (`resyncIfSessionActive`) split the two: a
  `SyncException` is WARN, any other `RuntimeException` is ERROR with a trace.

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
Axios error in priority order: `response.data.detail` (Spring `ProblemDetail`,
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

The backend is **English-only** (no i18n layer — see `backend/CLAUDE.md`); messages
are fixed at the throw site. User-facing English must be friendly and free of
internals: no concatenated `ex.getMessage()` (log it instead via `log.warn`), no
French strings reaching the English UI, no PEM/wizard/PKCS#8 jargon for non-operator
audiences. Translation to the user's locale happens **only** on the frontend through
`formatApiError`'s i18n keys; specific backend 4xx reasons pass through verbatim.

Full feature note: [`docs/features/frontend-error-display.md`](../features/frontend-error-display.md).
