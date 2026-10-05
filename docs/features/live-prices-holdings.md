# Feature: Live Prices in Holdings

> Last updated: 2026-08-10

## Context

Holdings (PEA, Compte-Titres, Crypto) display prices that are only updated during a full sync. When a user navigates to an account detail page, the displayed prices may be stale — sometimes hours old. The backend already exposes `GET /api/prices?tickers=...` via `PriceController` which refreshes the cache and returns live EUR prices. This feature integrates those live prices into the holdings table on page navigation, and propagates live portfolio values (with PnL) to Goals progress, Dashboard distribution, and the AccountDetail balance card.

## How it works

The frontend fetches holdings and live prices in parallel, then merges them client-side. The backend also computes live values server-side for Goals and Dashboard.

### Frontend data flow (holdings table)

```
User navigates to account detail
        |
        v
useHoldingsWithLivePrices(id)
        |
        +-- GET /api/accounts/{id}/holdings  (DB prices, may be stale)
        |
        +-- GET /api/prices?tickers=BTC,ETH,IWDA.AS  (live prices from providers)
        |
        v
Merge: override currentPrice with live price
Recalculate: currentValueEur, pnlEur, pnlPercent
        |
        v
HoldingsTable renders with live prices
```

If the prices API fails, the hook keeps the backend response. For Bourse Direct
holdings that response can include the last reconciled broker valuation in EUR,
even when Yahoo cannot resolve the native quote.

The two failure modes are deliberately not symmetric. `usePortfolio` fetches
holdings per account in a `Promise.all`, and a rejected holdings call fails the
whole query: `PortfolioView` and `HoldingsCard` render `ErrorState` with
`formatApiError` and a retry. Swallowing it per account (the previous
`catch { return [] }`) silently dropped that account from the portfolio total,
allocation and P&L, with a "no holdings" empty state as the only hint.

### Live-price recompute formula (single source of truth)

Both `usePortfolio` (portfolio view across all accounts) and `useHoldingsWithLivePrices` (single account holdings table) share a single helper, `recomputeWithLivePrice`, in `frontend/src/features/accounts/hooks.ts`:

```
costBasisEur    = backend costBasisEur         (null if the basis is unknown)
currentValueEur = quantity * livePrice
pnlEur          = currentValueEur - costBasisEur
pnlPercent      = pnlEur / costBasisEur * 100  (null if costBasisEur == 0)
```

The backend cost basis is retained because a broker can provide an authoritative
EUR P&L for a foreign-currency position. Reconstructing it from a native price
would introduce FX errors. Value and P&L are recomputed from the same
`livePrice` snapshot, so the header badge (`pnlPercent`), Gain/Loss display
(`pnlEur`), and total value (`currentValueEur`) cannot drift out of sync.

The previous implementation in `usePortfolio` updated only `valueEur`/`pnlEur` via delta-add (`l.pnlEur + (newVal - oldVal)`) and left `pnlPercent` at the backend's stored ratio, producing badge-vs-display incoherence on every live-price refresh.

### Frontend data flow (AccountDetail balance)

The balance card renders `account.currentBalanceEur`. The backend owns that
aggregate so cash, live EUR prices and the all-or-nothing Bourse Direct fallback
are applied once rather than reconstructed differently in each frontend view:

```
displayBalance = account.currentBalanceEur
```

### Backend data flow (Goals & Dashboard)

The backend computes live balance via `AccountService.liveBalanceEur()`:

```
AccountService.liveBalanceEur(account)
        |
        +-- Load holdings for account
        |
        +-- If no holdings: return stored balance converted to EUR
        |
        +-- If holdings: for each holding:
        |       +-- priceService.getPriceEur(ticker)  (live cache, 15-min TTL — EUR-denominated)
        |       +-- if no live price: never multiply an unqualified native quote as EUR
        |       +-- accumulate qty * livePrice
        |
        v
Return live portfolio value in EUR
```

For Bourse Direct, if any position lacks a live EUR price, the method returns
the last complete broker account total instead of a partial
`cash + priced symbols` sum. `HoldingResponse` follows the same rule per
position: Yahoo EUR value when available, otherwise the broker's reconciled EUR
value. `currentPrice` remains tagged with its explicit `quoteCurrency`.

Used by:
- `GoalService.toProgressResponse()` — sums `liveBalanceEur()` across linked accounts for `currentTotal`
- `DashboardService.buildDistribution()` — uses live values from pre-loaded `holdingsByAccount` map for distribution percentages
- `DashboardService.getDashboard()` — already computed live total/invested inline (pre-dates `liveBalanceEur()`)

