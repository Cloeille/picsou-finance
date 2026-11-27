# Feature: Portfolio diversification (sector + geography)

> Last updated: 2026-11-26

## Context

The holding detail modal already showed one ETF's countries and sectors. The Analysis section
answers the question that actually matters: across the **whole** equity sleeve — several funds,
several directly held shares, several accounts — how concentrated is it?

Two scores come out of it, one per axis, each with the share of the portfolio it could actually
measure stated beside it.

## How it works

### Where sector and country come from

| Holding | Sector | Country |
|---|---|---|
| ETF | look-through slices from `BoursoramaCompositionProvider` | look-through slices, same source |
| Directly held share | **Yahoo `/v1/finance/search`** | **ISIN prefix**, read from the Boursorama quote page |
| Anything the user disagrees with | `holding_classification.sector_key` | `holding_classification.country_key` |

The equity source was not the obvious one. Boursorama's company page carries
`fv_secteur_activite`, which looks like the answer and is not: it reads `n-d` for anything
outside Euronext (verified on AAPL) and, where present, gives a **sub-industry** ("Chimie de
base") that never merges with the eleven-value taxonomy the ETF slices use. Yahoo's search
endpoint — **already called** by `searchSymbols` for ISIN verification — returns that taxonomy
verbatim for US and European listings alike, so normalising is a lowercase and a
space-to-underscore, and **every resulting key already had a translation**. PR2 added no sector
keys to the locale files.

The country still comes from Boursorama, because Yahoo exposes only the listing venue — wrong for
a Paris-listed US company or an NYSE ADR. The page's analytics block carries
`"fv_code_isin":"US0378331005_AAPL"`, and an ISIN's first two characters are its ISO country of
issuance. That also recovers an identifier ingestion throws away: holdings store a Yahoo ticker,
never the ISIN they were converted from.

**Since 2026-08-15 the ISIN is kept**, on `security_profile`, recorded by every sync that already
receives one. It is what the sources actually resolve: Boursorama's search maps
`LU1681043599` to the same symbol as `CW8`, while `OpenFigiIsinConverter.pickBest` prefers US OTC
tickers for non-US ISINs — the shape Boursorama cannot find. Keeping it is what repaired most of
the missing look-through. See the
[ADR](../decisions/2026-08-15-isin-keyed-lookups-and-justetf.md).

**justETF is second in line**, keyed on that ISIN. It supplies the fee (TER), the distribution
policy and the replication method — none of which any other source here has — and a fallback
breakdown. It publishes only the top four per axis plus an explicit `Other`, against Boursorama's
ten with no residual, which is why it is second: ordering it first would lower every sector score
without adding truth.

The two providers are merged **field by field**, not first-answer-wins. That differs from
`resolveComposition` deliberately, and has to: no single source has both halves, so taking the
first provider's whole answer would discard the country every time the sector arrived first.

### Aggregation

For each account in the `EQUITY` tier, every priced line, share-weighted exactly once (the
`DashboardService` contract). The same ticker held in two accounts is **one** position.

A line whose own tier is not `EQUITY` is left out, with the [wealth pyramid](./wealth-pyramid.md)'s
precedence: the member's `holding_classification.wealth_tier` first, then the provider's
`providerWealthTier`, then the coin registry (`crypto_asset`). A line with none stays in. This keeps
the SCPI units of a [CORUM Life](./corum-life.md) contract (property), crypto coins held inside a
brokerage account, and any line the member moved to another tier out of the "unclassified equity"
list.

An account without holdings that declares a [tracked fund](./tracked-fund.md), whatever its
type, adds **one** line: keyed on the ticker linked to its ISIN (the ISIN until one is linked),
valued at its share-weighted EUR live value. It is added after the held lines, so a held line on
the same ticker merges with it and names it; otherwise the UI shows the ticker. The same equity
filter applies: an override to a non-equity tier removes it. An unlinked ISIN has no profile and
lands in the unclassified list. Hidden accounts are not filtered out, as for held lines.

Per ticker, in order: manual override → ETF look-through → single-share profile → unclassified.

For an ETF whose profile carries positive slices on an axis (`SECTOR` or `COUNTRY`):

- **Distinct raw labels mapping to the same canonical key are summed** (for example FactSet
  sub-sectors `"Technologie"` + `"Technologie électronique"` → `technology`), while **identical raw
  labels are never summed**. For known Boursorama 2-way French label collisions (`"Biens de
  consommation cycliques"` for `consumer_cyclical` / `consumer_defensive`, `"Finance"` for
  `financial_services` / `real_estate`), `resolve_composition` checks justETF's top-4 sectors: if
  either twin sector is named, the occurrence with the closest weight goes to that sector and the
  other to the twin; otherwise both ambiguous occurrences route to `other` with a debug log.
- **Rounding and small residuals (`sum ≥ 99 %` with no explicit `other` slice from the source, or
  `> 100 %` overshoot)** are normalized proportionally to `100 %` of the holding's value so a
  `99.52 %` single-country breakdown does not spawn unclassified or sub-percent dust. When the
  source emitted an explicit `other` / `Autres` row, it is persisted as `other` and never erased by
  normalization.
- **Top-N tails (`50 % ≤ sum < 99 %`, or any `≥ 50 %` distribution carrying an explicit `other`
  row)** keep every named slice at its literal published percentage and assign the remainder
  (`100 - sum`) to a classified `other` slice (`Autres` / `Others`), sorted last.
- **50 % floor (`sum < 50 %`)**: published slices are applied literally and the remainder stays
  **unclassified** (not `other`), so an upstream HTML layout change cannot hide a scraping gap.
- **Synthetic ETFs showing a substitution basket** are detected when `justETF` reports
  `replication = SYNTHETIC` while Boursorama returns non-empty physical equity holdings (non-swap
  lines, e.g. EU collateral holdings on BNP Easy STOXX Europe 600 instead of UK/Switzerland index
  exposure). `resolve_composition` replaces that collateral breakdown with the physical index proxy
  ETF (`proxy_isin_for_index`, which accepts only exact broad indices with neutral suffixes and
  rejects sector/regional/exclusion/factor/thematic sub-indices) or falls back to justETF's own
  index breakdown. If neither is available, the substitution basket is dropped with a warning and
  treated as unknown rather than kept silently.
- **Directly held shares** merge Yahoo's sector (`equity_sector`) with the country code derived
  from the share's ISIN prefix (validated with `openfigi::is_isin`, skipping Boursorama lookup when
  an ISIN is already known even if supranational such as `XS…`, and otherwise reading Boursorama's
  quote page `fv_code_isin` after verifying `fv_symb_societe`).
