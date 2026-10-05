# Feature: 24H Intraday Net Worth Chart

> Last updated: 2026-09-05

## Context

The NetWorthChart only had daily data points (from `BalanceSnapshot` taken once at 08:05). The "1D" range showed at most 2 points and displayed month abbreviations on the X-axis — useless for a 1-day window. This feature replaces "1D" with "24H" and fetches hourly price data on demand from external providers to reconstruct intraday portfolio values.

## How it works

When the user selects the "24H" range, the frontend calls `GET /api/history/net-worth/intraday?accountIds=...`. The backend fetches hourly prices from CoinGecko (crypto) and Yahoo Finance (stocks/ETFs), then computes portfolio value at each hour for the last 24 hours.

### Backend data flow

```
GET /api/history/net-worth/intraday?accountIds=1,2,3
        |
        v
HistoryService.buildIntradayHistory()
        |
        +-- For each account, load holdings
        |
        +-- Non-investment accounts (bank/savings):
        |     Use today's BalanceSnapshot (constant throughout the day)
        |
        +-- Investment accounts (PEA, CT, Crypto):
        |     For each holding with a ticker:
        |       PriceService.getIntradayPricesEur(ticker, from, to)
        |         -> CoinGecko.getIntradayPricesEur() for crypto
        |         -> YahooFinance.getIntradayPricesEur() for stocks
        |       Portfolio value at hour H = qty × price_at_H (forward-filled) + cash pocket
        |       Invested = qty × average buy-in + cash pocket (same perimeter as the value)
        |
        v
List<NetWorthIntradayPoint> — ~24 hourly points
```

### Frontend data flow

```
User selects "24H"
        |
        v
DashboardPage renders NetWorthChart with intraday prop
        |
        +-- useNetWorthIntraday(accountIds, enabled) fetches hourly data
        |
        +-- NetWorthChart:
              X-axis: "HH:mm" format
              Dots on each data point
              Tooltip shows hour + date
```

### Key files

**Backend:**
- `backend/src/main/java/com/picsou/adapter/CoinGeckoPriceProvider.java` — `getIntradayPricesEur()`: fetches hourly crypto prices via `market_chart/range`
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — `getIntradayPricesEur()`: fetches hourly stock prices via `interval=1h`
- `backend/src/main/java/com/picsou/service/PriceService.java` — `getIntradayPricesEur()`: routes to CoinGecko or Yahoo based on ticker
- `backend/src/main/java/com/picsou/service/HistoryService.java` — `buildIntradayHistory()`: assembles hourly net worth points
- `backend/src/main/java/com/picsou/controller/HistoryController.java` — `GET /api/history/net-worth/intraday`
- `backend/src/main/java/com/picsou/dto/DashboardResponse.java` — `NetWorthIntradayPoint(timestamp, total, invested)`

**Frontend:**
- `frontend/src/components/shared/TimeRangeSelector.tsx` — `1D` replaced with `24H`
- `frontend/src/components/shared/NetWorthChart.tsx` — dynamic X-axis formatting, dots for short ranges, intraday data source
- `frontend/src/features/dashboard/api.ts` — `getIntraday()` API call
- `frontend/src/features/dashboard/hooks.ts` — `useNetWorthIntraday()` hook
- `frontend/src/pages/dashboard/DashboardPage.tsx` — wires intraday fetch when range is 24H

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| On-demand price fetching (no storage) | No new tables, no scheduled jobs; intraday data is ephemeral and only needed when the user views it | Hourly `IntradaySnapshot` table + scheduled job (more infrastructure for a niche view) |
| Reuse existing provider endpoints | CoinGecko `market_chart/range` already returns hourly for < 90d ranges; Yahoo supports `interval=1h` | New dedicated intraday endpoints or scraping |
| Forward-fill missing hourly prices | Markets are closed at night; forward-fill from last known price avoids gaps | Leaving nulls (breaks the chart) or interpolating (invents fake data) |
| 24H replaces 1D in TimeRange type | Cleaner than having both; "1D" was misleading (only 2 daily points) | Keeping both 24H and 1D (confusing UX) |
| Dynamic X-axis formatting per range | Each range needs appropriate granularity (hours vs days vs months) | Static formatting (was the bug) |

## Gotchas / Pitfalls

- **No new scheduled jobs or DB tables**: Intraday data is fetched live from external APIs on each request. This means the 24H chart triggers external API calls every time it's loaded.
- **External API rate limits**: CoinGecko free tier has rate limits. If a user has many tickers, the 24H view could trigger many API calls. The `PriceService` 15-min cache helps for repeated views.
- **Forward-fill behavior**: If a ticker has no price at a given hour (e.g., stock market closed), `floorEntry()` uses the last known price. Crypto prices are available 24/7; stock prices stop after market close.
- **Bank account balances are constant intraday**: Only the daily `BalanceSnapshot` is used for non-investment accounts. Real-time bank balance changes won't appear until the next sync.
- **Loans are negated**: Same logic as the daily history — loan balances are subtracted from total net worth.
- **The cash pocket is on both sides**: for an investment account with holdings, the cash held inside the envelope (`Account.cashBalance`, set by the Bourse Direct, BoursoBank and DEGIRO connectors) is added to the hourly value *and* to the invested amount, as `AccountService.valuation()` does for the daily chart's today point. It is worth what it cost, so it never moves the gain. Leaving it out of both (the case until 2026-09-05) kept the gain right but made the 24H total lower than the daily one by the whole pocket, so switching ranges read as a drop that never happened.
- **`intraday` prop is optional on NetWorthChart**: Other pages (AccountDetail, GoalDetail) use NetWorthChart without intraday data. When `intraday` is not provided, the 24H range still works but shows empty data.
- **Yahoo timezone**: Yahoo Finance timestamps are parsed as `Europe/Paris` (not UTC) since the app targets French users. CoinGecko uses UTC.

## Tests

- Manual: select "24H" → chart shows ~24 hourly points with "HH:mm" on X-axis
- Manual: select "7D" → chart shows "dd MMM" labels with dots on data points
- Manual: select "1M"+ → chart shows month abbreviations (unchanged behavior)
- `GoalServiceTest` — existing backend test still passes
- `HistoryServiceTest`: `buildIntradayHistory_loanNegated_cashConstant` (loans negated, bank balance constant), `buildIntradayHistory_cashPocketCountsOnBothSides` (cash pocket in both the value and the invested amount)

## Links

- Related feature: [Price Service](./price-service.md)
- Related feature: [Live Prices in Holdings](./live-prices-holdings.md)
- Related feature: [Dashboard — Time Range Isolation](./dashboard-time-range-isolation.md)
