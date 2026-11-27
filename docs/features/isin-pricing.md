# Pricing by ISIN

## Context

A ticker names one listing of an instrument. The ISIN names the instrument itself. Deriving a
ticker from an ISIN and pricing that ticker can land on the wrong listing: `LU1829219390`
(Amundi Euro Stoxx Banks, 4.41 EUR) was priced through the US grey-market line `MULYF`, at
376 EUR (#683). Epic #698 moves every holding with an ISIN to prices read by ISIN.

This note covers the price sources (#700, #704), the valuation that reads them (#701) and the
history recorded by ISIN (#702).

## How it works

`IsinPriceService` asks each `IsinPriceSource` in order, and the first answer wins:

1. **justETF** (`JustEtfIsinPriceSource`). `GET /api/etfs/{isin}/quote?currency=EUR` for the
   quote and `/performance-chart` for daily closes, both by ISIN, in EUR. It covers listed shares
   and ETFs. An unlisted fund answers `latestQuote: null`, which means no price.
2. **Yahoo by ISIN** (`YahooIsinPriceSource`). Yahoo's own search maps the ISIN to the symbols
   Yahoo lists for it (`/v1/finance/search?q=<ISIN>`), and the price is read from the ISIN's
   **reference listing**: the first match, in Yahoo's order, that trades in the currency of the
   ISIN's market. A `US…` ISIN means USD. A euro-area ISIN, `LU` and `IE` included, means EUR.
   `GB` means GBP, with pence (`GBp`) accepted. A listing in another currency is never taken. An
   ISIN whose market currency is unknown here gets no Yahoo price. At most 3 matches are probed,
   and the symbol found is remembered for the next calls.

3. **Boursorama** (`BoursoramaIsinPriceSource`, #704), for unlisted funds (OPCVM with one NAV a
   day) that the first two do not carry. `/recherche/?query=<ISIN>` redirects to
   `/cours/<symbol>/` only when Boursorama knows the ISIN. The search row of that symbol
   (`/recherche/ajax`) must show its price in euros. The daily closes come from
   `GetTicksEOD?symbol=<symbol>&length=<days>`, asked as an XHR. The quote is the last close of
   the 30-day window.

None of the sources goes through OpenFIGI or a ticker held on the holding.

### Valuation (#701)

A line with an ISIN (`account_holding.isin`, #699) on a non-crypto account is priced by its ISIN
first. When an ISIN quote is available it wins over the ticker the line also carries (even when
the ticker still names a wrong listing like `MULYF`).

1. The ISIN quote: `PriceRead::isin_quotes`, which asks `IsinPriceService` and then falls back to
   a price recorded under the ISIN itself within the usual 7 days (stale).
2. **Temporary ticker fallback (until #706 lands).** When the ISIN sources return nothing
   — for example during a Yahoo 429 cooldown, offline, or for an ISIN domicile outside `EURO_AREA`
   and `MARKET_CURRENCY` — `valuation::quotes_for` falls back to the line's ticker quote
   (including its 7-day recorded `price_snapshot` fallback). Step 1 already reads the ISIN prices
   recorded since #702; until tickers are removed in #706, this keeps IBKR, DEGIRO, manual accounts,
   and holdings from unsupported domiciles from dropping to 0 EUR or freezing provider-valued
   account totals. On synced provider-valued accounts, `valuation::provider_overrules_quote`
   (#690) continues to overrule any contradictory quote (from either an ISIN source or the ticker
   fallback) and keep the bank's line value.
3. Else the bank's own line value (`provider_value_eur`), as for any unpriced line.
4. Else the line is unpriced: left out of the value and the cost basis.

Lines without an ISIN, and every line of a crypto account, keep their ticker. The account value,
the holdings page, the dashboard, the daily snapshots and the account export all go through the
same `quotes_for`. Writers announce both the ISINs (on non-crypto accounts) and tickers they will
value in `PriceNeeds`, so the lookups happen before the write transaction opens.

### History (#702)

`price_snapshot.ticker` holds the ISIN itself for a line priced by ISIN. The ISINs are the
distinct `account_holding.isin` on live non-crypto accounts.

- **Daily backfill.** `price_backfill` calls `PriceService::backfill_isin_history`, which fills
  twelve months from `IsinPriceService::daily_history` and skips an ISIN whose recorded series has
  no hole wider than a week, as for tickers.
- **Hourly refresh.** `scheduler::refresh_prices` calls `refresh_isin_prices`, which records the
  ISIN quote on the date its source gives it (never later than today, read once the lookups
  finish, so a refresh crossing midnight files the quote under the new day). This feeds the
  recorded fallback of `PriceRead::isin_quotes`.
- **24H chart.** No source has an hourly series by ISIN. An ISIN line is held flat at its current
  value from `quotes_for` (its ISIN quote first, else the bank's value `provider_value_eur`). Its
  ticker's hourly series is never read. `GET /api/prices/{isin}/intraday` answers `[]`.
- **Range P&L.** Both sides of an ISIN line are ISIN prices: the price recorded under the ISIN on
  or before `from`, and the ISIN quote now. A line missing either stays out of the range, never
  completed with a ticker price.
- **Holding chart.** `HoldingResponse.isin` is set on a line priced by ISIN, and the holding
  detail asks `/api/prices/{isin}/history` with it.

### Manual entry and CSV imports (#703)

A manual trade or CSV row entered by ISIN records that ISIN against the ticker it resolved to,
and the line rebuilt from the trades takes the ISIN its ticker is known by. Details in
[manual-transactions.md](manual-transactions.md).

### Rewriting wrong history (#705)

`market::runners::rewrite_isin_history` runs at startup, after `price_backfill` has recorded the
ISIN daily history. On each past DERIVED balance snapshot of a live account with ISIN lines, each
line is priced by its last close on or before that day, within `MAX_FALLBACK_AGE_DAYS` (7 days), as
the snapshot was built, so weekends and holidays are covered. A line whose ticker price that day
contradicts its ISIN price (the #690 factor of two) gives a candidate balance: the line's
contribution swapped, `quantity x (isin price - ticker price)`. The day is rewritten only if its
recorded balance is at least twice off that day's `invested_amount`, either way, and the
candidate is back within twice of it.

- Only the balance and `invested_amount` come from that day. The lines and their quantities are
  today's, so cash paid in or withdrawn since does not sway the test, but a line added since does.
- A day with an ordinary gain or loss is never moved, whichever way the ticker was off. A
  corrected day is no longer suspect, so a rerun changes nothing.
- Never touched: MANUAL snapshots, today's snapshot, crypto accounts, never-market-priced
  providers (Natixis, CMES), lines whose quantity is zero, days without both prices, days with no
  positive invested amount.
- Each account is flagged once done (`app_setting` `isin_history.rewrite_done.<account id>`, the
  value is the count of snapshots rewritten). It is flagged only when each ISIN has a price for
  every past snapshot day of the backfill window (12 months, less its first week). Otherwise, for
  example when `price_backfill` failed and only one hourly point exists, it is retried at the next
  boot. One failing account does not stop the others.
- Each rewritten snapshot is logged at INFO: account id, date, old and new balance.
- **The rewrite is irreversible.** The old balances are not kept in the database, only in the
  logs. Back up the database before upgrading. To replay the rewrite for one account, delete its
  flag (`DELETE FROM app_setting WHERE setting_key = 'isin_history.rewrite_done.<id>'`) and restart.
- Limits: today's quantity is used for every past day (a snapshot keeps no per-line quantities),
  lines sold to zero are not looked at, only days within the ISIN backfill window can be
  corrected, `invested_amount` is left as it was, and a day whose real value was already twice
  off its cost (a doubled position) can be read as suspect. Once an account is flagged, a line
  added later is not looked at, and a day skipped or partly corrected stays as it is.

## Technical choices

- **Three sources in order.** The justETF API is undocumented. Yahoo by ISIN covers it when
  justETF is down, rate-limited, or has no price. Boursorama sits third for unlisted funds (OPCVM)
  that neither justETF nor Yahoo carries.
- **Reference currency instead of exchange order.** `openfigi::pick_best` documents why no
  ordering of exchanges works for every Irish or Luxembourg fund. The currency of the ISIN's
  market is a property of the instrument, not a guess about which line Yahoo carries.
- **Cache.** 15 min for a quote, 5 min for a miss, so an unlisted fund is not asked on every render.
- **429.** justETF pauses itself for 5 minutes. Yahoo arms the existing `YahooCooldown`.
  Boursorama pauses itself for 5 minutes on a 429 or a 403.
- **Boursorama windows.** `GetTicksEOD` answers `[]` for any `length` outside
  30, 90, 180, 365, 730, 1095, 1825, 3650 and 7300 days. History asks the shortest one that
  reaches back to the first day wanted. A plain request (no `X-Requested-With`) answers 410.
  Checked live on 2026-11-25.

## Gotchas

- Yahoo FX converts a USD or GBP listing to EUR at today's rate, as everywhere else in the app.
- Unlisted funds (OPCVM with only a daily NAV) have no price from justETF or Yahoo. Boursorama
  covers those it knows and quotes in euros (#704); any remaining fund without a price takes the
  bank's own figure.
- The ticker fallback of step 2 stays until #706: an ISIN whose sources never answered has no
  recorded ISIN price to fall back on.
- Past days valued through a wrong ticker are rewritten once per account at startup, see
  [Rewriting wrong history](#rewriting-wrong-history-705).
- Up to 4 ISIN lookups run at once; each is cached 15 min (5 min for a miss).

## Key files

- `backend-rs/src/market/isin_prices.rs`: the `IsinPriceSource` trait, all three sources, the service.
- `backend-rs/src/market/yahoo.rs`: `YahooFinance::listing_quote` (price in the listing's own currency).
- `backend-rs/src/ledger/valuation.rs`: `price_key`, `quotes_for`.
- `backend-rs/src/ledger/prices.rs`: `PriceRead::isin_quotes`, `PriceNeeds::isin`.
- `backend-rs/src/market/prices.rs`: `PriceService::isin_quotes`, `refresh_isin_prices`,
  `backfill_isin_history`.
- `backend-rs/src/accounts/history.rs`: `get_intraday`, `get_pnl`.
- `backend-rs/src/market/runners.rs`: `rewrite_isin_history` (#705), run from
  `backend-rs/src/jobs/startup.rs`.
- `backend-rs/tests/integration/market/isin_prices.rs`
- `backend-rs/tests/integration/accounts/isin_history.rs`
- `backend-rs/tests/integration/market/isin_history_rewrite.rs`

## Tests

`cargo test --test integration market::isin_prices`: justETF bodies captured on 2026-11-22,
Yahoo listings stubbed, Boursorama search and EOD bodies captured on 2026-11-25, and the
service ordering and cache with fake sources and a fixed clock.

`cargo test --test integration accounts::isin_history`: the ISIN backfill and hourly record, the
24H chart and the range P&L of an ISIN line, and the holding's ISIN history route (#702).

`cargo test --test integration ledger::manual_isin`: a manual buy and a CSV row entered by
ISIN keep it, and existing manual lines are linked to the ISIN known for their ticker (#703).

`cargo test --test integration market::isin_history_rewrite`: the #683 PEA recomputed from
its ISIN prices once, a manual snapshot kept, a retry when the ISIN history is missing or holds a
single recent point, weekend days rewritten from Friday's close, a line sold to zero left out, a
ticker that agrees with the ISIN, a day already right with a ticker far below, a never-market-priced
account left alone, a later cash payment that does not sway the rewrite, and right days with an
ordinary gain or loss left alone (#705).
