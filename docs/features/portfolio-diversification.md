# Feature: Portfolio diversification (sector + geography)

> Last updated: 2026-08-15

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

Per ticker, in order: manual override → ETF look-through → single-share profile → unclassified.

A fund's published percentages are applied **literally**, and the holding counts as classified
only for the share they actually cover.

This was the opposite until 2026-08-15: percentages were renormalised to their own total and the
whole holding declared placed. The reasoning was that providers publish near-complete
distributions. They do not — Boursorama's sector breakdowns in this repo's own fixtures sum to
**87.25 %** and **70.04 %**, and justETF names its remainder outright (`Other 17.84 %`). So a fund
whose sectors were 70 % disclosed was reported as fully classified, the missing 30 % invented by
inflating the parts we happened to know.

The undisclosed remainder now lands in `unclassifiedValueEur` and lowers `coveragePercent`, which
is the figure that exists to say how much of the portfolio a score was computed over. Coverage
therefore reads *lower* than it used to for partially-disclosed funds; it was overstated before.

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

Both scores are computed over the **classified** part, and `coveragePercent` travels with them so
a bar computed over 60% of a portfolio cannot be read as one computed over all of it — the same
discipline as the `Others` remainder in the holding modal and `Valuation.anyPriced`.

### Never fetching on the read path

`security_profile` is a durable, global cache; `SchedulerService.refreshSecurityProfiles()` warms
it weekly. The breakdown reads rows and nothing else. See the
[ADR](../decisions/2026-08-13-persisted-security-profiles.md).

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

- `backend/src/main/java/com/picsou/port/EquityProfileProvider.java` — the port, and `dto/EquityProfile.java`
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — `fetch()`, `sectorFrom()`, `sectorKey()`
- `backend/src/main/java/com/picsou/adapter/BoursoramaEquityProfileProvider.java` — country from the ISIN
- `backend/src/main/java/com/picsou/adapter/BoursoramaClient.java` — symbol resolution, shared with the composition provider
- `backend/src/main/java/com/picsou/service/SecurityProfileService.java` — `load` (read) vs `refresh` (network)
- `backend/src/main/java/com/picsou/service/PortfolioDiversificationService.java` — the roll-up
- `backend/src/main/java/com/picsou/service/HoldingClassificationService.java` — the manual override
- `backend/src/main/resources/db/migration/V84__security_profile.sql`
- `backend/src/main/java/com/picsou/service/SecurityProfileRefreshRunner.java` — the on-demand pass
- `backend/src/main/java/com/picsou/service/SecurityIdentityService.java` — ticker → ISIN, recorded at sync
- `backend/src/main/java/com/picsou/adapter/JustEtfProvider.java` — fees, policy, fallback breakdown
- `backend/src/main/java/com/picsou/model/ClassificationKeys.java` — the unlisted sector and region
- `frontend/src/lib/treemap.ts` — the layout, shared with the account distribution chart
- `frontend/src/pages/analysis/BreakdownChart.tsx`, `SliceContributors.tsx`
- `frontend/src/pages/analysis/DiversificationSection.tsx`, `frontend/src/lib/chart-palette.ts`
- `frontend/src/pages/analysis/HoldingClassificationModal.tsx` — the editor, shared by both entry points

### Flow

```
GET /api/analysis/diversification
  └─ PortfolioDiversificationService
       ├─ readable EQUITY accounts → lines → weigh once → value by ticker
       ├─ SecurityProfileService.load(tickers)          one query, no network
       └─ per ticker: override ▸ ETF slices (applied literally) ▸ share profile ▸ unclassified
            └─ N_eff per axis, coverage + the unplaced lines reported

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
| Renormalise a partial look-through | A top-ten list does not sum to 100; treating it as absolute would mark every fund partly unclassified | Absolute percentages |
| Inverse Herfindahl | Distinguishes 20/20/20/20/20 from 96/1/1/1/1, which counting buckets cannot | Number of distinct sectors |
| Coverage stated, never renormalised | A score over 60% of a portfolio must not read as a score over all of it | Renormalising to the classified part silently |
| A provider's undisclosed remainder counts as unclassified | Inflating the disclosed parts to cover the rest invents a distribution we do not have | Renormalising to the published total |
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
- **A duplicate label from a provider loses the line, not the security.** The unique key is
  `(profile, kind, label)`, so `SecurityProfileService` de-duplicates before saving rather than
  letting one repeated slice fail the whole save.
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
- `PortfolioDiversificationServiceTest` (13) — share vs ETF placement, partial look-through
  renormalised, override per field, unclassified reported not renormalised, same ticker twice,
  shares applied once, `N_eff` on concentrated vs even portfolios, `EXPOSURE` vs `MIXED`
- `DiversificationSection.test.tsx`, `e2e/analysis.spec.ts`

## Links

- ADR: [Domicile vs exposure](../decisions/2026-08-13-equity-domicile-vs-etf-exposure.md)
- ADR: [Persisted security profiles](../decisions/2026-08-13-persisted-security-profiles.md)
- Related: [Security Insight](./security-insight.md) — the per-ETF composition this builds on
- Related: [Wealth pyramid](./wealth-pyramid.md) — the same Analysis page, and the same override row
