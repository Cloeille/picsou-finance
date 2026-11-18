# ADR: Louve Invest through its official MCP server and OAuth, not a sidecar

> Date: 2026-11-17
> Status: 🟡 Proposed (run against one real Louve account on 2026-11-17, see [Live verification](#live-verification); a refresh after one hour not yet observed through Picsou)

## Context

Members hold SCPI shares bought through Louve Invest, a distributor, and type them by hand
("Louve Invest - <fund>"). Every other SCPI connector (CORUM, Sofidy) is a sidecar that logs in
with the member's password and reads the client space's pages.

Louve publishes an official MCP server with standard OAuth 2.1 (checked live on 2026-11-17):
protected-resource metadata naming `https://app.louveinvest.com/mcp`, an authorization server
with RFC 7591 dynamic registration, public clients (`token_endpoint_auth_method: none`), PKCE
S256 only, RFC 9207 `iss` on the callback, rotating refresh tokens, revocation, and read-only
tools (`get_portfolio`, `get_scpi`, ...). Scopes are `profile:read portfolio:read
documents:read simulations:read simulations:write external-transactions:write`.

## Decision

1. **No sidecar.** The backend speaks to Louve directly over HTTPS with `java.net.http`, on the
   fixed host `app.louveinvest.com`, redirects refused, bodies capped at 2 MB:
   `LouveOAuthClient` (OAuth) and `LouveMcpClient` (a minimal JSON-RPC client, Streamable HTTP,
   protocol `2025-06-18`). No container, Compose profile or Helm value is added.
2. **Least privilege.** Only `portfolio:read` is requested, whatever the registration echoes.
   Only `get_portfolio` and `get_scpi` are called.
3. **One registration per redirect URI, instance-wide** (`louve_oauth_client`, V153), made on
   first use. The redirect URI is the browser's origin plus `/sync/louve/callback`, accepted only
   when same-origin, matching the request, or on the setup wizard's CORS allow-list, and HTTPS
   except on loopback. A changed origin registers again; `invalid_client` (on the code exchange
   or on a refresh) drops the row with one compare-and-delete statement on redirect URI and
   client id.
4. **A connection table of its own, like the other API connectors** (see
   [Precedent](#precedent-container-less-api-connectors)): `louve_connection` (V153), one row per
   member, the token set encrypted with `CryptoEncryption` in a TEXT column, a status
   (`PENDING`, `CONNECTED`, `ERROR`, `REAUTH_REQUIRED`) and the last sync time. The pending OAuth
   attempt sits on the same row (state nonce, encrypted verifier, redirect URI, client id, start
   time, 10 minutes), as Enable Banking keeps `oauth_state` on its requisition; a stored token set
   survives a pending reconnect. The sync runs in the request, as Wise's and SimpleFIN's do.
5. **Rotation is committed before use, one refresh per member.** The refresh runs in a
   transaction holding the connection row lock and commits the rotated set before the new access
   token is read with. Another run (a click during the 08:00 sync, a second instance) waits on
   the lock (at most five seconds, then a temporary contention that writes nothing) and reads
   the rotated set instead of refreshing the dead one. Callbacks and runs are fenced on the row's
   `grant_generation`, which only a new consent increments, so neither a late callback nor a run
   on the old grant writes over a newer connection.
6. **The SCPI invariants are applied in `LouvePortfolioMapper`**: envelope check, withdrawal
   price (the lower one when the fund sheet and the line disagree), dismembered lines not valued
   (`UNSUPPORTED_OWNERSHIP`), ISIN only when its check digit holds, distribution per share from
   the published rate and the subscription price. Accounts go through `ScpiFundReconciler` like
   CORUM and Sofidy, with one addition for distributors: an account named
   `"<distributor> <fund>"` is adopted too (exact after normalization, LOUVE only).

## Alternatives considered

### A browser sidecar scraping the client space, like CORUM

- **Pros**: the pattern every SCPI connector follows.
- **Cons**: the member's password stored or typed into Picsou, a Chromium image to build and
  ship, a page format that breaks silently, and a scraper against a provider that offers an
  official read-only API.

### A `sidecar_session` row and the queued sidecar cycle

- **Pros**: the queue, fencing, boot recovery and contract suites of `SidecarSyncTemplate` for free.
- **Cons**: `sidecar_session`, `SidecarBank` and their contract suites exist for connectors that
  talk to a sidecar's `{"detail": "<CODE>"}` contract; Louve has none, so it needed exclusions in
  those suites and a `SidecarBank` entry without a service. The repository's container-less
  connectors all take the other road.

### The MCP Java SDK client (`McpSyncClient` with `HttpClientStreamableHttpTransport`)

`spring-ai-starter-mcp-server-webmvc` already brings the SDK in (`io.modelcontextprotocol.sdk:mcp-core`).

- **Pros**: no JSON-RPC envelope or SSE parsing of our own.
- **Cons**: the transport reports an HTTP failure as an exception whose only trace of the
  status is its message (`Failed to send message: ...`), and the sync depends on that status:
  a 401 triggers the one token refresh, a 403 asks for a new consent, a 429 is `RATE_LIMITED`,
  a 502/503/504 is retried once. Reading those from exception messages would break silently on
  an SDK update, and the SDK's version follows Spring AI's (a 0.x API), not this connector.
  Two read-only tool calls do not need the SDK's sessions, capabilities or notifications, so
  `LouveMcpClient` stays a small, fully tested client.

### Spring Security's OAuth2 client (`spring-boot-starter-oauth2-client`)

Already a dependency, for SSO login.

- **Pros**: PKCE, the code exchange and the refresh grant without code of our own.
- **Cons**: its registrations come from a `ClientRegistrationRepository` known at startup, and
  it does not perform RFC 7591 dynamic registration. Its authorized clients are keyed on the
  logged-in principal, not on a member's encrypted row fenced by `grant_generation` and a row
  lock. The connector would have to replace both, which leaves the library little to do. Enable
  Banking, the other OAuth-style connector, does not use it either.

## Precedent: container-less API connectors

The connectors that call a provider's API from the backend, with no sidecar, share one layout:

| | SimpleFIN | Wise Jars | IBKR Flex | Enable Banking |
|---|---|---|---|---|
| Table | `simplefin_connection` (V113) | `wise_connection` (V131) | `ibkr_connection` (V59) | `requisition` (V3; `oauth_state` V51) |
| Row | one per member, unique `member_id` | same | same | one per bank connection |
| Secret | access URL, AES-GCM `TEXT` | API token, AES-GCM `TEXT` | token + query id, AES-GCM | the provider's session id |
| Status | `status` VARCHAR(20) + `last_synced_at` | same, plus `REAUTH_REQUIRED` | same | requisition status |
| Service | `connect` / `sync` / `resyncReporting` / `deleteConnection`; sync in the request; a `*StatusWriter` records a failure in its own transaction | same | same | `initiate` / `complete` (state nonce, single use) / resync |
| Controller | `/connect`, `/status`, `/sync`, `DELETE /connection`; a per-IP bucket | same | same | `/api/sync/initiate`, `POST /api/sync/complete` |
| Daily sync | `MemberSyncService` source; `SourceSyncResult.fromSyncException` maps `SESSION_EXPIRED` to `NEEDS_REAUTH` | same | same, from Flex codes 1012/1015 | `enable-banking` + retry |
| Boot recovery, executor, instance status | none: nothing is queued, no sidecar to probe | none | none | none |
| Deletion | `AccountConnectionService` removes the connection with its last account | same | same | same |
| Frontend | own hooks (`useXStatus`, `useConnectX`, `useSyncX`, `useDisconnectX`) and panel | same | same | `BankSyncTab`, callback route |

Louve follows that layout: `louve_connection`, `LouveSyncService` (`startConnection` /
`completeConnection` in place of `connect`, then `sync`, `resyncReporting`, `deleteConnection`),
`/api/louve/{auth/start, auth/complete, status, sync}` and `DELETE /api/louve/connection`, the
`louve` daily source, `AccountConnectionService`, and Wise-style hooks. What stays shared with
CORUM and Sofidy is SCPI-specific only: `ScpiFundReconciler` (provider `LOUVE`),
`scpi_position.louve_fund_code`, `applySyncedPosition` and `scpi_provider_fund`.

Two departures, both forced by OAuth: the failure status is written by the service's own short
transaction rather than a `*StatusWriter` bean (the service keeps network calls out of
transactions instead of running in one), and the refresh holds the row lock (a pasted token never
rotates, Louve's refresh token does).

### Statically configured OAuth client (an operator registers Picsou by hand)

- **Pros**: no registration table.
- **Cons**: every self-hosted instance would need a manual step; Louve explicitly supports
  dynamic registration for public clients.

## Reasoning

The official API removes every fragile part of a portal connector (password, second factor,
page parsing) and is read-only by grant. Following the container-less connectors' layout keeps
the sidecar plumbing for sidecars, and gives a reader one shape to expect for an API connector.

## Trade-offs accepted

- A sync holds the request until Louve answers (a few seconds), as Wise's does; there is no
  queued job to poll.
- A refresh holds the connection row lock during one HTTPS call to Louve.
- The redirect URI depends on the browser's origin. An instance reached under two origins has two
  registrations on Louve.
- A fund Louve no longer lists is only reported, not zeroed, until a live run shows how Louve
  lists a sold fund.

## Consequences

- V153: `louve_connection`, `scpi_position.louve_fund_code` with its
  per-member unique index, `LOUVE` and `UNSUPPORTED_OWNERSHIP` in `scpi_provider_fund`,
  `louve_oauth_client`.
- `/api/louve/*`, the `louve` source of the daily sync, the Louve tab and the Add Account entry.
- Life insurance (Louve Infinity), transactions and payment plans stay out; see
  [louve-invest.md](../features/louve-invest.md).

## Live verification

Run on 2026-11-17, a local instance against a real Louve account:

- Dynamic registration worked; the consent page asked only for "Lire votre portefeuille".
- The callback carried `iss=https://app.louveinvest.com`, accepted.
- The first sync `LINKED` the member's two manual accounts named "Louve Invest - <fund>",
  created nothing, and the envelope check passed. Share counts and withdrawal values matched the
  Louve dashboard; a real ISIN was kept and an internal code was not stored as one; the
  distribution fields were filled (`MONTHLY`). The second sync reported `UPDATED`.
- No token, code or amount appeared in the backend logs.
- Disconnect removed the "Picsou" grant on Louve's side and kept the accounts.
- Found and fixed: the callback URL (with its code) stayed in the address bar after a successful
  connection.

Still to observe through Picsou: a refresh after the one-hour access token expiry (the stored
refresh token must change and the next run use the new one; rotation itself was verified by hand
against the token endpoint), and the ownership label Louve uses for a dismembered line.
