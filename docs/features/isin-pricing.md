# Pricing by ISIN

## Context

A ticker names one listing of an instrument. The ISIN names the instrument itself. Deriving a
ticker from an ISIN and pricing that ticker can land on the wrong listing: `LU1829219390`
(Amundi Euro Stoxx Banks, 4.41 EUR) was priced through the US grey-market line `MULYF`, at
376 EUR (#683). Epic #698 moves every holding with an ISIN to prices read by ISIN.

This note covers step #700: the price source. Nothing reads it yet. Valuation switches to it in #701.

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

## Technical choices

- **Two sources from day one.** The justETF API is undocumented. Yahoo by ISIN covers it when
  justETF is down, rate-limited, or has no price.
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
- Unlisted funds (OPCVM with only a daily NAV) have no price from either source. #701 values
  them at the bank's own figure, and #704 looks for a NAV source.
- The service and sources are not constructed by `Market` yet: `IsinPriceService::standard`
  builds the production pair when #701 wires it.

## Key files

- `backend-rs/src/market/isin_prices.rs`: the `IsinPriceSource` trait, both sources, the service.
- `backend-rs/src/market/yahoo.rs`: `YahooFinance::listing_quote` (price in the listing's own currency).
- `backend-rs/tests/integration/market/isin_prices.rs`

## Tests

`cargo test --test integration market::isin_prices`: justETF bodies captured on 2026-11-22,
Yahoo listings stubbed, the service ordering and cache with fake sources and a fixed clock.
