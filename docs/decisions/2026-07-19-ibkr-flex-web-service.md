# ADR: Interactive Brokers via the Flex Web Service (read-only, EOD)

> Date: 2026-07-19
> Status: ✅ Active

## Context

Interactive Brokers was the main missing brokerage connector (Picsou already had Trade
Republic and Finary). IBKR offers three programmatic access paths, with very different
operational footprints for a **self-hosted, unattended** app that only needs to refresh a
portfolio once a day. We had to pick one before building the connector.

## Decision

Integrate IBKR through the **Flex Web Service**, pulling an "Open Positions" Flex Query
once a day. The user creates the query and a read-only token in Client Portal and pastes
the token + query id into Picsou. Sync is the standard two-step flow (`SendRequest` →
`GetStatement`), parsed into `AccountHolding` rows like the other brokers.

## Alternatives considered

### Flex Web Service (chosen)

- **Pros**: read-only token (6h–1y), nothing to run alongside the app, HTTP + XML, data is
  end-of-day which matches Picsou's daily snapshots, maps almost 1:1 onto the existing
  Trade Republic broker→holdings pattern.
- **Cons**: not real-time (EOD only); XML rather than JSON; two-step async flow needs
  polling.

### Client Portal API (Web API)

- **Pros**: near real-time, REST/JSON, richer (live positions, orders).
- **Cons**: requires running the **Client Portal Gateway** (a Java process) next to the
  app, with a session that expires roughly daily and needs interactive 2FA re-auth — hostile
  to unattended self-hosting.

### TWS API

- **Pros**: full-featured, real-time.
- **Cons**: requires **TWS or IB Gateway** (a desktop app) kept open and logged in, socket
  protocol, automatic daily disconnect. A non-starter for a headless container.

## Reasoning

Picsou already snapshots balances once a day and recomputes live valuations from tickers, so
intraday IBKR data adds nothing here while both real-time options impose a long-lived,
2FA-refreshed gateway/desktop process — exactly the kind of babysitting a self-hosted
dashboard should avoid. The Flex Web Service needs only a stored token and an HTTP call, and
slots into the existing `Port`/adapter + `SchedulerService` machinery with no new moving
parts. XML is handled with the JDK's own DOM parser, so no dependency is added.

## Trade-offs accepted

- **End-of-day only.** No intraday IBKR position changes; acceptable given daily snapshots
  and live ticker-based valuation.
- **Manual query setup.** The user must build the Flex Query and generate a token once — more
  onboarding friction than an OAuth flow, but it is the only read-only, gateway-free option.
- **Base-currency caveat.** IBKR cost basis is converted to the account base currency via
  `fxRateToBase`; the stored `averageBuyIn` (invested/PnL) is exact only when the IBKR base
  currency is EUR. Net worth is unaffected — it is recomputed live in EUR from tickers.

## Consequences

- New `ibkr_connection` table (encrypted token + query id), `IbkrFlexPort` +
  `IbkrFlexClient`, `IbkrSyncService`, `IbkrController` (`/api/ibkr/*`), and a daily
  auto-sync hook in `SchedulerService`.
- Reuses `OpenFigiIsinConverter`, `HoldingDedup`, `CryptoEncryption`, `AccountService`.
- No new Maven dependency (JDK `HttpClient` + DOM parser).
- Frontend connection card, i18n keys and the setup/integrations registry entry are a
  follow-up; the backend endpoints are usable meanwhile.