- **Existing profiles** written before these fixes are queued once at startup by
  `schedule_security_profile_recheck` (atomically claiming `app_setting` key
  `security_profile.composition_fix_v1_done` inside a transaction): it staggers `refreshed_at` in
  batches and caps `last_attempt_at` to the new `refreshed_at` when more recent (so batch 0 is due
  immediately instead of waiting out the 3-day failure backoff), keeping `status` and existing
  slices intact so diversification stays populated immediately on ETFs with slice totals `< 99 %`
  or `SYNTHETIC` replication, stocks missing `country_key`, and `NO_DATA` profiles carrying an
  ISIN. Never-fetched profiles are prioritized ahead of expired `OK` profiles. On a Yahoo 429
  cooldown with nothing resolved, new or `NEVER_FETCHED` profiles are left untouched (retried as
  soon as the cooldown clears), existing `OK` profiles keep their `status`/`refreshed_at`/slices,
  and existing non-`OK` profiles are marked `FAILED` (`rate limited`) without advancing
  `refreshed_at`.

**Each axis reports its own coverage.** With literal percentages the two genuinely diverge — a
share often has a known sector and no domicile, and a fund may disclose its countries far more
completely than its sectors. The headline `coveragePercent` takes the more generous of the two, so
without `sectors.coveragePercent` and `countries.coveragePercent` a score resting on half the data
would look as well-founded as one resting on all of it.

### Drilling into a slice

