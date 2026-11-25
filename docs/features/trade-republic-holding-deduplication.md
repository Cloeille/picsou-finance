# Fix: Trade Republic Holding Deduplication

> Last updated: 2026-05-18

## Problem

When syncing Trade Republic accounts, a `DataIntegrityViolationException` was thrown with the error:
```
duplicate key value violates unique constraint "account_holding_account_id_ticker_key"
```

This occurred because multiple ISIN codes (securities identifiers) could convert to the same Yahoo Finance ticker symbol via the `OpenFigiIsinConverter`. When syncing positions, the code would attempt to insert multiple `AccountHolding` records with the same `(account_id, ticker)` combination, violating the database's unique constraint.

### Example scenario
- Trade Republic account has two positions:
  - ISIN: `US0378691033` (Apple Inc. - US listing)
  - ISIN: `IE00B4L5Y983` (Apple Inc. - ISIN for European fund)
- Both convert to ticker: `AAPL`
- Sync tries to insert two holdings with `(account_id=57, ticker=AAPL)`
- Constraint violation occurs

## Solution

Modified `TradeRepublicSyncService.upsertAccount()` (and `BoursoSyncService` which has the same shape) to deduplicate holdings by ticker before persisting:

1. **Collect and deduplicate**: Loop through positions, converting each ISIN to a ticker
2. **Aggregate via VWAP**: When multiple positions map to the same ticker, combine quantities AND compute a quantity-weighted average buy-in
3. **Save deduplicated holdings**: Insert only one holding per ticker with the aggregated quantity and weighted average

### Implementation

- Shared helper `com.picsou.service.HoldingDedup` exposes the `HoldingAgg` record and a static `vwapMerge(prev, next)` method
- Used `Map.merge(..., HoldingDedup::vwapMerge)` so both sync services share a single canonical merge formula
- Positions are deduplicated **in-memory before database writes**, avoiding constraint violations
- VWAP formula: `weightedAvg = (q1·a1 + q2·a2) / (q1 + q2)` at scale 8, `RoundingMode.HALF_UP` (matches `HoldingComputeService`)

### Key files

- `HoldingDedup.java` — shared VWAP merge helper
- `backend-rs/src/connectors/trade_republic/sync.rs:346-378` — TR upsertAccount dedup loop
- `backend-rs/src/connectors/bourso/sync.rs:245-281` — Bourso upsertAccount dedup loop

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Deduplicate in-memory before saving | Avoids constraint violations and keeps the database clean | Update existing holdings (more complex, slower) |
| Use `Map.merge()` for aggregation | Concise, handles both first occurrence and merges in one pass | Manual `if-put-get` logic (more verbose) |
| VWAP-weighted average buy-in on duplicates | Mathematically correct cost-basis; preserves the gain/loss invariant `pnl = value − cost` | "Keep first averageBuyIn" — non-deterministic (depends on HashMap iteration order) and produces wrong gain/loss percentages |
| Shared `HoldingDedup` helper for TR & Bourso | One canonical formula = one place to audit/test; prevents drift between providers | Per-service private lambdas (regressed twice already) |

## Gotchas / Pitfalls

- **ISIN → Ticker conversion is not 1:1**: Multiple ISINs can map to the same ticker (e.g., different listings of the same security)
- **`HashMap` iteration order is not deterministic**: A merge lambda that picks `prev` (or `next`) for a field implicitly depends on insertion-order hashing, which can flip between syncs and JVMs. The VWAP merge is symmetric and therefore order-independent — see `HoldingDedupTest#vwapMerge_isOrderIndependent`.
- **Null averages treated as zero**: When one of the merged aggregates has a null `averageBuyIn`, the VWAP uses zero for that side. Acceptable because callers populate it from the provider's reported buy-in; null typically means "unknown / cash-equivalent".
- **Edge case**: WebSocket sync treats positions as authoritative. If TR returns an
  empty position list for a portfolio, existing holdings for that account are deleted
  so a full sale is reflected immediately. CSV imports still preserve holdings because
  they contain balances only, not position details.

## Tests

- `HoldingDedupTest` — VWAP math, null handling, order independence, zero-quantity guard, name/currentPrice fallback
- `TradeRepublicSyncServiceTest#sync_mergesDuplicateTickersWithVwap` — integration wiring: two distinct ISINs → same ticker → saved `AccountHolding.averageBuyIn` is the VWAP, not whichever position appeared first
- `TradeRepublicSyncServiceTest#sync_deletesOldHoldingsWhenPortfolioReturnsEmpty` — empty authoritative TR portfolio clears stale holdings
- No regression in existing sync flow when the backend suite is run.

## Related

- `OpenFigiIsinConverter` — responsible for ISIN → Yahoo ticker conversion
- `AccountHolding` — database entity with unique constraint on `(account_id, ticker)`
- Sync flow: `TradeRepublicSyncService.sync()` → `upsertAccount()` → `holdingRepository.save()`
