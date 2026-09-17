# Feature: Security Insight (asset type + ETF composition)

> Last updated: 2026-08-13

## Context

The holding detail modal shows an **Insight** section: the asset type of a security
(ETF / stock / crypto) and, for ETFs, its composition by companies, countries, and
sectors. It answers "what am I actually holding?" without leaving the dashboard.

## How it works

The frontend opens the modal and calls `GET /api/securities/{ticker}/insight?name=…`.
The backend classifies the asset type, and for ETFs resolves the composition from a
single external provider (Boursorama), then caches the result in memory for a few days.

- **Type detection** — `SecurityInsightService.classify()`: crypto if the ticker is
  known to `CoinGeckoPriceProvider`; otherwise map Yahoo `meta.instrumentType`
  (`ETF`/`MUTUALFUND` → `ETF`, `EQUITY` → `STOCK`, `CRYPTOCURRENCY` → `CRYPTO`, else
  `UNKNOWN`). The `instrumentType` comes from the same unauthenticated Yahoo `chart`
  endpoint already used for prices.
- **Composition** — the service iterates the registered `EtfCompositionProvider`s; the
  first whose `supports(ticker, name)` matches and whose `fetch(...)` returns a
  composition with **any** data wins. The provider returns a **pre-aggregated**
  `EtfComposition` (companies / countries / sectors, each a descending list of
  `WeightedSlice`); the backend no longer aggregates raw holdings itself.
- **Cache** — `ConcurrentHashMap<uppercased-ticker, CachedInsight>` with a 3-day TTL
  (longer than `PriceService`, since composition changes slowly). `clearCache()` drops
  it; it is also lost on restart.

### The Boursorama provider

`BoursoramaCompositionProvider` is the only composition adapter. It resolves a security
by **ticker** and parses all three breakdowns from Boursorama's public, server-rendered
tracker pages — no authentication, no JS execution. Two steps:

1. **Symbol resolution** — `GET /recherche/?query={bareTicker}` returns a `302` whose
   `Location: /cours/{SYMBOL}/` header carries Boursorama's internal symbol
   (e.g. `NQSE` → `1zNQSE`). The redirect is **not** followed; the `Location` is read
   directly. The exchange suffix is stripped first (`PUST.PA` → `PUST`).
2. **Composition fetch + parse** — `GET /bourse/trackers/cours/composition/{SYMBOL}/`
   (with `/bourse/opcvm/cours/composition/{SYMBOL}/` as a fallback for funds). The first
   page that actually contains `amChartData` is parsed:
   - **Countries** ← inline amCharts JSON block with `"id":"regional"`, field
     `amChartData`: `[{"name":"Etats-Unis","value":97.25}, …]`.
   - **Sectors** ← inline amCharts JSON block with `"id":"sector"`.
   - **Companies** ← HTML table `c-table-gauge`: the header cell is the holding name,
     `data-gauge-current-step` is the weight %. Top 10 rows; swap lines filtered.
   - **asOf** ← `"Date du portefeuille : DD/MM/YYYY"`; **source** = `"Boursorama"`.

Anything that goes wrong (no symbol, no page, malformed JSON) is swallowed and surfaces
upstream as "composition unavailable" — the provider never throws.

### Label i18n (sectors + countries translated)

Boursorama's labels are French. To keep the API locale-agnostic:

- The **backend normalises** French labels to **stable keys** in `WeightedSlice.label`
  via `BoursoramaLabels.sectorKey()` / `countryKey()` (e.g. `"Technologie"` →
  `technology`, `"Etats-Unis"` → `US`). An **unmapped** label is passed through
  **verbatim** (the raw French string), so a slice is never blank.
- The **frontend translates** those keys through react-i18next under
  `holdings.insight.sectorNames.*` and `holdings.insight.countryNames.*`, with the
  received value as the **fallback** when no key exists (so passthrough French still
  renders). **Company names are real names, not keys**, and are always rendered verbatim.

### Key files