Each slice carries the holdings behind it, largest first, with their euros and their share — so
"France 8.4 %" can be opened rather than merely read. Contributors are capped at twelve and
anything under 0.5 % of a slice joins a folded tail (`ticker: null`), because a slice answers
*why is this this big?* and thirty lines of 0.1 % do not answer it. `contributorCount` still
reports the true number.

Names and accounts live in a top-level `securities` dictionary rather than being repeated: one ETF
lands in a dozen slices across two axes.

### Ways to draw it

Bars, donut or treemap, chosen in the card header and remembered in `localStorage`. All three
share **one** selection model, so a slice opened from the treemap shows the same panel the legend
opens. The selected slice keeps its colour and the others dim — highlighting the selection instead
would break the tie between chart, legend and panel that makes the three read as one chart.

The treemap layout is `frontend/src/lib/treemap.ts`, shared with the account distribution chart
rather than reimplemented. Recharts' own `Treemap` is unused: it cannot express the hover
behaviour that chart had already worked out.

### The scores

Effective number of positions — the inverse Herfindahl index:

```
N_eff = 1 / Σ wᵢ²          score = min(100, 100 · N_eff / target)
```

Targets: **6** sectors, **3** regions.

Counting buckets cannot distinguish 20/20/20/20/20 from 96/1/1/1/1; both hold five sectors and
only one is diversified. `N_eff` reads them as 5.0 and 1.09.

Both scores are computed over the **named** classified slices only: the residual `other` bucket is
excluded from Herfindahl and weights are renormalized across named buckets, because `other`
aggregates unknown holdings/sectors/countries and counting it as a single homogeneous bucket would
artificially inflate `N_eff` and the score. `coveragePercent` travels alongside the score so a bar
computed over 60% of a portfolio cannot be read as one computed over all of it — the same
discipline as the `Others` remainder in the holding modal and `Valuation.anyPriced`.

### Never fetching on the read path

`security_profile` is a durable, global cache; `SchedulerService.refreshSecurityProfiles()` warms
it weekly, over the held tickers and the [tracked fund](./tracked-fund.md) keys. The breakdown
reads rows and nothing else. See the [ADR](../decisions/2026-08-13-persisted-security-profiles.md).

### Correcting a line by hand

The weekly pass is not enough on its own, for two different reasons:

- **A cold instance.** Nothing warms the table on first read, so a fresh install shows a wholly
  unclassified breakdown until the following Sunday. That reads as the feature being broken
  rather than merely cold, so `POST /api/analysis/security-profiles/refresh` runs the same pass
  on demand. It answers `202` and does the scraping on a single background thread — one or two
  HTTP calls per ticker with no pacing is far past any request timeout — and refuses to start a
  second pass while one is running.
- **Securities no provider covers.** Employee-savings FCPEs (`QS…` codes), unlisted funds and
  ELTIFs are on neither Yahoo nor Boursorama, and no amount of refreshing will place them. On a
  real portfolio these are not an edge case: three Amundi FCPE lines can be 60 % of the equity
  sleeve. For those the manual override is the only route, so the breakdown lists what it could
  not place and each entry opens the editor.

`GET /api/accounts/{id}/holdings/{ticker}/classification` backs that editor. It returns the
member's override and the providers' guess as **separate** fields. Merging them into one
"effective" value would leave the form unable to say whether you are confirming a guess or
reading your own earlier decision — and saving a pre-filled guess would freeze it in place, so
the value would stop tracking the provider forever. The guess is therefore shown as text and
never pre-selected.

Clearing all three fields deletes the row rather than storing a blank verdict, so "no override"
and "deliberately unclassified" cannot diverge.

### Key files

