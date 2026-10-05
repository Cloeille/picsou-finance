# ADR: Bourse Direct uses an isolated browser sidecar and atomic complete snapshots

> Date: 2026-07-21
> Status: ✅ Active

## Context

Picsou needs to import PEA and securities-account positions from Bourse Direct,
including login and one-time-code authentication. The available web flows are
interactive and the portfolio representations can change independently of
Picsou. A transport timeout or partial real-time stream must not be interpreted
as an empty account: doing so would erase valid holdings and create false
balance and P&L history.

Browser work is also much slower than an ordinary REST request. Holding a
database transaction or a browser-facing HTTP request open for the whole
portfolio import caused poor feedback and made retries hard to distinguish from
duplicate work.

## Decision

1. Run Bourse Direct browser automation in a dedicated, internal-only
   FastAPI/Playwright sidecar with a Chromium-only, non-root image.
2. Keep credentials and OTPs ephemeral. Persist only the complete browser
   storage state, encrypted by the Java backend.
3. Require the sidecar to return a strict, currency-explicit snapshot and prove
   completeness by reconciling account and position EUR valuations.
4. Queue portfolio import after authentication and expose persisted
   `QUEUED/RUNNING/SUCCESS/FAILED` status to the frontend.
5. Perform upstream I/O outside the database transaction, then replace holdings
   and write the daily snapshot atomically after every account validates.
6. Fence jobs by the Bourse Direct session ID. A job created by a session that
   was cleared or replaced cannot commit.
7. Fail interrupted in-flight jobs on backend startup so they remain observable
   and retryable.

## Alternatives considered

### Run Playwright inside the main application image

- **Pros**: one image and no internal HTTP contract.
- **Cons**: couples the Java runtime to Python and Chromium, enlarges every app
  deployment, and weakens the failure boundary around an unofficial scraper.

### Perform the full import synchronously in the authentication request

- **Pros**: a single request returns accounts or an error.
- **Cons**: long requests provide ambiguous progress, are vulnerable to proxy
  timeouts, and encourage keeping external I/O inside a database transaction.

### Accept partial accounts and merge only fields that arrived

- **Pros**: can display some data during an upstream format change.
- **Cons**: cannot distinguish a legitimately closed position from an omitted
  position and can preserve mutually inconsistent balance, holdings and P&L.

### Store only native prices and derive EUR values later

- **Pros**: smaller broker contract.
- **Cons**: a missing symbol mapping or FX quote makes foreign positions
  unpriceable and risks treating a native quote as EUR. The broker's reconciled
  EUR valuation is the safer fallback.

## Reasoning

The sidecar is a natural anti-corruption boundary for unstable browser details,
while the Java port remains a typed financial contract. Asynchronous status
separates user-facing request latency from import latency. Reconciliation plus
one atomic persistence phase prioritizes preservation of the last known-good
portfolio over best-effort partial freshness, which is the safer failure mode
for financial history.

## Trade-offs accepted

- Bourse Direct remains an unofficial integration that needs maintenance when
  the provider changes its pages or streams.
- Deployment has one additional internal container and a Chromium runtime.
- The sidecar runs as one replica because pending 2FA browser contexts are
  process-local.
- A completed login can report `QUEUED` before accounts appear; the frontend
  must poll status and communicate that state.
- Public CI cannot prove the live login flow without private credentials and a
  human-delivered OTP.

## Consequences

- `BourseDirectPort` is the only backend dependency on the sidecar contract.
- Position quotes carry an explicit native currency alongside broker EUR value
  and P&L fields.
- `bourse_direct_session` persists synchronization state and last stable error
  code.
- Sync failures leave existing financial rows unchanged.
- Sidecar, backend and frontend tests can verify all deterministic boundaries;
  release validation still includes a live smoke test.
