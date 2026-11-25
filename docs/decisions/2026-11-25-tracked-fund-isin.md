# ADR: How Picsou names a fund the provider does not identify

> Date: 2026-11-25
> Status: ✅ Active

## Context

Some providers report an account that holds a fund as one cash balance. A Wise "Stocks" jar
synced through Enable Banking arrives as a `SAVINGS` account whose balance is the jar's market
value, with no holding lines and no field saying it is invested or in which fund (#685).

Picsou counted that money as safety net in the [wealth pyramid](../features/wealth-pyramid.md)
and left it out of [diversification](../features/portfolio-diversification.md). Net worth was
right; the analysis of it was wrong.

## Decision

The member declares the ISIN of the single fund the account holds, in a nullable
`account.tracked_isin` column. Only the analysis pages read it: the pyramid, the projection built
on it, and diversification count the account's live value as that fund. The tier is `EQUITY`
unless the member's `holding_classification.wealth_tier` override says otherwise.

The line is keyed on the market ticker linked to the ISIN in `security_profile`, not on the ISIN:
held lines and profiles are keyed on tickers, and a bare ISIN never gets a profile. Resolution
uses the OpenFIGI resolver the syncs use, in a background task after the save, retried weekly.
The ISIN is the key until a ticker is linked.

The tracked fund applies only to an account that is not a liability, a peer loan or a pocket, and
that has no holding rows. Real lines win.

## Alternatives considered

### A synthetic holding row

Write an `account_holding` row with quantity = balance / price, rewritten on each sync.

- **Pros**: every holdings-based page (positions, diversification, pyramid) would see the fund
  with no new code path.
- **Cons**: the account's value would become quantity × live price and drift from the provider's
  balance between syncs. It would fight the investment-type holdings recompute and live-price
  valuation, and change net worth, which must not move.

### Retype the account to `COMPTE_TITRES` with a manual holding

- **Pros**: no schema change; the account would land in the equity tier by its type.
- **Cons**: an investment type makes imported transactions budget-neutral and recomputes holdings
  from transactions. That breaks a cash-transfer account, and the manual quantity goes stale on
  every deposit.

### Detect the fund automatically from the Wise API

Read `investmentState == "INVESTED"` and the jar's fund from Wise's own API.

- **Pros**: no member input.
- **Cons**: the member syncs the jar through Enable Banking, whose payload carries no such
  signal. It would cover one provider only. A Wise jars auto-fill can come later and write the
  same column.

## Reasoning

The balance is the only figure the provider guarantees, so it stays the source of truth. A
declared ISIN only reclassifies that value inside two pages; dashboard, goals, history, budget,
syncs and the balance do not change. Keying on the linked ticker makes a tracked line merge with
a held line of the same listing and reuse its profile and the member's overrides.

## Trade-offs accepted

- One ISIN per account: no multi-fund or weighted mix.
- No quantity, cost basis, P&L or live-price valuation for the tracked fund.
- A delay after saving: the fund is unclassified in diversification until the background
  resolution links a ticker and refreshes its profile.
- When one ISIN links to several tickers, the smallest one wins; collation can differ between
  PostgreSQL and SQLite for unusual tickers.
- Export and import formats do not carry the ISIN yet.

## Consequences

- New column `account.tracked_isin` (PostgreSQL V156, SQLite V3) and the `trackedIsin` /
  `trackedTicker` API fields.
- New module `backend-rs/src/accounts/tracked_fund.rs`, read by `pyramid.rs`, `projection.rs` and
  `diversification.rs`.
- The weekly security-profile pass and the on-demand refresh also cover tracked keys, and the
  weekly pass retries unresolved ISINs. `holdings::distinct_tickers` (prices, logos) is unchanged.
- Feature note: [tracked-fund.md](../features/tracked-fund.md).