Backend:
- `backend/src/main/java/com/picsou/controller/SecurityController.java` — `GET /api/securities/{ticker}/insight`, optional `name` query param. Not member-scoped (market data, like `PriceController`).
- `backend/src/main/java/com/picsou/service/SecurityInsightService.java` — type classification, provider orchestration, in-memory cache. No aggregation (providers return pre-aggregated data).
- `backend/src/main/java/com/picsou/port/EtfCompositionProvider.java` — port: `supports(ticker, name)` + `fetch(ticker, name) → Optional<EtfComposition>`.
- `backend/src/main/java/com/picsou/adapter/BoursoramaCompositionProvider.java` — **the** composition adapter (symbol resolution → composition page → parse). WebClient with `followRedirect(false)` and a 16 MB in-memory limit.
- `backend/src/main/java/com/picsou/adapter/BoursoramaLabels.java` — FR→key maps for ~11 sectors and the common countries; accent-insensitive normalisation; verbatim passthrough on miss.
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — `getInstrumentType(ticker)` + `instrumentType` on the `Meta` record.
- `backend/src/main/java/com/picsou/dto/SecurityInsightResponse.java`, `backend/src/main/java/com/picsou/dto/EtfComposition.java` (adds `source` + `asOf`), `backend/src/main/java/com/picsou/dto/WeightedSlice.java`.

Frontend:
- `frontend/src/components/ui/partition-bar.tsx` — partition-bar primitive (proportional flex segments).
- `frontend/src/components/shared/HoldingInsightSection.tsx` — type badge + three breakdowns (Companies / Countries / Sectors), with a **view toggle** (segmented control, default **line**) switching all three between two renderings of the same `PartitionBar`:
  - **block** — labelled segments *inside* one proportional bar (variant-cycled colours; rich on a wide screen).
  - **line** — a slim colour-only bar above a **wrapping legend** (swatch + label + %); labels are *not* inside the segments, so it stays readable on a phone regardless of slice count.

  Both share the `Others` remainder; both translate country/sector keys (`labelNs`) and render company names verbatim. A `source · asOf` footnote sits below. View state is component-local (resets when the modal closes).
- `frontend/src/components/shared/HoldingDetailModal.tsx` — renders `<HoldingInsightSection>` after the stats grid; gated on the modal being open.
- `frontend/src/features/accounts/api.ts` (`securityInsight`) and `frontend/src/features/accounts/hooks.ts` (`useSecurityInsight`).
- `frontend/src/i18n/locales/{en,fr}.json` — `holdings.insight.sectorNames` + `holdings.insight.countryNames` key maps.
- `frontend/src/demo/index.ts` — mock handlers for the demo holdings (stocks, crypto, and two ETFs whose countries/sectors use the same keys, `source: 'Boursorama'`).

### Flow

