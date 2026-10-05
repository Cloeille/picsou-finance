# ADR: Persist security profiles and warm them on a schedule

> Date: 2026-08-13
> Status: ✅ Active

## Context

`SecurityInsightService` answers "what is this security?" for **one** ticker, when the user opens
a holding's detail modal. It caches in a `ConcurrentHashMap` with a 3-day TTL, which is lost on
restart. That is fine for its job: one lookup, user-initiated, and a cold cache costs one request.

The diversification breakdown needs the same answer for **every distinct ticker in the
portfolio**, on a page render. With the in-memory cache that means, after every deploy and every
restart, N synchronous lookups inside one HTTP request — each up to a 15-second timeout, several
of them two-step scrapes of an unofficial HTML page. Slow for the user, and a reliable way to get
the instance rate-limited or blocked.

## Decision

Persist what we resolve, in two tables (`security_profile` + `security_composition_slice`), and
**never fetch on the read path**.

- `PortfolioDiversificationService` reads only persisted rows.
- `SchedulerService.refreshSecurityProfiles()` runs weekly, refreshes profiles older than 30 days,
  capped at 40 tickers per pass, one `try`/`catch` per ticker.
- A ticker with no profile yet is **reported as unclassified**, with its name in
  `pendingTickers` and the covered share of the portfolio stated beside the score.

The tables are **global, not member-scoped**: a security's sector is nobody's private data. Same
reasoning that already makes `price_snapshot` and `/api/securities/{ticker}/insight`
member-agnostic.

`SecurityInsightService` keeps its own in-memory cache for the single-ticker endpoint. Merging the
two is a separate change with its own tests.

## Alternatives considered

### Keep the in-memory cache and warm it at startup

- **Pros**: no migration, no new tables.
- **Cons**: the N requests still happen, just at boot instead of on first render; they happen
  again after every restart; and nothing can report coverage, because "not cached" and "not
  resolvable" are indistinguishable.

### Fetch lazily on the roll-up, cache after

- **Pros**: simplest; always current.
- **Cons**: the first request after a deploy takes minutes, and a provider outage turns a page
  render into a timeout. It also puts a burst of scraping behind an ordinary page view, which is
  precisely the pattern that gets an IP blocked.

### Store the composition as a JSON blob on one column

- **Pros**: one table, one write.
- **Cons**: the project uses no JSONB anywhere, and slices as rows can be asserted on in a
  `@DataJpaTest` and queried without deserializing.

## Reasoning

The read path and the resolve path have opposite requirements: one must be fast and always
answer, the other may be slow and is allowed to fail. Separating them is what lets the breakdown
degrade into "93% covered, MC.PA pending" instead of into a spinner.

Weekly is not a compromise — a sector genuinely does not change. The staleness that matters here
is measured in years.

## Trade-offs accepted

- A newly bought security is unclassified until the next pass, up to a week. Coverage is stated,
  so this reads as "not looked up yet" rather than as a wrong number.
- The 40-ticker cap means a very large portfolio takes a few weeks to cover fully. Raising it is
  a one-line change, deliberately not made until someone needs it.
- Two caches for the same question exist until `SecurityInsightService` is migrated.

## Consequences

- Migration `V84__security_profile.sql` adds both tables and the sector/country columns on
  `holding_classification`.
- `SecurityProfileService` splits `load` (read-only, batched by ticker) from `refresh` (network,
  transactional, one ticker).
- The weekly job shares the default scheduler thread with `refreshPrices`, `dailyBankSync`,
  `dailySnapshots` and `monthlyPropertyValuation`; the batch cap and per-ticker guard are what
  keep it from starving them.
