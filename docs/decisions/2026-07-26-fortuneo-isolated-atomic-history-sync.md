# ADR: Fortuneo uses an isolated browser sidecar and complete atomic history sync

> Date: 2026-07-26
> Status: ✅ Active

## Context

Picsou needs to import Fortuneo current accounts, PEA, PEA-PME and securities
accounts. Fortuneo does not expose a supported public API for this use case. Its
login crosses an SSO flow and a six-digit second factor, while portfolio data is
split between authenticated API responses and legacy HTML pages.

Those sources do not have identical update timing. Equipment totals, securities
lines, cash pockets and transaction pages can briefly disagree. Treating a
partial response as complete would silently erase holdings or truncate history,
which is worse than retaining the last known-good snapshot.

Securities history can also exceed the legacy 100-row page size and the former
4,000-row assumption. Historical valuation then needs daily prices and existing
snapshots without issuing one database query per ticker and day.

## Decision

1. Run Fortuneo browser automation in a dedicated internal-only FastAPI and
   Playwright sidecar, built as a Chromium-only non-root container.
2. Keep the login, password and OTP ephemeral. Persist only opaque browser
   storage state, encrypted by the Java backend, and never log credentials,
   account identifiers or financial payloads.
3. Bind each pending authentication process to the member that initiated it.
   Completing another member's process is rejected before session persistence.
4. Treat the legacy portfolio summary as the authoritative snapshot for
   securities value, cash and total. Reconcile its internal equation and the
   parsed position sum within a fixed EUR tolerance. Equipment cash pockets are
   diagnostic only and cannot invalidate an otherwise complete snapshot.
5. Import securities history until the provider-declared row count is reached.
   Reject missing, changing, truncated or implausibly large pagination. Import
   only posted current-account entries and use stable provider transaction ids
   for idempotent full-history reconciliation.
6. Queue synchronization after authentication and persist
   `IDLE/QUEUED/RUNNING/SUCCESS/FAILED` status. Fence jobs by session id and mark
   interrupted jobs failed during startup recovery.
7. Perform Fortuneo I/O outside the database transaction. Validate every
   account first, then replace holdings, transactions and snapshots atomically,
   preserving all previous data when any write fails.
8. Backfill required market prices before reconstructing securities history.
   Batch-load prices and existing snapshots once, then replay daily quantity,
   value and invested amount from memory.

## Alternatives considered

### Run Playwright inside the Java application image

- **Pros**: one container and no private HTTP contract.
- **Cons**: couples Java to Python and Chromium, enlarges every deployment and
  removes the process boundary around an unofficial browser integration.

### Use only direct HTTP calls

- **Pros**: lower resource usage and faster startup.
- **Cons**: cannot reliably reproduce the SSO and browser session lifecycle, and
  would couple unstable Fortuneo cookies and page choreography to the backend.

### Accept and merge partial provider responses

- **Pros**: some data remains fresh during a provider-side format change.
- **Cons**: cannot distinguish an omitted row from a closed position and can
  permanently corrupt balances, holdings and historical P&L.

### Stop history import at a fixed page count

- **Pros**: bounded request volume and simple control flow.
- **Cons**: silently truncates valid accounts once their operation count crosses
  the chosen limit. A declared-count protocol provides a safer bound.

### Reconstruct history with per-day repository lookups

- **Pros**: straightforward implementation with small intermediate structures.
- **Cons**: creates an `O(days × tickers)` query pattern and holds the
  synchronization transaction open while repeatedly accessing the database.

## Reasoning

The sidecar is an anti-corruption boundary for Fortuneo-specific browser and
parsing behavior. The Java port exposes only a strict financial contract, while
the persisted state machine makes slow synchronization observable and retryable.

Failing closed protects historical truth. One authoritative portfolio summary,
declared-count transaction pagination and one atomic persistence phase make it
possible to prove that a response is complete before replacing known-good data.
Batching the reconstruction inputs preserves the same calculations without the
query amplification of a nested date/ticker loop.

## Trade-offs accepted

- Fortuneo remains an unofficial integration that will require maintenance when
  its authentication flow, API responses or legacy pages change.
- Deployment gains one Chromium sidecar and pending authentication contexts are
  process-local, so the sidecar runs as a single replica.
- The private backend-to-sidecar connection uses HTTP on an isolated Docker
  network. It is not published outside the host. Credentials and the one-time
  code cross that hop in cleartext, which is an **accepted risk**, not an
  oversight: the only attacker who can read the bridge is one who already holds
  root on the host, and that attacker also holds `CRYPTO_ENCRYPTION_KEY` (which
  decrypts every stored session), the Postgres volume, and the JVM's memory.
  Encrypting one loopback-equivalent hop does not change that outcome, while
  certificate provisioning and rotation would become a permanent burden for
  every self-hoster. The same reasoning already governs the app-to-proxy hop
  (see the [Caddy opt-in TLS ADR](./2026-07-19-caddy-opt-in-tls-profile.md)):
  TLS is terminated at the edge, not between co-located containers. This
  decision reverses the moment the sidecar stops being co-located — and that
  case is already enforced in code, since `FortuneoAdapter.validateBaseUrl`
  accepts plain HTTP only for the isolated Compose service name and loopback,
  and requires HTTPS for any other host.
- Strict reconciliation can reject a temporarily inconsistent provider response
  and leave data stale until the next successful sync.
- Public CI verifies deterministic browser navigation and parsers, but cannot
  exercise a live account and human-delivered OTP.

## Consequences

- `services/fortuneo-auth` owns browser login, provider calls, parsing and strict
  response validation.
- `FortuneoPort`, `FortuneoAdapter`, `FortuneoSyncService` and
  `FortuneoTransactionWriter` isolate transport, orchestration and persistence.
- `fortuneo_session` stores encrypted browser state and observable job status;
  Flyway migrations `V80` and `V81` contain the complete Fortuneo schema and
  integration setting without colliding with active feature branches. `V80`'s
  unique index on `transaction (account_id, external_id)` is partial on
  `external_id IS NOT NULL` and is created in the same migration that adds the
  column, so no row qualifies while it is built — a plain `CREATE INDEX` is one
  heap scan on a single-family database during an upgrade that has already
  stopped the previous container.
- Credentials and OTPs are never persisted, and sync failures leave imported
  accounts, holdings, transactions and history unchanged.
- Sidecar, backend, frontend and end-to-end suites cover deterministic contract,
  lifecycle, reconciliation and navigation behavior.