- `EquityProfileProvider.java` — the port, and `EquityProfile.java`
- `backend-rs/src/market/yahoo.rs` — `fetch()`, `sectorFrom()`, `sectorKey()`
- `BoursoramaEquityProfileProvider.java` — country from the ISIN
- `backend-rs/src/market/boursorama.rs` — symbol resolution, shared with the composition provider
- `backend-rs/src/accounts/analysis/profiles.rs` — `load` (read) vs `refresh` (network)
- `backend-rs/src/accounts/analysis/diversification.rs` — the roll-up
- `backend-rs/src/accounts/tracked_fund.rs` — the key of an account tracking a fund
- `backend-rs/src/accounts/classification.rs` — the manual override
- `backend-rs/migrations/V84__security_profile.sql`
- `backend-rs/src/accounts/analysis/profiles.rs` — the on-demand pass
- `SecurityIdentityService.java` — ticker → ISIN, recorded at sync
- `backend-rs/src/market/justetf.rs` — fees, policy, fallback breakdown
- `ClassificationKeys.java` — the unlisted sector and region
- `frontend/src/lib/treemap.ts` — the layout, shared with the account distribution chart
- `frontend/src/pages/analysis/BreakdownChart.tsx`, `SliceContributors.tsx`
- `frontend/src/pages/analysis/DiversificationSection.tsx`, `frontend/src/lib/chart-palette.ts`
- `frontend/src/pages/analysis/HoldingClassificationModal.tsx` — the editor, shared by both entry points

### Flow