```
HoldingDetailModal (open)
  → useSecurityInsight(ticker, name)
    → GET /api/securities/{ticker}/insight?name=…
       → SecurityInsightService.getInsight()
          ├─ cache hit (by uppercased ticker)? → return
          ├─ classify(): CoinGecko? → CRYPTO | Yahoo instrumentType → ETF/STOCK/CRYPTO/UNKNOWN
          └─ if ETF: first supporting EtfCompositionProvider.fetch() → pre-aggregated EtfComposition
                      └─ Boursorama: /recherche → 302 /cours/{SYMBOL}/ → composition page → parse
       → cache + return SecurityInsightResponse
  → HoldingInsightSection: type badge (+ 3 partition bars for ETF, Others remainder, source/asOf)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Yahoo `chart` for instrument type | Already called for prices, no auth | Yahoo `quoteSummary` (needs cookie + crumb) |
| Single Boursorama provider for composition | One source covers companies + countries + sectors, incl. country breakdown no free Yahoo endpoint exposes; same vendor already used for sync; server-rendered (no auth/crumb); works for synthetic ETFs | Per-issuer holdings files (broke in practice); Yahoo `quoteSummary` (no country, needs crumb, empty for synthetics) |
| Resolve by ticker, not fund name | The synced `name` is often just the ticker; ticker resolution sidesteps the issuer-name matching problem entirely | Issuer-name matching (the previous design's fatal flaw) |
| Provider returns pre-aggregated data | Boursorama is already aggregated; no backend grouping needed | Backend aggregating raw holdings |
| Backend normalises FR labels to keys; frontend translates | Locale-agnostic API; unchanged `WeightedSlice` shape | Translating in the backend; shipping raw French to the client |
| In-memory cache, 3-day TTL | Composition changes slowly; matches `PriceService` style | Persisting to DB |
| Frontend adds `Others` remainder | Bars stay honest about coverage (top-10 companies don't sum to 100) | Backend fabricating a 100% total |
| Block/line view toggle, default **line** | Block (labels inside segments) reads richly on desktop but truncates to nothing for <10px slices on a phone; line (colour bar + legend) stays legible at any width — so line is the mobile-safe default and block is opt-in | Single fixed layout (block alone broke on mobile; line alone dropped the denser desktop view) |

## Gotchas / Pitfalls

- **`name` is now vestigial.** The Boursorama provider resolves purely by ticker;
  `supports()` accepts any non-blank ticker and `fetch()` ignores `name`. The
  `/insight?name=…` query param and the port's `name` argument are retained for API
  stability and possible future providers, but nothing consumes `name` today. (The cache
  therefore keys on ticker alone, which is correct given `name` is unused.)
- **Bare-ticker resolution can, rarely, collide.** The exchange suffix is stripped before
  searching, so a bare ticker shared by two instruments could resolve to the wrong one.
  This fails soft (wrong-or-no composition → "unavailable"); ISIN-based disambiguation is
  a noted future enhancement (ISINs already arrive from sync + `OpenFigiIsinConverter`).
- **Synthetic ETFs have no company breakdown anywhere.** Their holdings table lists only
  the swap line (`TRS …` / `SWAP …`), which is filtered out, so companies is empty while
  countries + sectors (the index look-through) are still shown. This is **not**
  "unavailable".
- **"Unavailable" means all three breakdowns are empty** — typically when Boursorama has
  no composition page for the security. The frontend renders only non-empty bars, and the
  "Composition unavailable" note shows only for an ETF with a null composition.
- **French labels are normalised to keys**; unmapped sectors/countries fall back to the
  raw French string (never blank). Adding a country/sector means adding the key to both
  `BoursoramaLabels` and the two i18n locale files.
- **The amChart regex is greedy-safe but bracket-fragile**: the `[^\]]*` capture truncates
  the JSON array if a label contains a literal `]`; `readTree()` then throws and that
  breakdown comes back empty (fail-soft, acceptable).
- **The query is gated on the modal being open** (`enabled`), so it doesn't fire for every
  rendered (but closed) modal.
- **Both views are the same `PartitionBar` primitive**, differing only in presentation —
  `BlockView` puts labels *inside* variant-cycled segments; `LineView` makes the bar
  colour-only and moves labels to a wrapping legend. The shared `CompositionBar` computes
  `labelOf` + the `Others` remainder once, so the two views can never disagree on the math.
  The toggle drives all three breakdowns together via one component-local `view` state
  (resets on modal close — per [ADR 2026-04-05](../decisions/2026-04-05-component-local-state-for-ui-filters.md)).
- **Demo mode keys on the exact path** (`GET /securities/{ticker}/insight`, query
  stripped); unmatched tickers fall through to `{}`, which the UI treats as "no insight"
  and renders nothing.

## Tests

- `BoursoramaLabelsTest` — FR→key mapping for sectors and countries, accent-insensitive
  normalisation, verbatim passthrough on an unmapped label.
- `BoursoramaCompositionProviderTest` — parsing against saved HTML fixtures: a physical
  ETF (NQSE — companies + countries + sectors + `asOf`) and a synthetic ETF (PUST — empty
  companies, countries + sectors present); symbol parsing from a redirect `Location`;
  exchange-suffix stripping; empty HTML → empty composition; non-numeric slice value
  skipped.
- `SecurityInsightServiceTest` — crypto/equity/unknown classification, ETF composition via
  the provider, null composition when no provider resolves data, non-ETF skips the
  provider, caching.
- `HoldingInsightSection.test.tsx` — three bars from a mock ETF composition, the `Others`
  remainder, country/sector key translation (with verbatim fallback), the **block/line view
  toggle** (both views render the three bars + labels), stock fallback (badge only),
  unavailable ETF note, empty-response no-render, loading spinner.

## What builds on this

The portfolio-wide breakdown ([portfolio-diversification.md](./portfolio-diversification.md), 2026-08-13)
reuses this pipeline and adds two things it deliberately does not have:

- **Persistence.** The `ConcurrentHashMap` here is right for one user-initiated lookup and wrong
  for N of them on a page render, so `security_profile` stores what gets resolved and
  `SchedulerService` warms it weekly. This service keeps its own cache; merging the two is a
  separate change.
- **Equity coverage.** A `STOCK` gets no composition here. `EquityProfileProvider` answers its
  sector (Yahoo search) and country (the ISIN on the Boursorama quote page), merged field by
  field — deliberately unlike `resolveComposition`, which stops at the first provider with data.

`BoursoramaCompositionProvider` no longer owns its `WebClient`: symbol resolution moved to
`BoursoramaClient`, shared with the equity provider so a change at Boursorama is one repair.

## Links

- Related ADR: [ETF composition via Boursorama (single source)](../decisions/2026-06-01-etf-composition-via-boursorama.md)
- Superseded ADR: [ETF composition from issuer holdings files](../decisions/2026-05-31-etf-composition-issuer-holdings.md)
- Related: [Price service](./price-service.md), [Live prices (holdings)](./live-prices-holdings.md)
