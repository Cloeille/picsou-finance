# Feature: Dividend forecast

> Last updated: 2026-10-29

## Context

Picsou knew what a member holds but not the income those holdings will pay. This feature
forecasts **future** dividends of listed shares and ETFs, and SCPI distributions, and shows them
in Budget as upcoming capital income and on the page of a compte-titres or a PEA (issue #244).
Past dividends are not forecast: they are recognised from the real payment on the account,
see [Received dividends](#received-dividends).

## How it works

### Sources (weekly, never on the read path)

- **Yahoo Finance** chart endpoint, `range=2y&interval=1mo&events=div`: ex-date and amount per
  share for every ticker. No payment date. An event with a missing or zero amount or date is
  skipped, never stored as a 1970 dividend.
- **Nasdaq** `api/quote/{symbol}/dividends` (no key, unofficial), only for tickers without an
  exchange suffix: payment date and already-declared dividends. In practice it fills
  Nasdaq-listed stocks only; NYSE listings, ETFs and European tickers come back empty, which is
  the normal case and not an error.
- **SCPI**: the member declares an annual distribution per share and a frequency (monthly or
  quarterly) on the position. The amount is bounded to its column, `NUMERIC(20, 8)`: a larger
  value is rejected with a 400.

`SchedulerService.refreshDividendHistory` runs on Sunday at 04:15, on its own executor so it
does not occupy Spring's single scheduler thread. It walks the distinct held tickers, stalest
first (never fetched, then the oldest `refreshedAt`), capped at 40 per pass and paced. A ticker
is included when `security_profile` has a non-null ISIN for it, or when at least one live
holding of it sits in a non-crypto account. It is skipped only when no ISIN is known and every
live holding of it is crypto: one member's crypto ATOM must not hide another member's ATOM
stock, and a coin never carries an ISIN. The history stays keyed on the ticker. Yahoo and
Nasdaq are still queried by ticker, not by ISIN. The pass is skipped while `YahooCooldown` is
active. Each ticker is fetched outside the write transaction. A success is refreshed after 6
days, a failure retried after 3 days. The threshold sits under the weekly cron period on
purpose: `refreshedAt` is stamped after the fetch, so a 7-day threshold would skip every source
on the next Sunday and refresh it only every other week. A failed fetch keeps the stored
events, and a Nasdaq failure never discards the Yahoo history. A stored future declaration
stays unless Nasdaq actually returned that ex-date. When both sources have that future ex-date
and a rate exists, the Nasdaq amount replaces the Yahoo one.

### Forecast (on the fly, read-only)

- **Source status**: the forecast reads every source that has stored events, whatever its status.
  A `FAILED` refresh keeps the last good events, so one 429 or FX miss does not blank the
  forecast; a source with no stored event yields no line.
- **Cadence**: inferred from the ex-dates of the last 24 months only when every gap falls in one
  band (monthly, quarterly, semi-annual, annual). The last amount is carried forward. Irregular
  or lapsed payers get no forecast.
- **Announced**: a declared future dividend is used as is (basis `ANNOUNCED`, real payment
  date) and replaces any projected date within 20 days of it, including a declaration whose
  ex-date sits just past the horizon.
- **Window**: a holding line is in `[today, end]` when its known payment date is. When the
  payment date is null the ex-date decides. No lag is invented for that choice. A dividend
  that has already gone ex, with a known payment still ahead, is included.
- **Payment date** of a projected line: ex-date plus the median lag learned from stored payment
  dates for that ticker, else from the member's past `DIVIDEND` transactions on that ticker
  over the last 24 months (same account first, paired with an ex-date at most 60 days earlier).
  A negative lag, or one longer than 60 days, is ignored. Otherwise `null`: no default lag is
  ever invented.
- **SCPI**: one payment on the 25th of the month after each period, none for a period ending
  before the jouissance date.
- **Amounts** are gross, in EUR at the FX rate of the refresh, weighted by the member's
  ownership share.
- **`countsInBudget`** is false for a dividend inside a PEA, an assurance vie, a PER or an
  employee savings plan (`AccountType.isLockedWrapper()`), and for a reinvested SCPI
  distribution: the money stays in the envelope. Such lines are listed but left out of the
  Budget total. The rule lives in the backend only.

### Received dividends

A cash credit is recognised as a dividend from the stored history, with no network call.
`DividendReceiptService.classifyRecent(memberId, today)` runs after every member sync
(`MemberSyncService`, in `resyncScheduled` and both `resyncForUser`). It is best effort: a
failure is logged and never changes the sync result.

1. Owned accounts only. Categories are member-scoped, so a co-owned row is classified by its owner.
2. Credits of the last 45 days: amount > 0, category not set manually, type empty, `DEPOSIT` or `DIVIDEND`,
   category empty or of kind INCOME, and not a Trade Republic `TRADING` row. A TRANSFER or EXPENSE
   auto category marks a trade leg (TR books the BUY investment leg and the SELL cash leg as
   positive `DEPOSIT`s), so such a row is never retyped.
3. Candidate lines: the positive holdings of the credited account. With none, the owned
   accounts of the same provider (Trade Republic pays into "TR Cash" while the lines sit on the PEA).
   Crypto lines are skipped.
4. The event is the one with the latest ex-date in the 60 days before the credit. When it has a
   payment date, the credit must fall within 7 days of it.
5. Expected amount = quantity x per-share amount (`amountEur` for a EUR credit, `amount` when the
   credit currency is the source currency, otherwise skipped). Kept when received / expected
   is within 0.40 to 1.10, which absorbs withholding tax and FX drift.
6. **Evidence is required, an amount alone is not enough** (a 100 EUR deposit must not become a
   dividend): the row is already typed `DIVIDEND`, or a label word starts with `divid`, `coupon`,
   `dvd` or `distribution` (a word prefix, so `INDIVIDUEL` is not evidence), or it names the
   instrument (a distinctive word of the holding name, or the ticker root: 4 letters or more alone,
   a 3-letter root such as `AIR` or `SAN` only next to a keyword or a `DIVIDEND` type).
   A candidate naming the instrument wins; else a keyword or `DIVIDEND` type with exactly one
   candidate; otherwise the row stays untouched.
7. The row gets `txType = DIVIDEND`, its ticker and name when empty, and the member's
   `revenus-capital` category when it has none or an automatic INCOME one. A second run changes nothing.

`GET /api/dividends/received?days=30` (clamped to 1..366) lists the `DIVIDEND` rows of every
readable account. Each line has the same `id` as the forecast line it fulfils
(`HOLDING:accountId:ticker:exDate`), or `HOLDING:accountId:ticker:tx<id>` when no ex-date is
known, so a push notifier can say "you received the TotalEnergies dividend". `accountId`,
`accountType` and `countsInBudget` are those of the account holding the line: a Trade Republic
dividend credited to "TR Cash" for a PEA line reports the PEA, like the forecast does. With no
readable holder of that ticker, or several of the same provider, the credited account is kept.

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
- **SCPI forms**: annual distribution per share and frequency. Omitting either field leaves
  the stored value alone, the same way an omitted fund code does. An explicit null clears it.

### Key files

- `adapter/YahooFinancePriceProvider.java` (`fetchDividendHistory`), `adapter/NasdaqDividendProvider.java`
- `service/DividendHistoryService.java` — weekly refresh, merge of both sources
- `service/DividendForecastService.java` — cadence, projection, lag, SCPI, budget flag
- `service/DividendReceiptService.java` — recognises received dividends, `received` list
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
- Nasdaq's unofficial dividend endpoint expects the headers of its quote page (a browser
  user agent, `Origin` and `Referer`). Those headers are a product decision and are set once
  per request, not also as client defaults.
- Amounts are gross; withholding tax and PFU are not applied.
- The expected amount uses today's quantity, not the quantity held at the ex-date.
- A dividend with no label evidence stays unclassified, even when its amount fits.
- Only Trade Republic trade legs are excluded by type (`TRADING`). Another broker's sale credit,
  uncategorised and labelled with the instrument name, is still recognised when its amount fits
  the band.
- `/received` resolves the holding account among every readable account, so a co-owned account
  holding the same ticker can be reported for a credit on the member's own cash account.
- A co-owned row is classified by the owner only, so it can be listed by `received` before the
  owner's next sync.
- `amount` in `/received` is the credit as booked, not weighted by the member's share.
- `/received` lists every readable `DIVIDEND` row, including a broker-typed one that no stored
  event fits (then `exDate` and `amountPerShare` are null, and the row may stay uncategorised).
- In `/received`, `exDate` and `amountPerShare` come from the latest event in the 60 days before
  the credit, with no amount-fit check: they describe the likely payment, not a proven one.

## Links

- ADR: [2026-10-20 dividend forecast](../decisions/2026-10-20-dividend-forecast-weekly-yahoo-history.md)
- Issue: #244
