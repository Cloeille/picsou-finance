# Feature: Bourse Direct sync

## Scope

The connector imports Bourse Direct investment accounts as Picsou `PEA` or
`COMPTE_TITRES` accounts. A successful snapshot contains:

- the authoritative account valuation and cash balance in EUR;
- every open position, its quantity and EUR average buying price when safely
  available or derivable;
- the broker quote in its native currency;
- the broker-provided position valuation and unrealized P&L in EUR.

Orders, transactions, statements and broker performance history are
intentionally out of scope. Picsou creates one daily balance snapshot from each
successful portfolio import.

This is an unofficial, read-only integration. Bourse Direct can change its
login page or internal portfolio streams without notice. The connector is
therefore designed to fail closed: an incomplete or inconsistent response never
replaces the last valid portfolio.

## Authentication and 2FA

`services/bourse-direct-auth` is an isolated FastAPI/Playwright sidecar:

1. `POST /initiate` opens the official login page and submits the login and
   password.
2. When Bourse Direct requests 2FA, the browser context is kept in memory for at
   most ten minutes and the caller receives a one-use `processId`.
3. `POST /complete` atomically claims that process, submits the six-digit code
   and explicitly avoids trusting the device.
4. The sidecar returns Playwright's complete storage state. The Java backend
   encrypts it through `CryptoEncryption` before writing
   `bourse_direct_session`.

The login, password and one-time code are never stored or logged. Pending
browser contexts are closed after completion, failure, expiry, sidecar shutdown
and by a periodic expiry sweep. Concurrent attempts to complete the same
`processId` cannot reuse a browser context.

## Portfolio collection contract

The sidecar first listens to the current Socket.IO portfolio stream. The legacy
real-time stream remains a fallback for accounts still served by the old page.
Both paths produce the same strict `AccountPayload` model.

There is no trusted end-of-snapshot event, so completeness is established by
reconciliation:

```text
account total ~= cash + broker portfolio valuation
sum(position valuation in EUR) ~= broker portfolio valuation
```

The tolerance is the greater of EUR 0.05 and 0.1% of the expected amount. The
modern collector also waits for a three-second quiet window so a transiently
balanced partial stream is not accepted too early. Missing required monetary
fields, malformed currencies, duplicate account identifiers and foreign quotes
without an EUR valuation reject the complete snapshot.

`currentPrice` is expressed in `quoteCurrency`; `buyingPriceEur`,
`currentValueEur` and `pnlEur` are always EUR-denominated. Picsou keeps the
broker EUR valuation as a fallback when Yahoo cannot resolve a symbol or price
it reliably.

## Asynchronous import

Authentication stores the encrypted session in a short database transaction,
then queues portfolio work on the managed `bourseDirectSyncExecutor`. Manual
`POST /api/bourse-direct/sync` also returns `202 Accepted` immediately. The UI
polls `GET /api/bourse-direct/status` while the state is `QUEUED` or `RUNNING`.

```text
IDLE -> QUEUED -> RUNNING -> SUCCESS
                         -> FAILED
```

Only one job can be queued or running for a member. A job carries the database
session ID that created it; a cleared or replaced session prevents that old job
from committing. Jobs interrupted by a backend restart are marked `FAILED` on
startup so the UI never remains stuck in an in-flight state.

Stable failure codes are returned in RFC 7807 `code` fields and persisted as
`lastSyncError`: `INVALID_CREDENTIALS`, `INVALID_OTP`,
`AUTH_ATTEMPT_EXPIRED`, `SESSION_EXPIRED`, `PORTFOLIO_INCOMPLETE`,
`UPSTREAM_FORMAT_CHANGED`, `UPSTREAM_UNAVAILABLE`, `INVALID_DATA` and
`INTERNAL_ERROR`.

## Atomic persistence

Network calls, browser work and OpenFIGI resolution happen outside the database
transaction. Only after every account passes validation does a short transaction:

- upsert accounts under stable `bd_` external IDs and provider `Bourse Direct`;
- preserve user-soft-deleted accounts instead of recreating them;
- deduplicate positions that resolve to the same ticker;
- replace each account's holdings;
- flush the replacement before computing its daily snapshot;
- mark the session `SUCCESS`.

Any validation or persistence failure rolls the transaction back. The previous
holdings, account valuation and snapshot remain coherent. A failed portfolio
fetch keeps a usable session active for retry; only `SESSION_EXPIRED`
deactivates it.

The daily 08:00 scheduler calls `resyncIfSessionActive` and goes through the same
queue and completeness gates.

## Deployment

Docker Compose builds `services/bourse-direct-auth/Dockerfile`. The image uses
`python:3.12-slim-bookworm`, installs Chromium only and runs as a non-root user. The
service is reachable only from the internal Compose network. The backend URL
defaults to `http://bourse-direct-auth:8001` and can be overridden with
`BOURSE_DIRECT_AUTH_URL`; local development defaults to
`http://127.0.0.1:8002`.

The sidecar needs outbound HTTPS access to `www.boursedirect.fr`. It does not
need an ingress or any externally reachable port.

Pending 2FA browser contexts live in process memory, so the sidecar must run as
a single replica unless request affinity or a shared pending-state mechanism is
added. Synchronization jobs are fenced by their persisted session IDs, so a
session cleared or replaced while work is running cannot be overwritten by the
older job.

## Frontend

`frontend/src/components/sync/BourseDirectPanel.tsx` is a thin wrapper over
`SidecarSessionPanel`, the generic login / OTP / sync / disconnect panel shared
with Amundi: it supplies the `sync.bourseDirect.*` i18n prefix, the icon and the
`useBourseDirect*` hooks. Bourse Direct does not set `appPush`, so its second
factor is always the six-digit code form.

The reconciliation tolerance the collector uses upstream is also the one the
backend applies (`SyncValues.moneyClose`), shared with Amundi so the same
rounding drift cannot be accepted by one integration and rejected by the other.

## Verification boundaries

CI builds the real sidecar image and runs parser, lifecycle and browser
navigation tests inside it. Backend tests cover the adapter contract, member
scoping, asynchronous state transitions, stale-session fencing and atomic
replacement. Frontend tests cover 2FA errors and waiting for background success
before closing the account wizard.

A real-account end-to-end test cannot run in public CI because it requires
private credentials and a live OTP. Maintainers should perform a live smoke test
before release and after a reported Bourse Direct page change.

## Related decisions

- [Bourse Direct uses an isolated browser sidecar and atomic complete snapshots](../decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md)
- [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- [Mandatory encryption key](../decisions/2026-04-08-mandatory-encryption-key.md)