```
GET /api/analysis/diversification
  └─ PortfolioDiversificationService
       ├─ readable EQUITY accounts → lines (not tiered elsewhere) → weigh once → value by ticker
       ├─ tracked fund accounts (no holdings) → one line on the linked ticker ▸ ISIN
       ├─ SecurityProfileService.load(tickers)          one query, no network
       └─ per ticker: override ▸ ETF slices (≥99% normalized if no explicit other, 50–99% tail to other, <50% tail unclassified) ▸ share profile ▸ unclassified
            └─ N_eff per axis (named slices only, excluding other), coverage + unplaced lines reported

SchedulerService.refreshSecurityProfiles()  (Sundays 03:45, ≤40 tickers)
POST /api/analysis/security-profiles/refresh  (on demand, same cap, one at a time)
  └─ SecurityProfileService.refresh(ticker)
       ├─ SecurityInsightService.getInsight → assetType (+ ETF composition)
       └─ if STOCK: EquityProfileProviders, merged field by field

PUT /api/accounts/{id}/holdings/{ticker}/classification
  └─ HoldingClassificationService — owner-gated write, row keyed on (member, ticker)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Yahoo search for the sector | Already-called endpoint, works outside Euronext, returns the exact taxonomy the ETF slices use — zero new i18n keys | Boursorama's `fv_secteur_activite`: `n-d` off Euronext, and a sub-industry elsewhere |
| ISIN prefix for the country | Universal, and recovers the ISIN ingestion discards | Yahoo's `exchDisp` — the listing venue, wrong for an ADR or a cross-listing |
| A separate `EquityProfileProvider` port | An ETF has a distribution, a share has one sector; and `resolveComposition` stops at the first provider with any data, so adding to that list would change ETF behaviour | Widening `EtfCompositionProvider` |
| Field-by-field merge | No source has both halves | First provider with data wins, as compositions do |
| Tiered `spread()` thresholds (≥99% normalized if no explicit `other`, 50–99% tail in `other`, <50% floor left unclassified) | Avoids reporting known top-N ETF tails or single-country rounding as unclassified while preserving explicit `other` rows and preventing broken HTML scrapes from hiding gaps inside `other` | Applying every slice literally (marks every top-N ETF partly unclassified) or normalizing any partial sum (invents weights we do not have) |
| Inverse Herfindahl over named slices only (excluding `other`) | Distinguishes 20/20/20/20/20 from 96/1/1/1/1 without letting an opaque `other` bucket inflate `N_eff` as if it were a single diversified sector/country | Counting distinct buckets, or including `other` in Σ wᵢ² |
| Coverage stated, never renormalised | A score over 60% of a portfolio must not read as a score over all of it | Renormalising to the classified part silently |
| Unlisted holdings get `private_equity` / `XU` | An ELTIF is not a failed lookup, it is a class of asset; without a home it sat in the to-classify list forever | Leaving them unclassified; using `ZZ`, which CLDR defines as *Unknown Region* and would conflate a verdict with an absent answer |
| Override keyed `(member, ticker)` | Survives the prune that deletes `account_holding` rows a provider stops reporting | A column on `account_holding` |
| Palette extracted to `lib/chart-palette.ts` | One ETF's sectors and the whole portfolio's should read as the same chart at two scales | A second palette in the new component |

## Gotchas / Pitfalls

- **A wrong Boursorama symbol does not 404.** It answers 200 with something else, so
  `countryOf` refuses any page that does not carry `"fv_symb_societe":"<the symbol asked for>"`.
  "The page loaded" is not "the page is about this security".
- **`fv_secteur_activite` can be the literal string `n-d`.** Treat it as absent; it is not a
  sector label. This provider ignores the field entirely for that reason.
- **Never `quotes[0]` from Yahoo's search.** It is a relevance ranking: a thin European listing
  can be outranked by a better-known foreign namesake, and the position would be filed under
  that company's sector. Match the symbol exactly.
- **The country breakdown mixes two quantities** once a directly held share contributes — index
  exposure for funds, domicile for shares. `basis` says which, and the UI notes it. See the
  [ADR](../decisions/2026-08-13-equity-domicile-vs-etf-exposure.md).
- **`coveragePercent` is the more generous of the two axes.** A holding counts as classified when
  *either* sector or country could be placed; the per-axis truth is in each `Breakdown`.
- **Duplicate raw labels from a provider are never summed.** Distinct raw sub-sector labels mapping
  to the same canonical key are summed in the parsers; known Boursorama 2-way collisions
  (`Biens de consommation cycliques`, `Finance`) are disambiguated against justETF's top-4 or
  routed to `other`. The unique key in `security_composition_slice` is `(profile, kind, label)`, so
  `collect_slices` only sums `other` and routes any unexpected duplicate named slice to `other`
  rather than failing the save or silently inflating a named bucket.
- **The weekly job is capped at 40 tickers.** A large portfolio takes a few passes to cover. That
  is visible as `pendingTickers`, not as a wrong number.
- **`BoursoramaCompositionProvider` no longer owns its WebClient**; it takes `BoursoramaClient`.
  Its existing test only exercises the static parsers, which is why the refactor was safe — keep
  it that way.

## Tests

- `YahooEquityProfileTest` — taxonomy normalisation, exact-symbol matching (not `quotes[0]`),
  an ETF yielding no sector, null/empty responses
- `BoursoramaEquityProfileProviderTest` — real (trimmed) fixtures: a French listing, a US one
  whose sector reads `n-d`, a wrong-symbol page that must be refused, a malformed ISIN
- `SecurityProfileServiceTest` — the field-by-field merge, ETF stored as slices, duplicate labels,
  UNKNOWN still recorded, only-stale refresh with the batch cap, one bad ticker not aborting
- `PortfolioDiversificationServiceTest` — share vs ETF placement, partial look-through
  renormalised, override per field, unclassified reported not renormalised, same ticker twice,
  shares applied once, `N_eff` on concentrated vs even portfolios, `EXPOSURE` vs `MIXED`, a line
  the provider files as real estate left out, the member's tier beating the provider's
- `tests/integration/accounts/analysis.rs` — a tracked account as one line spread by its profile,
  merged with a held line, unclassified while unlinked, left out when overridden out of equity
- `DiversificationSection.test.tsx`, `e2e/analysis.spec.ts`

## Links

- ADR: [Domicile vs exposure](../decisions/2026-08-13-equity-domicile-vs-etf-exposure.md)
- ADR: [Persisted security profiles](../decisions/2026-08-13-persisted-security-profiles.md)
- Related: [Security Insight](./security-insight.md) — the per-ETF composition this builds on
- Related: [Wealth pyramid](./wealth-pyramid.md) — the same Analysis page, and the same override row
- Related: [Tracked fund](./tracked-fund.md) — an account without lines counted as the fund it holds

## Co-owned accounts

A co-owner sees their weighted share of a shared account with holdings, like the owner sees
theirs. The Java backend answered `404` there (it checked that the account was readable, then
loaded the holdings of the owner's accounts only); the Rust backend loads the holdings of every
readable account, and the contract scenario `analysis.isolation` records the new answer.
