# Feature: Louve Invest SCPI sync

> Last updated: 2026-11-18

## Context

Louve Invest (app.louveinvest.com) is a French SCPI distributor with an official MCP server and
standard OAuth 2.1. Picsou reads the member's SCPI shares through it: no sidecar, no scraping, no
Louve password. The shares land in the SCPI accounts of [scpi.md](./scpi.md), under the same
invariants as CORUM and Sofidy.

## How it works

### Connection (OAuth 2.1, PKCE S256, public client)

1. **Start**: `POST /api/louve/auth/start`. The redirect URI is the browser's `Origin` when it is
   one of the instance's configured origins (Administration > Security, or `ALLOWED_ORIGINS`
   before a list is saved: `DynamicCorsConfigurationSource.originPolicy()`), plus
   `/sync/louve/callback`. Otherwise the answer is a 400 with code `ORIGIN_NOT_ALLOWED`, which
   the panel turns into "add this address in Administration > Security". A listed origin must be
   HTTPS, or HTTP on a loopback host (`ORIGIN_NOT_SECURE`). `LouveClientRegistry` registers
   Picsou once per redirect URI (RFC 7591, `token_endpoint_auth_method: none`) and keeps the
   client id in `app_setting` under `louve.client-id:<SHA-256 hex of the redirect URI>`. The
   member's `louve_connection` row records the attempt (state nonce, encrypted PKCE verifier,
   redirect URI, client id, start time, valid 10 minutes); stored tokens are kept meanwhile. The
   answer is Louve's authorize URL: `scope=portfolio:read` only, S256,
   `resource=https://app.louveinvest.com/mcp`.
2. **Consent** on Louve's page, back to `/sync/louve/callback?code=…&state=…&iss=…`.
3. **Callback**: the SPA replaces the history entry with `/sync?tab=louve` before posting
   `{code, state, iss}` to `POST /api/louve/auth/complete`, so neither reload nor back replays
   the code. It reads the result from the mutation's promise (React Query drops per-call
   callbacks when the observer unsubscribes) and posts once (a ref guards StrictMode). A refusal
   shows in the panel, or as a toast if the member left the tab. The backend requires
   `iss=https://app.louveinvest.com` (RFC 9207), checks the state in constant time, spends the
   attempt before the exchange, stores the encrypted token set only if the row is still at the
   attempt's `grant_generation` (then increments it), revokes the grant it replaces, and runs the
   first sync. An overtaken callback stores nothing and revokes only its own grant.
   `invalid_client` drops the registration; `error=access_denied` completes nothing.

### Sync

`POST /api/louve/sync` and the daily run sync in the request, like Wise and SimpleFIN; no network
call happens inside a transaction except the refresh.

- **Refresh** when the access token expires within 60 seconds, and once more on a 401. Refresh
  tokens rotate, so the refresh holds the `louve_connection` row lock and commits the new set
  before using it; a concurrent run reads the rotated set. The wait is bounded
  (`SET LOCAL lock_timeout = '5s'`): a run that cannot lock writes nothing and fails as
  `UPSTREAM_UNAVAILABLE`.
- **Fencing**: a run carries its row id and `grant_generation`; one overtaken by a reconnect or a
  disconnect writes neither portfolio nor status.
- **Refusals**: `invalid_grant` is `SESSION_EXPIRED` → `REAUTH_REQUIRED`, tokens kept so a
  disconnect can revoke them. `invalid_client` does the same and drops the registration with
  `DELETE FROM app_setting WHERE setting_key = ? AND value = ?`: a newer registration survives and
  two instances forgetting the same client both succeed. A failing drop is logged by type and
  never replaces the refusal. The token set records its redirect URI (required, else
  `INVALID_SESSION_STATE`).
- **MCP**: `initialize`, `notifications/initialized`, `Mcp-Session-Id` echoed, then
  `get_portfolio {includeExternal: false}` and `get_scpi` per fund. JSON or SSE answers; SSE is
  split on `\n` only (payloads may carry U+2028 / U+0085); decimals read as `BigDecimal`. A
  502/503/504 or an unreachable host is retried once after 2 seconds, then
  `UPSTREAM_UNAVAILABLE`; an interrupt during that pause stops without retrying. 403 is
  `SESSION_EXPIRED`, 429 `RATE_LIMITED`. A fund sheet Louve fails to serve is skipped.
- **Shape checks** before any write (`UPSTREAM_FORMAT_CHANGED`): a non-object tool result, no
  `scpi` key, no `positions` array, a sheet without `urlId`, an `ownership` that is not an array
  of strings. Nothing is filtered out of `ownership`. Only `"scpi": null` (or empty positions
  with a zero total) is an empty portfolio.

External lines (`includeExternal`) are held elsewhere and not synced. `get_portfolio.nbShares` is
taken as the held count; purchases still in progress are assumed excluded.

### Mapping (`LouvePortfolioMapper`)

