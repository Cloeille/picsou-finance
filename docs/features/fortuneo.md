# Feature: Fortuneo sync

## Status

Fortuneo is available as an opt-in connector for cash and securities accounts.
The integration imports accounts, positions and transactions through a dedicated
Playwright sidecar. Authentication requires the customer's normal credentials
and, when requested by Fortuneo, a one-time code.

The connector is intentionally fail-closed: an incomplete account list,
portfolio or transaction page does not replace previously imported data.

## Scope

Supported data:

- current accounts and savings accounts;
- PEA, PEA-PME and CTO securities accounts;
- current balances and securities positions;
- cash-account transactions returned by Fortuneo;
- securities operations from the legacy history page;
- reconstructed daily balance history from the imported ledger.

Out of scope:

- transfers or trading actions;
- storing plaintext credentials or one-time codes;
- claiming history older than the provider makes available;
- guessing balances, instruments or partial portfolios.

## Architecture

The integration follows Picsou's ports-and-adapters structure:

1. The React client starts or resumes the authentication flow through the
   backend.
2. `FortuneoController` delegates orchestration to `FortuneoSyncService`.
3. `FortuneoAdapter` calls the private `fortuneo-auth` sidecar.
4. The sidecar drives the provider UI and returns a typed, minimal payload.
5. The backend validates the complete payload before changing accounts,
   holdings, transactions or snapshots.

The architectural boundary, completeness rules and accepted trade-offs are
recorded in the [Fortuneo ADR](../decisions/2026-07-26-fortuneo-isolated-atomic-history-sync.md).

The sidecar is not exposed publicly by Docker Compose. Its inbound interface is
on a dedicated internal network shared only with the backend; a separate
sidecar-only egress network lets Chromium reach Fortuneo without making the
credential-bearing API reachable by the rest of the stack. A custom remote
sidecar URL must use HTTPS; plain HTTP is accepted only for the isolated
`fortuneo-auth` service name or loopback development.

## Authentication and session security

`POST /api/fortuneo/auth/initiate` creates a short-lived pending flow. The sidecar
submits the provider login form and returns either:

- a pending context when a one-time code is required; or
- an authenticated session when the provider completes without MFA.

`POST /api/fortuneo/auth/complete` consumes the pending context and the one-time
code. Pending contexts are single-use, expire quickly and are removed on
failure. Credentials and one-time codes are never returned by the API, written
to logs or persisted.

If neither the authenticated page nor the one-time-code form appears before the
login deadline, the attempt is reported as an upstream availability failure.
A timeout is not treated as proof that the submitted credentials were invalid.

The authenticated browser state is encrypted by the backend before it is
stored. Session status lookups are scoped to the current member. Startup
recovery enumerates members at the system boundary, then repairs interrupted
jobs through one explicitly member-scoped update per member before scheduled
work starts. Scheduled syncs then reuse only valid stored sessions. Provider
rejections are mapped to stable error codes so the frontend can distinguish
reconnect, retry and investor-profile actions.

The backend rate-limits initiate and complete endpoints separately from normal
API traffic. Its standard CSRF, authorization and ownership checks still apply.

## Account discovery and mapping

The provider's equipment response is merged before mapping accounts. Pagination
or multipart gaps invalidate the whole response.

| Provider product | Picsou type |
|---|---|
| Current account | `CHECKING` |
| Savings account | `SAVINGS` |
| PEA | `PEA` |
| PEA-PME | `PEA` |
| CTO | `COMPTE_TITRES` |

PEA-PME intentionally shares Picsou's `PEA` type because the domain has no
separate type. Its provider subtype remains available to select the correct
legacy route.

A linked Equipment cash pocket is folded into its parent CTO only when the
relationship is unambiguous. It is an optional cross-check, not the source of
the final balance: unmatched or ambiguous pockets are discarded, and each
securities account later receives its cash from its own legacy portfolio page.

Every provider account uses a stable external identifier. The backend upserts
within the current member and provider scope and never matches accounts by
display label alone.

## Portfolio collection

Securities positions come from the provider's legacy portfolio view after the
sidecar establishes the corresponding legacy session. The parser reads:

- the provider instrument label and identifier;
- quantity, current price and average purchase price;
- the broker valuation;
- the page's securities, cash and total summaries.

