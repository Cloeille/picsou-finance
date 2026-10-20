# Feature: Dividend forecast

> Last updated: 2026-10-20

## Context

Picsou knew what a member holds but not the income those holdings will pay. This feature
forecasts **future** dividends of listed shares and ETFs, and SCPI distributions, and shows them
in Budget as upcoming capital income and on the page of a compte-titres or a PEA (issue #244).
Past dividends are not forecast: they are classified from the real payment on the account.

## How it works

### Sources (weekly, never on the read path)

- **Yahoo Finance** chart endpoint, `range=2y&interval=1mo&events=div`: ex-date and amount per
  share for every ticker. No payment date.
- **Nasdaq** `api/quote/{symbol}/dividends` (no key, unofficial), only for tickers without an
  exchange suffix: payment date and already-declared dividends. In practice it fills
  Nasdaq-listed stocks only; NYSE listings, ETFs and European tickers come back empty, which is
  the normal case and not an error.
- **SCPI**: the member declares an annual distribution per share and a frequency (monthly or
  quarterly) on the position.

`SchedulerService.refreshDividendHistory` runs on Sunday at 04:15 over the distinct held tickers
(crypto excluded), capped at 40 per pass and paced. It is skipped while `YahooCooldown` is
active. A success is refreshed after 7 days, a failure retried after 3 days. A failed fetch keeps
the stored events, and a Nasdaq failure never discards the Yahoo history.

### Forecast (on the fly, read-only)

- **Cadence**: inferred from the ex-dates of the last 24 months only when every gap falls in one
  band (monthly, quarterly, semi-annual, annual). The last amount is carried forward. Irregular
  or lapsed payers get no forecast.
- **Announced**: a declared future dividend is used as is (basis `ANNOUNCED`, real payment
  date) and replaces any projected date within 20 days of it.
- **Payment date** of a projected line: ex-date plus the median lag learned from stored payment
  dates for that ticker, else from the member's past `DIVIDEND` transactions on that ticker
  (same account first, paired with an ex-date at most 60 days earlier). Otherwise `null`: no
  default lag is ever invented.
- **SCPI**: one payment on the 25th of the month after each period, none for a period ending
  before the jouissance date.
- **Amounts** are gross, in EUR at the FX rate of the refresh, weighted by the member's
  ownership share.
- **`countsInBudget`** is false for a dividend inside a PEA, an assurance vie, a PER or an
  employee savings plan (`AccountType.isLockedWrapper()`), and for a reinvested SCPI
  distribution: the money stays in the envelope. Such lines are listed but left out of the
  Budget total. The rule lives in the backend only.

### API

`GET /api/dividends/upcoming?horizonDays=60[&accountId=12]` (horizon clamped to 1..366). Each
line has a deterministic `id` (`SOURCE:accountId:instrument:expectedDate`), which a future push
notifier (iOS, 2.0) can use to tell "already sent" from "new".

### UI

- **Budget overview**: "Revenus du capital à venir" card, total of the next 60 days for
  `countsInBudget` lines, next payments, and a muted line for the amount kept in envelopes.
- **Account detail** (compte-titres, PEA): carousel of cards for the next 12 months, each with
  ex-date and amount, payment date (or "unknown") and amount, amount per share, and an
  "Annoncé" / "Estimé" badge.
- **SCPI forms**: annual distribution per share and frequency.

### Key files

- `adapter/YahooFinancePriceProvider.java` (`fetchDividendHistory`), `adapter/NasdaqDividendProvider.java`
- `service/DividendHistoryService.java` — weekly refresh, merge of both sources
- `service/DividendForecastService.java` — cadence, projection, lag, SCPI, budget flag
- `controller/DividendController.java`
- `db/migration/V116__dividend_forecast.sql` — `dividend_source`, `dividend_event`, SCPI fields,
  `revenus-capital` category back-fill
- `frontend/src/features/dividends/`, `components/dividends/AccountDividendsSection.tsx`,
  `pages/budget/OverviewTab.tsx`

## Gotchas / Pitfalls

- A ticker bought during the week has no forecast until the next Sunday pass.
- The forecast follows history: a cut or a cadence change shows up only once the source has it.
- NYSE stocks keep an estimated payment date unless the member already received a dividend on
  them; there is no free keyless source for them.
- Amounts are gross; withholding tax and PFU are not applied.

## Links

- ADR: [2026-10-20 dividend forecast](../decisions/2026-10-20-dividend-forecast-weekly-yahoo-history.md)
- Issue: #244