| Picsou | Louve | Rule |
|---|---|---|
| fund code (`scpi_position.louve_fund_code`) | `positions[].scpiUrlId` | A slug of at most 40 characters, else `INVALID_DATA` |
| share count | `nbShares` | |
| withdrawal price (the balance) | `get_scpi.sellingPriceByShare` | Cross-checked with `sellingPrice / nbShares`; within a cent the sheet wins, beyond it the **lower** one. Neither: `PRICE_INCOMPLETE` |
| subscription price | `get_scpi.buyingPriceByShare` | Stored, never used as the balance |
| management company | `get_scpi.managementCompany` | The fund's company, not Louve |
| ISIN | `get_scpi.isinCode` | Only with a valid ISO 6166 check digit (Louve also puts internal codes there) |
| annual distribution per share | `distributionRate × buyingPriceByShare` | Written with the cadence (`monthly`, `quarterly`) only when all inputs are valid; a published rate, never a transaction |
| currency | | Always `EUR` |

The lines must add up to `scpi.sellingPrice` within one euro, else `PORTFOLIO_INCOMPLETE` and
nothing changes. Two full-ownership lines for one fund are `INVALID_DATA`.

**Dismembered ownership**: a line whose `ownership` is not exactly `["Pleine propriété"]` is
recorded `UNSUPPORTED_OWNERSHIP` (V153), never linked, created or valued.

**Account linking**: `ScpiFundReconciler` with provider `LOUVE`, same outcomes as CORUM and
Sofidy. As a distributor, Louve also adopts an account named "Louve Invest - Aurore" or "Louve
Invest Aurore" (exact after normalization, both spellings count towards `AMBIGUOUS`). A fund no
longer listed is `MISSING_AT_PROVIDER` and left untouched, as CORUM does.

### Status, daily sync and disconnect

- `GET /api/louve/status` (`{connected, status, lastSyncedAt}`), `POST /api/louve/sync` (shared
  per-IP sync bucket), `DELETE /api/louve/connection`: deletes the row, then revokes the tokens on
  Louve after commit (best effort; a rolled-back account deletion keeps row and grant). Any token
  set the row still decrypts is revoked, whatever its status.
- Statuses: `PENDING`, `CONNECTED`, `ERROR` (retried next time), `REAUTH_REQUIRED`.
- Start and callback share a per-IP bucket of 10 calls per 15 minutes (`louveAuthBuckets`).
- Daily 08:00 run, source `louve`: no tokens is `SKIPPED_NOT_CONNECTED`; `REAUTH_REQUIRED` is
  `NEEDS_REAUTH` without calling Louve; otherwise `SYNCED`, `NEEDS_REAUTH` or `FAILED`.
- Deleting the last SCPI account linked to a Louve fund removes the connection through
  `AccountConnectionService`, after taking the member lock
  (`lockMemberForAccountDeletion`, `MANDATORY` transaction) in the sync's order.
- No boot recovery, executor or instance-status probe: nothing is queued.

### Key files

`adapter/LouveOAuthClient`, `LouveMcpClient`, `LouveAdapter` (protocol, token state, read tools);
`service/LouvePortfolioMapper` (SCPI invariants), `LouveSyncService` (attempt, callback, sync,
disconnect), `LouveClientRegistry` (client ids in `app_setting`); `controller/LouveController`
(redirect URI from the configured origins); `frontend/src/components/sync/LouvePanel.tsx`.

## Technical choices

The decisions and their rejected alternatives are in the [ADR](../decisions/2026-11-17-louve-invest-official-mcp.md).

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Lower withdrawal price on disagreement | Never overstates net worth | Always the sheet or the line |
| Skip dismembered lines | Their value is not the quoted price | Value them as full ownership |

## Gotchas / Pitfalls

- The registration echoes every scope; asking for more than `portfolio:read` would grant write
  scopes.
- Never use a refreshed access token before its state is stored: a lost rotation forces a
  reconnect.
- The lock wait is bounded with `SET LOCAL lock_timeout`: Hibernate ignores a positive
  lock-timeout hint on PostgreSQL.
- No log line carries a token, code, verifier, response body or figure; exception messages are
  logged by `GlobalExceptionHandler`, so they carry no figure either.
- A member reaching Picsou through an address missing from Administration > Security cannot
  connect until the operator adds it.
- Out of scope: life insurance (Louve Infinity), transactions, payment plans.

## Tests

`LouveOAuthClientTest`, `LouveMcpClientTest`, `LouveAdapterTest` (protocol, SSE, retry and
interrupt, rotation); `LouvePortfolioMapperTest`, `ScpiFundReconcilerTest#louve*`;
`LouveSyncServiceTest` (callback checks, fencing, refresh, statuses); `LouveControllerTest`
(redirect URI, refusal codes); `LouveClientRegistryTest` and `LouveClientRegistryPostgresTest`
(hashed key, first stored wins, compare-and-delete); `LouveConnectionPostgresTest` (V153, one
refresh for two runs, bounded lock, deferred revocation, `MANDATORY` lock);
`AccountConnectionServiceTest#*Louve*`; `LouvePanel.test.tsx`.

## Links

- ADR: [Louve Invest through its official MCP server and OAuth, not a sidecar](../decisions/2026-11-17-louve-invest-official-mcp.md)
- Feature: [SCPI shares](./scpi.md), [Security, CORS and cookies](./security-cors-cookies.md)
