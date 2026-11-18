# Feature: Louve Invest SCPI sync

> Last updated: 2026-11-18

## Context

Louve Invest (app.louveinvest.com) is a French SCPI distributor. It publishes an official MCP
server with standard OAuth 2.1, so Picsou reads the member's SCPI shares through it: no sidecar,
no scraping, no Louve password in Picsou. The shares land in the SCPI accounts of
[scpi.md](./scpi.md), under the same invariants as CORUM and Sofidy.

## How it works

### Connection (OAuth 2.1, PKCE S256, public client)

1. **Start** — `POST /api/louve/auth/start`. The redirect URI is the browser's origin plus
   `/sync/louve/callback`. The origin is trusted when the browser says the call is same-origin
   (`Sec-Fetch-Site`), when it matches the request's own scheme, host and port, or when it is on
   the CORS allow-list of the setup wizard. It must be HTTPS, or plain HTTP on a loopback host
   for development. Picsou registers itself with Louve once per redirect URI (RFC 7591 dynamic
   client registration, `token_endpoint_auth_method: none`) and keeps the client id in
   `louve_oauth_client`. A new origin means a new registration; the old one stays for the
   sessions that still refresh with it. The member's `louve_connection` row (created on the
   first start, status `PENDING`) records the attempt: the state nonce, the encrypted PKCE
   verifier, the redirect URI and client id, and when it started (valid 10 minutes), like
   `requisition.oauth_state` for Enable Banking. A stored token set is kept meanwhile, so an
   abandoned reconnect leaves the member connected. The answer is Louve's authorize URL:
   `scope=portfolio:read` only (the registration echoes every scope; only this one is ever asked
   for), `code_challenge_method=S256`, `resource=https://app.louveinvest.com/mcp`.
2. **Consent** — the member sees Louve's page ("Lire votre portefeuille") and is sent back to
   `/sync/louve/callback?code=…&state=…&iss=…`.