French numbers may contain normal spaces, non-breaking spaces or thin spaces.
The parser normalizes all of them before decimal conversion. Missing required
columns, unreadable decimals or a declared total inconsistent with parsed rows
invalidate the snapshot.

The legacy page is authoritative for a securities account's positions, cash and
total. The sidecar verifies that the position rows match the securities summary
and that securities plus cash match the total before returning this single
snapshot. The separately fetched Equipment total is diagnostic only, because a
normal market move between the two requests must not reject valid data. Public
market data may not cover unlisted or provider-specific instruments, so Picsou
keeps the broker total when not every Fortuneo holding can be priced externally.

Cash is accepted only when it can be derived from or checked against the same
complete portfolio snapshot. Picsou does not default a securities account's
full balance to cash.

## Cash transactions

Cash accounts use the provider transaction endpoint. The product-specific
`CAV` filter is sent only for `CHECKING` and `SAVINGS` accounts; pending entries
are excluded because the current booked balance does not include them and they
would shift every reconstructed historical balance. Securities accounts use
their separate legacy ledger.

The provider response can extend beyond Picsou's former rolling window. Picsou
therefore reconciles every returned entry. Stable provider IDs are stored as
external IDs and make repeated imports idempotent.

When stable IDs are present, reconciliation removes only provider-owned rows in
the response's observed date span before inserting the new response. Manual
rows and other providers' rows are preserved. If a future response omits IDs,
the compatibility path remains bounded to the same recent window for both
deletion and insertion.

An empty response does not clear existing history because the endpoint exposes
no trustworthy completeness marker. Invalid dates or amounts reject the full
account payload.

## Securities ledger

PEA and CTO operations come from the legacy history page. The sidecar submits
the page's own search form, requests the earliest supported start date and
paginates until the declared total is collected. A missing total or partial
page rejects the ledger. The declared count, rather than a fixed 4,000-row
client limit, controls pagination; implausible totals and pages that stop making
progress fail explicitly without returning a partial or deceptively empty list.

Operation labels map to Picsou transaction types as follows:

| Provider operation | Picsou type |
|---|---|
| Cash purchase | `BUY` |
| Cash sale | `SELL` |
| Coupon or dividend payment | `DIVIDEND` |
| Financial-transaction tax or custody fee | `FEE` |
| Cash deposit or withdrawal | `DEPOSIT` or `WITHDRAWAL` from the amount sign |
| Dividend entitlement | ignored; the cash leg is imported |

Cancellation rows retain the type of the operation they reverse and carry the
provider's signed amount. Unsupported labels are preserved as `OTHER`; the
connector does not invent accounting meaning.

The page's instrument referential maps normalized labels to ISINs. Matching is
exact after accent, case and whitespace normalization. Unknown labels remain
unattached instead of being fuzzily assigned to the wrong holding. Known ISINs
use the shared OpenFIGI conversion path, with the ISIN itself as a stable
fallback when no quoted ticker is available.

Duplicate HTML rows can occur across pagination boundaries. The sidecar removes
only exact duplicates of one rendered row; distinct economic operations on the
same date remain distinct. The backend additionally enforces provider external
IDs for idempotent reconciliation.

## Balance-history reconstruction

After transaction import, `BalanceHistoryReconstructor` rebuilds daily balances
backwards from the provider's current balance.

For cash accounts, each transaction amount is reversed from the current balance
to recover earlier closing balances. Days without activity carry the prior
closing balance. Existing snapshots for the ledger range are loaded once before
the replay, so long histories do not issue one repository query per movement.

For securities accounts, quantities are replayed forward from the first trade in
the ledger and valued day by day against `price_snapshot`. A day where any held
instrument has no price is skipped rather than valued at a stale one, so a gap
in the curve means "not established", never "worth nothing".

Prices for every traded ticker and existing account snapshots are loaded in two
range queries before the daily replay. The day loop performs no repository
lookup, keeping long-lived accounts from generating a query per ticker and day
while preserving the import transaction's atomicity.

The cost basis is replayed alongside the quantities and written to the
snapshot's `invested_amount`: a purchase adds `quantity x execution price` plus
its fees, a sale removes cost at the position's average, and the day's basis is
the sum over the very same lines the day's value came from. This is what the
P&L curve subtracts (`HistoryService.buildHistory`), so the alternative --
storing the day's own value there to satisfy the NOT NULL column -- reports
every reconstructed day as a gain of exactly zero and leaves the account's whole
gain to appear as one vertical step on today's live point. A purchase the
provider reported without an execution price makes that day's basis unknowable;
the day is still drawn, at a basis equal to its value, claiming no gain rather
than a wrong one.

