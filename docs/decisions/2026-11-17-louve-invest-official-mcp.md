# ADR: Louve Invest through its official MCP server and OAuth, not a sidecar

> Date: 2026-11-17
> Status: 🟡 Proposed (run once against a real account, see [Live verification](#live-verification))

## Context

Members hold SCPI shares bought through Louve Invest, a distributor, and type them by hand. The
other SCPI connectors (CORUM, Sofidy) are sidecars that log in with the member's password.

Louve publishes an official MCP server with standard OAuth 2.1: protected-resource metadata for
`https://app.louveinvest.com/mcp`, RFC 7591 dynamic registration of public clients, PKCE S256
only, RFC 9207 `iss` on the callback, rotating refresh tokens, revocation, and read-only tools
(`get_portfolio`, `get_scpi`, ...).

## Decision

1. **No sidecar.** The backend calls Louve over HTTPS with `java.net.http` on the fixed host
   `app.louveinvest.com`, redirects refused, bodies capped at 2 MB: `LouveOAuthClient` (OAuth)
   and `LouveMcpClient` (minimal JSON-RPC over Streamable HTTP, protocol `2025-06-18`). No
   container, Compose profile or Helm value.
2. **Least privilege.** Only `portfolio:read` is requested, whatever the registration echoes;
   only `get_portfolio` and `get_scpi` are called.
3. **The redirect URI comes from the configured origins.** It is the instance origin the
   browser is on, taken from the list the operator keeps in Administration > Security
   (`cors.allowed-origins`, or `ALLOWED_ORIGINS` before one is saved, the CORS policy of
   `DynamicCorsConfigurationSource`), plus `/sync/louve/callback`. An origin outside that list is
   refused with `ORIGIN_NOT_ALLOWED`; it must be HTTPS, or HTTP on loopback (`ORIGIN_NOT_SECURE`).
   Request headers such as `Host` or `X-Forwarded-*` never shape it.
4. **One registration per redirect URI, instance-wide, in `app_setting`.** Made on first use
   under `louve.client-id:<SHA-256 hex of the redirect URI>` (80 characters; `setting_key` is
   `VARCHAR(100)` and a redirect URI can be longer), value the client id. Of two concurrent
   registrations the first stored wins (`INSERT ... ON CONFLICT DO NOTHING`). `invalid_client`
   drops it with one compare-and-delete on key and client id, so a newer registration survives.
5. **A connection table like the other API connectors** (SimpleFIN, Wise, IBKR):
   `louve_connection` (V153), one row per member, the token set encrypted with `CryptoEncryption`,
   a status (`PENDING`, `CONNECTED`, `ERROR`, `REAUTH_REQUIRED`) and the last sync time. The
   pending OAuth attempt (state, encrypted verifier, redirect URI, client id, 10-minute TTL) sits
   on the same row, as Enable Banking keeps `oauth_state` on its requisition. The sync runs in
   the request, as Wise's does; a failure status is written in the service's own short
   transaction rather than a `*StatusWriter` bean, since network calls stay out of transactions.
6. **Rotation is committed before use, one refresh per member.** The refresh holds the row lock
   (bounded to five seconds, then a contention that writes nothing) and commits the rotated set
   before using the new access token; a concurrent run reads the rotated set. Callbacks and runs
   are fenced on `grant_generation`, which only a new consent increments.
7. **SCPI invariants in `LouvePortfolioMapper`**: envelope check, withdrawal price (the lower one
   on disagreement), dismembered lines not valued (`UNSUPPORTED_OWNERSHIP`), ISIN only with a
   valid check digit, distribution per share from the published rate. Accounts go through
   `ScpiFundReconciler`; as a distributor, Louve also adopts an account named
   `"<distributor> <fund>"`.

## Alternatives considered

- **A browser sidecar scraping the client space, like CORUM**: rejected. It needs the member's
  password and a Chromium image, and breaks silently on a page change, while an official
  read-only API exists.
- **A `sidecar_session` row and the queued sidecar cycle**: rejected. That machinery serves the
  sidecars' `{"detail": "<CODE>"}` contract; Louve has none, and the container-less connectors
  all use their own table.
- **The MCP Java SDK client** (already on the classpath through Spring AI): rejected. It reports
  HTTP failures only in exception messages, while the sync branches on the status (401 refresh,
  403 reconsent, 429, one retry on 502/503/504), and its 0.x API follows Spring AI's version.
- **Spring Security's OAuth2 client**: rejected. It has no RFC 7591 dynamic registration and keys
  authorized clients on the logged-in principal, not on a member's fenced, locked row.
- **A static client registered by hand**: rejected. Every self-hosted instance would need a
  manual step, and Louve supports dynamic registration.
- **A redirect URI derived from each request** (`Origin`, else scheme/host/port, with
  same-origin and allow-list checks), with a dedicated `louve_oauth_client` table: replaced in
  review. It depended on headers a proxy or a typo can change, so one instance could register
  twice, and it needed a table only for that.

## Reasoning

The official API removes the fragile parts of a portal connector (password, second factor, page
parsing) and is read-only by grant. The container-less connectors' layout keeps sidecar plumbing
for sidecars. Deriving the redirect URI from configuration ties it to what the operator declared.

## Trade-offs accepted

- A sync holds the request until Louve answers, as Wise's does.
- A refresh holds the connection row lock during one HTTPS call.
- An operator must list the address members use in Administration > Security; each listed origin
  members connect from gets its own registration on Louve.
- A fund Louve no longer lists is reported, not zeroed, until a live run shows how Louve lists a
  sold fund.

## Consequences

- V153: `louve_connection`, `scpi_position.louve_fund_code` (unique per member), `LOUVE` and
  `UNSUPPORTED_OWNERSHIP` in `scpi_provider_fund`. The client ids live in `app_setting`.
- `/api/louve/*`, the `louve` daily source, the Louve tab and the Add Account entry.
- Life insurance (Louve Infinity), transactions and payment plans stay out; see
  [louve-invest.md](../features/louve-invest.md).

## Live verification

One run against a real account on a local instance checked registration, consent (portfolio read
only), the `iss` check, a first sync that linked existing manual accounts, a second sync, the
absence of tokens and figures in the logs, and revocation on disconnect. Not yet observed through
Picsou: a refresh after the access token expires, and the ownership label of a dismembered line.