3. **Callback** — the SPA page reads `{code, state, iss}`, replaces the history entry with
   `/sync?tab=louve` at once (before the call, on success and on error alike, so neither a
   reload nor the back button can replay the single-use code), then posts them to
   `POST /api/louve/auth/complete` (no state change on a GET). The result is read from the
   mutation's promise, not from `mutate`'s per-call callbacks, which React Query drops when the
   calling observer unsubscribes (StrictMode's effect replay, the route change): that is what left
   the code in the address bar in the first live run. A ref keeps the post to one. The answer
   never navigates: a refusal is shown in the panel if the member is still on the Louve tab, as
   a toast otherwise, so a member who went to another page meanwhile stays there. The backend refuses an `iss` other than
   `https://app.louveinvest.com` (RFC 9207, which Louve advertises; a missing `iss` is refused
   too), checks the state in constant time against the member's live attempt, and spends the
   attempt before exchanging the code: a replayed callback finds nothing. The exchange returns
   the token set, stored encrypted (`CryptoEncryption`) in `louve_connection.token_state`; the
   grant it replaces, if any, is revoked; the first sync then runs in the same request (a failure
   leaves the member connected with status `ERROR`). The spent attempt remembers the row and its
   `grant_generation`; the token set is stored only if the row is still at that generation, which
   then increments. A callback whose exchange was overtaken by a disconnect or by a newer consent
   stores nothing and revokes only the grant it just obtained; the newer connection is untouched. `invalid_client` drops the stored registration so the next
   attempt registers again. `error=access_denied` on the callback completes nothing.

### Sync

Like Wise and SimpleFIN, `POST /api/louve/sync` (and the daily run) syncs in the request and
answers when it is done; no network call happens inside a transaction except the refresh. The
sync:

- refreshes the access token when it expires within 60 seconds, and once more if Louve answers
  401 anyway. **Refresh tokens rotate** (verified live): each refresh returns a new refresh
  token and invalidates the old one. The refresh runs in a transaction that holds the
  `louve_connection` row lock (`SELECT … FOR UPDATE`), and the new set is committed before the
  new access token is used. A second run (the 08:00 sync and a click, or two instances) waits on
  that lock and then reads the rotated set instead of refreshing the dead one; a run whose
  token was refused while another run rotated it takes the rotated set as it is. The wait is
  bounded (`SET LOCAL lock_timeout = '5s'`; Hibernate ignores a positive lock-timeout hint on
  PostgreSQL): a run that cannot get the lock in time writes nothing, leaves the status and the
  tokens alone, and fails as a temporary contention (`UPSTREAM_UNAVAILABLE`, `FAILED` in the daily
  report).
- is fenced on the connection it started under: the row id and its `grant_generation` (a new
  consent increments it, a refresh-token rotation does not). A run overtaken by a reconnect or a
  disconnect writes neither the portfolio nor a failure status onto the connection that
  replaced it.
  `invalid_grant` on refresh is `SESSION_EXPIRED`: the connection turns `REAUTH_REQUIRED` (a new
  consent is needed) and keeps its tokens so a disconnect can still revoke them. `invalid_client`
  on refresh does the same and also drops the stored registration, so the reconnect registers
  again instead of reusing the dead client. The token set records the redirect URI its client was
  registered for (a required field: a state without it is unreadable, `INVALID_SESSION_STATE`), and the drop is one statement, `DELETE … WHERE redirect_uri = ? AND client_id = ?`:
  a newer registration is kept, and two instances forgetting the same client both succeed (one
  with 0 rows). A failure of that step is logged by type and never replaces the refusal, so the
  connection still turns `REAUTH_REQUIRED`.
- opens an MCP session (Streamable HTTP, protocol `2025-06-18`: `initialize`, the
  `notifications/initialized` notification, `Mcp-Session-Id` echoed when Louve sets one), calls
  `get_portfolio {includeExternal: false}`, then `get_scpi {scpi}` once per fund. Answers come as
  plain JSON or as SSE; the SSE body is split on `\n` only (one trailing `\r` dropped), never on
  every Unicode line terminator, because the payload may carry U+2028 or U+0085. Decimals are
  read as `BigDecimal`, never as doubles. A 502/503/504 or an unreachable host is retried once
  after 2 seconds, then `UPSTREAM_UNAVAILABLE`. 403 is `SESSION_EXPIRED` (scope no longer
  granted), 429 `RATE_LIMITED`. A fund sheet Louve fails to serve (`isError`) is skipped; the
  line's own value is used.
- refuses any answer of the wrong shape with `UPSTREAM_FORMAT_CHANGED` before anything is
  written: a tool result that is not a JSON object (`[]` would otherwise read as an empty
  portfolio and report every known fund missing), a portfolio without a `scpi` key, a `scpi`
  object without a `positions` array, a fund sheet without its `urlId`, and an `ownership` that
  is not an array of strings (missing and `null` included; live data always carries
  `["Pleine propriété"]`). No element is filtered out: dropping an object from
  `["Pleine propriété", {...}]` would value a dismembered line as full ownership. Only an
  explicit `"scpi": null` (or an empty `positions` array with a zero total) is a valid empty
  portfolio.

`includeExternal: false`: external lines are SCPIs the member declared on Louve as held
elsewhere. Louve does not hold them, and the member may already sync them from there.

`get_portfolio` is taken as authoritative for the held share count. Whether its `nbShares`
includes purchases still `inProgress` in `list_transactions` (not yet confirmed by the
management company) cannot be told from the MCP data; Picsou assumes it does not and reads no
transaction.

### Mapping (`LouvePortfolioMapper`)

| Picsou | Louve | Rule |
|---|---|---|
| fund code (`scpi_position.louve_fund_code`) | `positions[].scpiUrlId` (= `get_scpi.urlId`) | A slug, at most 40 characters; anything else is `INVALID_DATA` |
| share count | `nbShares` | |
| withdrawal price (the balance) | `get_scpi.sellingPriceByShare` | Cross-checked with `position.sellingPrice / nbShares` (a total for the line). Within a cent, the published price wins; beyond, the **lower** of the two, so a disagreement can understate the share, never overstate it. A warning logs the fund code only. Neither present: `PRICE_INCOMPLETE` |
| subscription price | `get_scpi.buyingPriceByShare` | Stored beside the balance, never used as it |
| management company | `get_scpi.managementCompany` | The fund's own company ("Exemple REIM"), not Louve |
| ISIN | `get_scpi.isinCode` | Only when it passes the ISO 6166 check digit. Louve also puts internal references there (`SCPI99990001`), which have an ISIN's shape but not its check digit |
| annual distribution per share | `get_scpi.distributionRate × buyingPriceByShare`, to the cent | The French *taux de distribution* is defined on the subscription price. Written with the cadence only when the rate (a fraction in [0, 1)), the subscription price and a known `rentFrequency` (`monthly` → `MONTHLY`, `quarterly` → `QUARTERLY`) are all there; otherwise the stored figures (perhaps typed by the member) are left alone. It feeds the dividend forecast. It is a rate published by the fund, not a payment: a fund sheet with `isDistributionEstimated: true` gives a target rate, not one paid. No distribution is ever written as a transaction on the SCPI account (`isLedger()` is false for `SCPI`); cash dividends arrive on the bank account and sync there |
| currency | — | Always `EUR` |

Before anything is written, the lines must add up to `scpi.sellingPrice` within one euro, or the
run fails with `PORTFOLIO_INCOMPLETE` and nothing changes, like Sofidy's envelope check. Two
full-ownership lines for one fund are `INVALID_DATA`.

**Dismembered ownership.** A line whose `ownership` array is not exactly `["Pleine propriété"]`
(nue-propriété, usufruit, several entries, or an empty array) is not valued: Louve quotes the
full-ownership price, which a bare-ownership or usufruct right is not worth. The fund is
recorded with the outcome `UNSUPPORTED_OWNERSHIP` (V152); no account is created or linked, and an
account already linked keeps its last balance untouched. A fund held both ways syncs only its
full-ownership line.

**Account linking.** `ScpiFundReconciler` with provider `LOUVE`, same rules as CORUM and Sofidy
(IGNORED / UPDATED / LINKED by normalized name / AMBIGUOUS / CREATED / MISSING_AT_PROVIDER, the
`scpi_provider_fund` tombstones). Louve is a distributor (`ScpiProvider.distributor()`): a manual
account named after the distributor and the fund ("Louve Invest - Aurore", "Louve Invest Aurore") is
adopted as well as one named "Aurore". That is still an exact match after normalization, and both
spellings count towards `AMBIGUOUS`. It is not extended to CORUM or Sofidy, which are management
companies whose fund names already carry their brand. A fund Louve no longer lists is reported
`MISSING_AT_PROVIDER` and its account is left untouched, as CORUM does; Sofidy's zeroing is not
copied for a connector that has not been verified live.

### Daily sync, status and disconnect

- `GET /api/louve/status` (`{connected, status, lastSyncedAt}`, never a token), `POST
  /api/louve/sync` (throttled by the shared per-IP sync bucket), `DELETE /api/louve/connection`
  (deletes the row, then revokes the refresh and access tokens on Louve, best effort, once the
  deletion has committed: inside an account deletion, a `TransactionSynchronization` defers the
  call to `afterCommit`, so a rolled-back deletion keeps both the row and a live grant). Revocation is attempted for any token set the row still decrypts, whatever its
  status: a connection Louve already refused may still hold a grant. A row holding only a pending
  attempt holds no token and revokes nothing. A new consent revokes the grant it replaces.
- Status: `PENDING` (no consent yet), `CONNECTED`, `ERROR` (the last sync failed, retried by the
  next one) or `REAUTH_REQUIRED` (Louve refused the grant: `SESSION_EXPIRED`,
  `INVALID_SESSION_STATE`, `invalid_client`).
- Start and callback share a per-IP bucket of 10 calls per 15 minutes (`louveAuthBuckets`).
- The daily 08:00 member sync runs `louve` last: no token set is `SKIPPED_NOT_CONNECTED`;
  `REAUTH_REQUIRED` is `NEEDS_REAUTH` without calling Louve again (a dead refresh token is not
  replayed daily); otherwise the sync runs and is `SYNCED`, or `NEEDS_REAUTH` / `FAILED` from its
  error code, like the other API connectors.
- Deleting the member's last SCPI account linked to a Louve fund removes the connection (and
  revokes the grant), through `AccountConnectionService` as for CORUM's SCPI accounts and the
  other API connectors; the deletion takes the member lock first, the order the sync's commit
  uses.