### Historical net-worth chart (`HistoryService.buildHistory`)

For each past date, both `total` and `invested` are read from `balance_snapshot` and forward-filled per account from the latest row on or before that date. Loans contribute their negative balance to `total` and zero to `invested`. Today's point is replaced with live values from `liveBalanceEur()` and `calculateInvestedAmount()` so intraday changes are visible immediately. The `invested_amount` column (added in V18, `NOT NULL`) is written by both the daily scheduler and every sync path via `AccountService.upsertSnapshot`.

### Key files

- `frontend/src/features/accounts/api.ts` — `prices(tickers)` API function
- `frontend/src/features/accounts/hooks.ts` — `useHoldingsWithLivePrices(id)` hook
- `frontend/src/pages/accounts/AccountDetailPage.tsx` — uses the hook; `displayBalance` for holding accounts
- `backend/src/main/java/com/picsou/service/AccountService.java` — `liveBalanceEur()` method
- `backend/src/main/java/com/picsou/service/GoalService.java` — uses `liveBalanceEur()` in `toProgressResponse()`
- `backend/src/main/java/com/picsou/service/DashboardService.java` — `buildDistribution()` uses live values from `holdingsByAccount`

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two separate API calls (holdings + prices) | Prices API is reusable and navigation can refresh a cached backend value | A dedicated enriched-holdings endpoint |
| Client-side merge | Refreshes the existing currency-explicit `HoldingResponse` without another domain DTO | New dedicated endpoint returning enriched holdings |
| Graceful degradation on price failure | Better UX than showing errors for non-critical price data | Throwing error / blocking page render |
| Holdings failure propagates instead | A missing *price* leaves the position visible with a stale value; a missing *holdings* response removes the position from every total | Returning `[]` for the failed account |

## Gotchas / Pitfalls

- **Prices are not persisted**: Live prices are only used for display. The DB `current_price` in `account_holding` is not updated — that still happens during sync.
- **`useAccountHoldings` still exists**: The old hook is kept for the `usePortfolio` hook which fetches holdings for all accounts (portfolio view). Switching it to live prices would trigger many price API calls at once.
- **No polling**: Prices refresh only on navigation (TanStack Query stale time of 2 min). There is no auto-refresh or polling interval.
- **Yahoo Finance is unofficial**: See [price-service.md](./price-service.md) gotchas. A stored quote is displayed only with its explicit `quoteCurrency`; it is never multiplied as EUR when that currency is unknown.
- **Bourse Direct and Trade Republic write an EUR fallback**: `provider_value_eur` is accepted only from a broker that quotes in EUR — Bourse Direct as part of a fully reconciled snapshot, Trade Republic because its portfolio stream is EUR-denominated by construction (the same assumption already behind `TrAccountData.balanceEur`). Connectors that expose native-currency values without an explicit `quoteCurrency` still return `null`.
- **`liveBalanceEur()` falls back per holding**: when Yahoo has no live price, the holding is valued at `provider_value_eur` before being dropped. Dropping it is the last resort, and it is what makes the dashboard P&L wrong (see the asymmetry gotcha below).
- **Value and invested must cover the same holdings**: `liveBalanceEur` may drop an unpriced holding, but `DashboardService` and `AccountService.calculateInvestedAmount` still count its full cost basis. Any holding dropped from the value side while its cost basis remains understates P&L by exactly that cost basis (GH issue #76). The `provider_value_eur` fallback closes this for Bourse Direct and Trade Republic; a connector that populates neither can still hit it.
- **`AccountService.toResponse()` computes a live balance**: for Bourse Direct it falls back to the complete stored broker total if even one Yahoo price is unavailable.
- **`liveBalanceEur()` triggers price lookups**: Each call fetches holdings then queries `PriceService` per ticker. Don't call in tight loops. The Dashboard pre-loads holdings into a map to avoid N+1; Goals calls it per-account in the goal's account list (typically small).
- **The account detail page does not re-sum holdings**: it displays the backend's `currentBalanceEur`, avoiding disagreement with cash and broker fallback rules.

## Tests

- Manual: navigate to a PEA/CT/Crypto account, verify `/api/prices` call in network tab and live prices in table
- Manual: navigate to a checking/savings account, verify no `/api/prices` call

## Links

- Related feature: [Price Service](./price-service.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
