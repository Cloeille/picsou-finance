# ADR: Forecast dividends from a weekly Yahoo history and declared SCPI distributions

> Date: 2026-10-20
> Status: ✅ Active

## Context

Picsou knows what a member holds (`account_holding`, `scpi_position`) and already projects
upcoming *charges* in Budget (`/api/recurring/calendar`). It says nothing about the income those
holdings will pay. Issue #244 asks for upcoming dividends, for listed securities and for SCPI, as
capital income in Budget, with an identifier stable enough for a future iOS push notification.

Two constraints shape the answer:

1. **Yahoo is already the busiest source.** Prices hit the chart endpoint every hour for every
   ticker, and a 429 already pauses logo scraping (`YahooCooldown`, issue #162). A per-request
   dividend lookup would multiply calls by the number of Budget page views.
2. **No public feed publishes upcoming SCPI distributions.** Management companies announce them
   in quarterly bulletins (PDF), not in a machine-readable form.

## Decision

**Store each ticker's dividend history, refreshed weekly from the Yahoo chart endpoint already
used for prices; forecast from its cadence. Forecast SCPI from a distribution the member
declares. Never call a provider on the read path.**

- `dividend_source` / `dividend_event` (Flyway V116) hold the history per ticker, globally: a
  dividend is not private data, so one fetch serves every member holding the ticker. Each event
  stores its EUR amount at refresh time, so the forecast never needs a live FX rate.
- `SchedulerService.refreshDividendHistory` runs on Sunday at 04:15, on its own executor so the
  pass does not occupy Spring's single scheduler thread, one
  `/v8/finance/chart/{ticker}?range=2y&interval=1mo&events=div` call per distinct ticker,
  stalest first, capped at 40 per pass and paced. Each ticker is fetched outside the write
  transaction. A ticker is included when a non-null ISIN is known for it, or when at least one
  live holding sits in a non-crypto account; it is skipped only when no ISIN is known and every
  live holding of it is crypto. It **reads** `YahooCooldown`: while prices are rate-limited, the
  pass is skipped. A success is refreshed after 6 days (under the weekly cron period, since
  `refreshedAt` is stamped after the fetch), a failure retried after 3. A failed fetch keeps the
  previous events, and the forecast keeps using them.
- US listings (tickers without an exchange suffix) are completed in the same pass by Nasdaq's
  `api/quote/{symbol}/dividends` (no key, unofficial): payment date, record date and the
  already-declared next dividend, stored as an announced event. A Nasdaq failure never discards
  the Yahoo history. A stored future declaration stays unless Nasdaq returned that ex-date; when
  both have it and a rate exists, the Nasdaq amount replaces the Yahoo one. Nasdaq only fills
  Nasdaq-listed stocks: NYSE listings (KO, JNJ, O), ETFs and European tickers come back empty,
  so it cannot replace Yahoo.
- The cadence (monthly, quarterly, semi-annual, annual) is inferred only when every gap between
  consecutive ex-dates in the last 24 months falls in one band; the last amount is carried
  forward. An irregular or lapsed payer gets no forecast. A holding line is in the horizon when
  its known payment date is; when that date is null the ex-date decides, and no lag is invented
  for the choice. An announced ex-date just past the horizon still suppresses the cadence
  projection inside it.
- An SCPI position gains an optional declared annual distribution per share and a frequency
  (monthly or quarterly). Omitting either field leaves the stored value alone; an explicit null
  clears it. The amount is bounded to `NUMERIC(20, 8)`: a larger value is a 400, not a 500.
  Payments are placed on the 25th of the month after each period, none before the jouissance
  date. A `REINVEST` position is forecast and flagged, not hidden.
- The forecast is computed on the fly by a read-only service and exposed as
  `GET /api/dividends/upcoming`. Each line carries a deterministic id
  (`SOURCE:accountId:instrument:date`), which is what a notifier needs to tell "already sent"
  from "new".
- Only future payments are forecast; past ones are classified from the real transaction. A
  dividend paid inside a PEA, an assurance vie, a PER or an employee savings plan cannot be
  withdrawn freely, so it is listed but flagged `countsInBudget: false` and kept out of the
  Budget total, like a reinvested SCPI distribution. The backend owns the rule; the UI reads
  the flag.
- Amounts are gross. Nothing is written to balances or transactions: the real payment still
  arrives through the normal sync path, as with the savings-interest projection
  ([ADR 2026-06-28](./2026-06-28-savings-livrets-interest-projection.md)).

## Alternatives considered

### Announced dividends from `quoteSummary?modules=calendarEvents`

- **Pros**: the next ex-date and amount as declared by the issuer, not inferred.
- **Cons**: the endpoint answers 401 without Yahoo's cookie/crumb handshake, which is fragile and
  doubles the calls. Only the next event is given, and many tickers omit it. Rejected for now; the
  table can store an announced event later without changing the read path.

### Fetch on demand when Budget opens

- **Pros**: always fresh, no table.
- **Cons**: one Yahoo call per ticker per page view, competing with prices for the same rate
  limit. Rejected.

### A paid dividend calendar API

- **Pros**: announced dates, better coverage.
- **Cons**: an API key and a cost for a self-hosted app; most providers' free tiers are smaller
  than Yahoo's. Rejected.

### Scrape SCPI bulletins

- **Pros**: no data entry.
- **Cons**: one PDF layout per management company, published after the fact. The CORUM and
  Sofidy sidecars could fill the declared fields later; the model does not depend on how they are
  filled. Deferred.

## Consequences

- A ticker bought on Monday gets its forecast after the next Sunday pass at the latest.
- The forecast is wrong when an issuer changes its cadence or amount, until the history reflects
  it. The UI labels every amount as a gross estimate.
- Push notifications (iOS, 2.0) can be built on the endpoint without schema change.