- No boot recovery and no executor: nothing is queued, as for Wise and SimpleFIN. The admin
  instance status is unchanged: it probes the in-use sidecars' `/health`, and none of the API
  connectors is listed there.

### Key files

- `adapter/LouveOAuthClient.java` — registration, authorize URL, code exchange, refresh, revocation
- `adapter/LouveMcpClient.java` — JSON-RPC over Streamable HTTP, SSE parsing, one retry
- `adapter/LouveAdapter.java` — token state, refresh and rotation, the two read tools
- `service/LouvePortfolioMapper.java` — the SCPI invariants applied to Louve's figures
- `model/LouveConnection.java`, `repository/LouveConnectionRepository.java` — the connection row and its lock
- `service/LouveSyncService.java` — connection attempt, callback, sync, daily entry, disconnect
- `service/LouveClientRegistry.java` — one registration per redirect URI
- `controller/LouveController.java` — `/api/louve/*`, redirect-origin check
- `frontend/src/components/sync/LouvePanel.tsx` — connect, callback, sync, disconnect

### Flow

```
Connect ─▶ POST /api/louve/auth/start ─▶ authorize URL (scope portfolio:read, PKCE S256)
        ─▶ Louve consent page ─▶ /sync/louve/callback?code&state&iss
        ─▶ POST /api/louve/auth/complete ─▶ iss + state + TTL checked, attempt spent
        ─▶ token exchange ─▶ louve_connection.token_state (encrypted) ─▶ first sync

Sync ─▶ [row lock] refresh if needed, commit the rotated set ─▶ initialize ─▶ get_portfolio
     ─▶ get_scpi × funds ─▶ envelope + ownership + price rules ─▶ ScpiFundReconciler
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Official MCP + OAuth in the backend | Read-only, consented, no password, no page format to break | A browser sidecar scraping the client space |
| A `louve_connection` table, synchronous sync | The precedent of the container-less API connectors (SimpleFIN, Wise, IBKR) | A `sidecar_session` row and the queued sidecar cycle, which exist for sidecars |
| Refresh under the connection row lock | Rotating refresh tokens: one refresh per member, across threads and instances | A single-worker executor, which only serialises one instance |
| Instance-wide `louve_oauth_client` | A registration belongs to the origin, not to a member | One registration per member, which multiplies clients on Louve |
| Lower withdrawal price on disagreement | Never overstates net worth | Always the sheet, or always the line |
| Skip dismembered lines | Their value is not the quoted price | Value them as full ownership |

## Gotchas / Pitfalls

- Louve's registration echoes every scope it supports; asking for more than `portfolio:read` at
  authorize time would grant write scopes (simulations, external transactions) Picsou never needs.
- A lost refresh-token rotation forces a reconnect: never use a refreshed access token before
  its state is stored.
- No log line carries a token, a code, the verifier, a response body or a financial figure
  (fund codes and HTTP statuses only). Exception messages are written for
  `GlobalExceptionHandler`, which logs them, so they carry no figure either.
- The callback must not be followed by a GET that changes state; the SPA posts it.
- Out of scope, for a follow-up: life-insurance contracts (Louve Infinity,
  `list_investment_contracts`, `investmentContracts` in `get_portfolio`), transactions
  (`list_transactions`) and payment plans.

## Tests

- `LouveOAuthClientTest` — RFC 7636 S256 vector, authorize parameters, public-client
  registration, code exchange form, `invalid_grant` on refresh and on exchange, `invalid_client`,
  non-Bearer answer, transient statuses, revocation never throws
- `LouveMcpClientTest` — headers and session id, SSE split on `\n` only (U+2028 / U+0085 in the
  payload, CRLF, comments, a notification before the answer), exact decimals, structured content,
  401 / 403 / 429, one retry on 503, tool error vs JSON-RPC error
- `LouveAdapterTest` — expiry margin, refresh and rotation (old refresh token kept when none comes
  back), a refused client naming its registration, a revoked refresh token, an unreadable state or
  one without its redirect URI, a sheet for another fund, a skipped sheet
- `LouvePortfolioMapperTest` — sheet vs line price, lower price wins, missing prices, envelope,
  dismembered lines, duplicates, ISIN check digit, distribution rate and cadence
- `LouveSyncServiceTest` — attempt and PKCE with the tokens kept, wrong issuer, wrong state, TTL,
  replay, `invalid_client` (and a failing forget), disconnected during the exchange, replaced
  grant revoked, refresh stored before the read, one refresh on a 401, a set rotated by another
  run, statuses after each failure, the daily entry, revocation whatever the status, a stale
  callback or a sync on the old grant never overwriting a newer connection, a rotation keeping the
  generation, a busy lock leaving the connection alone
- `LouveConnectionPostgresTest` — the V152 schema against the entity; two concurrent runs on an
  expiring token refreshing it once; a lock held too long ending as a contention within the bound;
  a disconnect rolled back by its caller revoking nothing (real PostgreSQL)
- `AccountConnectionServiceTest#*Louve*` — the last linked SCPI account removes the connection
- `LouveControllerTest` — redirect origin: same-origin, allow-listed, refused, loopback HTTP only
- `ScpiFundReconcilerTest#louve*`, `#aDismembered*` — prefixed name adoption, ambiguity,
  management company / ISIN / distribution, unsupported ownership
- `LouvePanel.test.tsx` — no credential field, callback posted once and URL cleaned, consent
  refused, refused code, reconnect state

The live run of 2026-11-17 and what it did not cover are recorded in the [ADR](../decisions/2026-11-17-louve-invest-official-mcp.md#live-verification).

## Links

- ADR: [Louve Invest through its official MCP server and OAuth, not a sidecar](../decisions/2026-11-17-louve-invest-official-mcp.md)
- Feature: [SCPI shares](./scpi.md)
- ADR: [One sidecar session table](../decisions/2026-11-02-one-sidecar-session-table.md)