Because the reconstruction reads `price_snapshot`, the sync fills it first, for
every instrument in the portfolio *and* every instrument the ledger traded (a
line bought and sold last spring is held on the days in between, and its missing
price would drop them). Before that, the only thing that ever wrote that table
was `PriceBackfillRunner`, an `ApplicationRunner` covering the tickers held at
startup -- which by construction knows nothing about an account connected
afterwards, so a newly connected PEA reconstructed nothing at all until the app
was restarted and the account synced a second time. The backfill runs outside
the commit transaction and a failure only shortens the curve: it never fails the
import.

The securities cash pocket is deliberately excluded from the reconstructed
value. The legacy history page reports securities operations only, never a
transfer funding the account, so past cash could only be extrapolated by
assuming the account was never fed. Cash therefore enters value and basis
identically on the live point and the P&L is unaffected, but the balance curve
does step by the cash pocket on the day the live point takes over.

The normal snapshot scheduler may later replace the current day, but older
reconstructed days remain available.

## Asynchronous sync and atomic persistence

The manual sync endpoint starts work asynchronously and returns a status that
the frontend polls. A fenced running state prevents stale workers from
overwriting a newer attempt. Rejected executor submissions and startup recovery
transition sessions out of `SYNCING` instead of leaving them stuck.

One validated sidecar response is persisted in a single backend transaction.
Account discovery completes before deletions or replacements begin. If any
account, holding or ledger fails validation, the transaction rolls back and the
previous imported state remains intact.

Removing a Fortuneo account also removes its provider connection when no other
Fortuneo account depends on it. The sidecar session, provider-owned holdings and
scheduled work cannot recreate a deliberately deleted account.

## Deployment

Both Compose definitions include the `fortuneo-auth` service and its isolated
inbound and egress networks. Relevant settings are documented in `.env.example`
and use non-secret defaults where possible. Secrets remain runtime environment
values and must not be committed.

The standard health check verifies the sidecar process. CI builds the sidecar
image, runs its unit tests and validates both Compose configurations.

## Verification and privacy

Automated coverage includes:

- sidecar parsing, pagination, navigation fallback and lifecycle tests;
- backend adapter, controller, encryption, recovery and synchronization tests;
- PostgreSQL reconciliation tests for provider-owned transaction replacement;
- frontend API, state, error and rendering tests;
- Playwright smoke coverage for setup, sync and account deletion;
- frontend type checking, linting and production builds;
- Docker Compose configuration and sidecar health checks.

Repository fixtures retain only the provider's response structure, operation
vocabulary and number/date formats. Names, identifiers, dates, quantities and
amounts are synthetic. Screenshots, HAR files, page dumps, account composition,
balances and account-level measurements must never be committed.

Optional diagnostic artifacts can contain private information. They are
disabled by default, written only inside the sidecar container and require an
explicit `FORTUNEO_DEBUG_ARTIFACTS=true` setting for an isolated local run.
They must be deleted after diagnosis and must not be attached to public issues.

## Key files

- `services/fortuneo-auth/main.py` — sidecar API, browser lifecycle and fetches.
- `services/fortuneo-auth/fortuneo_parser.py` — response and legacy-page parsers.
- `backend/src/main/java/com/picsou/port/FortuneoPort.java` — backend port and
  typed transport records.
- `backend/src/main/java/com/picsou/adapter/FortuneoAdapter.java` — sidecar
  adapter.
- `backend/src/main/java/com/picsou/service/FortuneoSyncService.java` — sync
  orchestration and persistence.
- `backend/src/main/java/com/picsou/service/BalanceHistoryReconstructor.java` —
  historical balance reconstruction.
- `frontend/src/components/sync/FortuneoPanel.tsx` — shared authentication and
  sync UI.
- `frontend/src/pages/sync/FortuneoTab.tsx` — dedicated sync page.

## Related documentation

- [Architecture](../ARCHITECTURE.md)
- [Docker deployment](docker-deployment.md)
- [API documentation](../../backend/docs/API.md)
