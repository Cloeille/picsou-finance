# Feature: ISIN to Yahoo Finance Ticker Conversion

> Last updated: 2026-08-10

## Context

Trade Republic returns account holdings with ISIN codes (e.g., `IE00BYVQ9F29`), but Yahoo Finance expects ticker symbols (e.g., `IWDA.AS`). This feature converts ISINs to Yahoo-compatible tickers via the OpenFIGI API, and also resolves the display name (e.g., "ISHARES CORE MSCI WORLD") for the frontend.

OpenFIGI answers with *every* listing of an instrument, not with the one Yahoo quotes, so the pick is **verified against Yahoo** before it is returned — see [Verification](#verification-the-pick-is-checked-against-yahoo) and [ADR 2026-08-10](../decisions/2026-08-10-yahoo-verified-isin-tickers.md). A ticker that cannot be priced is not a cosmetic problem: `AccountService.valuation()` drops an unpriceable holding from the account total, so an all-ETF account where every ISIN resolved badly displays **0 €** (GH issue #74).

The converter is shared by several callers: the **Trade Republic sync** (its original use), **manual transaction entry** (a user can type an ISIN instead of a ticker in the *Add transaction* form — see [manual-transactions.md](./manual-transactions.md)), the **CSV importer**, **Bourso**, and **DEGIRO** sync. All resolve at write time so an ISIN entry and the equivalent ticker entry collapse into one position.

## How it works

### Key files

- `backend/src/main/java/com/picsou/adapter/OpenFigiIsinConverter.java` — ISIN→ticker+name conversion via OpenFIGI `/v3/mapping` API; also exposes `public static boolean isIsin(String)`, the 12-char ISIN detector reused by callers to decide whether to resolve
- `backend/src/main/java/com/picsou/service/TradeRepublicSyncService.java` — calls `resolve()` during sync, stores ticker and name
- `backend/src/main/java/com/picsou/service/ManualTransactionService.java` — calls `isIsin()` + `resolve()` when a user enters an instrument by ISIN in the *Add transaction* form (`applyInstrumentFields`)
- `backend/src/main/java/com/picsou/service/DegiroSyncService.java` — calls `resolve()` for each DEGIRO position's ISIN during sync
- `backend/src/main/java/com/picsou/port/SymbolCatalogPort.java` — "do you carry this symbol, and what do you know for this identifier", the contract the resolved ticker is verified against
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — rejects unconvertible ISINs via regex in `supports()`; implements `SymbolCatalogPort` (`hasQuote()` probes a candidate symbol, `searchSymbols()` asks Yahoo what *it* knows for an ISIN)
- `backend/src/main/java/com/picsou/config/IsinTickerRepairRunner.java` — startup pass that re-resolves manual transactions still carrying a raw ISIN as their ticker
- `backend/src/main/java/com/picsou/service/DegiroSyncService.java` and `BoursoSyncService.java` — call `resolveIsinOrSymbol(isin, symbol, providerName)` for each position: brokers ship positions with or without an ISIN, so the shared helper prefers the ISIN (it prices through Yahoo), falls back to the broker's own symbol, and keeps the broker label unless OpenFIGI knows a better name
- `backend/src/main/java/com/picsou/adapter/YahooFinancePriceProvider.java` — rejects unconvertible ISINs via regex in `supports()`
- `frontend/src/components/shared/HoldingsCard.tsx` — displays name in title, ticker in square badge

### Flow

```
TR WebSocket → TrPosition(isin)
    ↓
TradeRepublicSyncService.upsertAccount()
    ↓
openFigiIsinConverter.resolve(isin)
    ↓
POST /v3/mapping  body: [{"idType":"ID_ISIN","idValue":"IE00BYVQ9F29"}]
    ↓
OpenFIGI returns array of results with ticker + exchCode + name
    ↓
pickBest() selects best exchange → composes ticker + Yahoo suffix
    ↓
priceable() → Yahoo quotes it?  ── yes ─→ keep it
    │
    no → GET /v1/finance/search?q=<ISIN> → first symbol Yahoo quotes wins
         (none → keep the OpenFIGI pick unverified, as before)
    ↓
Returns TickerResult(ticker="IWDA.AS", name="ISHARES CORE MSCI WORLD")
    ↓
Stored as AccountHolding.ticker + AccountHolding.name
    ↓
Frontend: h.name ?? h.ticker → shows name, falls back to ticker
```

## Verification: the pick is checked against Yahoo

`pickBest()` answers "which listing do we prefer". Whether a holding gets a value at all depends on
a different question — "which listing does Yahoo carry" — and no exchange-code heuristic predicts
it (see Gotchas for the reordering that fixed one holding and broke two). `priceable()` therefore
verifies rather than guesses:

1. **Probe the pick.** `symbolCatalog.hasQuote(ticker)` — a chart request, no FX conversion (an unavailable
   EUR rate says nothing about whether the symbol exists). Quoted → returned unchanged, which is
   every portfolio that already works today: no ticker churn, no extra search.
2. **Ask Yahoo.** `symbolCatalog.searchSymbols(isin)` returns the symbols Yahoo indexes for that ISIN, in
   its own relevance order, filtered to `isYahooFinance` entries that `supports()` accepts. The
   first one that quotes wins. `enableFuzzyQuery=false`, so an ISIN Yahoo does not know returns
   nothing rather than a near-match.
3. **Give up safely.** Nothing quotable → the OpenFIGI pick is returned unverified (or the ISIN
   itself when OpenFIGI returned nothing), i.e. exactly the behaviour that predates this step.

The pick is only ever replaced on a **positive** quote for a different symbol, never on a failure
to quote the pick — a rate-limited Yahoo cannot turn a working ticker into a search result.

Cost, per ISIN and once per process (`resolve()` caches by ISIN): **1** Yahoo request when the pick
quotes, **5 at most** when it does not (probe + search + up to 3 candidate probes,
`MAX_VERIFIED_CANDIDATES`), 4 when OpenFIGI returned nothing. Matches come back in relevance order,
so a listing outside the first three is not the one being looked for.

The calls are also bounded in time, because `resolve()` runs on the **write path** — inside the
transaction of a user saving a transaction or importing a CSV row. The catalog calls use a 3-second
timeout rather than the 10 seconds a price read gets (a verification that does not arrive costs
nothing: the caller keeps the ticker it had), and `priceable()` gives up after a 10-second
`VERIFY_BUDGET` per ISIN. Worst case per newly-seen ISIN is therefore ~15s including the OpenFIGI
call, against ~5s before this feature and ~55s with the price-read timeout.

Verified live on the three holdings of issue #78:

| ISIN | OpenFIGI pick | Verified result |
|------|---------------|-----------------|
| `IE000BI8OT95` | `MWRDF` — 404, delisted | `MWRD.PA` (161.68 EUR) |
| `IE00BGSF1X88` | `ISHUF` — 121.42 USD | `ISHUF`, kept |
| `IE00BD6FTQ80` | `IBBCF` — 33.26 USD | `IBBCF`, kept |

### Repairing rows already stored with an ISIN

`resolve()` falls back to the ISIN itself when nothing resolves, and that fallback is persisted on
the transaction — where nothing ever revisits it, since `supports()` rejects ISIN-shaped strings.
`IsinTickerRepairRunner` re-resolves those rows at startup and recomputes the holdings derived from
them. It is scoped to **manual transactions of manual accounts** (a synced account re-resolves on
its next sync) and needs no gate flag: a raw-ISIN ticker cannot be priced by construction, so
rewriting it can only improve it. Unresolvable rows are left alone and retried on the next boot.

The pass is bounded twice — **25 ISINs** (OpenFIGI's keyless quota) and **60 seconds** — because an
`ApplicationRunner` runs before `ApplicationReadyEvent`, so whatever it spends is time the
application is not serving. Both are checked before starting an ISIN, so it can overrun by at most
the resolution in flight (~15s).

It resumes where it stopped, via a cursor in `app_setting` (runtime state, no migration). That is
load-bearing rather than cosmetic: only an ISIN that *resolves* leaves the candidate list, so a
fixed window from the front would hand permanently-unresolvable identifiers — a bond, an unlisted
fund — the same slots on every boot and never reach anything behind them. Each ISIN is applied in its own
transaction, opened once its resolution has returned: a half-applied repair is not retryable, since
renamed rows no longer match the raw-ISIN query.

It deletes the holdings keyed by the old ISIN before recomputing, and that order matters:
`HoldingComputeService.recomputeHoldings` rebuilds a holding for every ticker its transactions
mention but deliberately leaves one alone when no transaction mentions its ticker at all (a synced
account owns holdings no transaction backs). A rename turns the old key into exactly that kind of
orphan, so the account would otherwise carry both the repaired position and an unpriceable
duplicate — listed with a quantity and no value.

## TR-native crypto ISIN short-circuit

Trade Republic's on-platform crypto (Bitcoin, Ethereum, etc. held directly, not via an ETC) uses internal ISINs of the form `XF000<SYMBOL><digits>` (e.g. `XF000BTC0017`, `XF000SOL0042`) that are not real market instruments — OpenFIGI never resolves them. `resolve()` checks the cache, then detects this pattern before the OpenFIGI call, parses `<SYMBOL>` out generically (not a hardcoded per-coin list — see GH issue #22), and validates it against the injected `CoinGeckoPriceProvider.supports()`. If known it returns `TickerResult(symbol, coinGecko.displayName(symbol))` and caches it. Returning the parsed symbol as the **ticker** (not just the name) is what makes the holding price-resolvable via `CoinGeckoPriceProvider` afterwards — the earlier version only fixed the display name and left the ticker as the fake ISIN. An unrecognized symbol logs one warning and falls through to the normal OpenFIGI path (which will still miss, same as before this feature); because that miss is cached, the warning fires once per holding, not on every `resolve()`.

Both the "is this a known crypto?" check and the display name come from `CoinGeckoPriceProvider`'s single `TICKER_TO_ID` registry (`displayName()` title-cases the CoinGecko coin id, so `MATIC` → "Matic Network") — there is no second per-coin map to keep in sync, and every known coin gets a real name, not just BTC/ETH. The converter takes `CoinGeckoPriceProvider` as a constructor dependency rather than calling a static method, so it stays consistent with whatever the price provider actually supports.

`resolve()` normalizes the input (`trim().toUpperCase(Locale.ROOT)`) once at the top and reuses that value for the cache key, the crypto-symbol match, and the OpenFIGI fallback ticker — earlier the cache/fallback used the raw input, so case/whitespace variants of the same ISIN (or a real ISIN differing only by case) created duplicate cache entries and duplicate OpenFIGI calls. `Locale.ROOT` avoids the Turkish-locale `i`/`İ` hazard on `toUpperCase`.

The `XF000` marker itself lives in one place: `OpenFigiIsinConverter.isTrCryptoIsin()` (prefix-based, case-insensitive), which `TradeRepublicAdapter` also calls to route these holdings to TR's own exchange (`TRD0`). The two TR-crypto detection sites (the adapter's exchange choice and this converter's parse) share that predicate/prefix so they can't drift.

**Backfill:** because the resolved ticker is persisted on each `Transaction`, manual crypto transactions entered *before* this behavior existed carry the fake ISIN (`XF000BTC0017`) as their ticker and would no longer aggregate with new `BTC` rows (`HoldingComputeService` groups by exact ticker). Migration `V38__backfill_tr_crypto_transaction_tickers.sql` rewrites those historical rows to the resolved symbol for the crypto set known at that release; derived `account_holding` rows self-heal on the next recompute.

## Exchange selection logic

`pickBest()` selects the Yahoo Finance ticker from multiple OpenFIGI results:

1. **Home exchange** — based on ISIN country prefix (`US`→US, `HK`→HK, `DE`→GY, etc.)
2. **US OTC/ADR** — for non-US ISINs, US listings often have best Yahoo coverage
3. **EU exchanges** — NA (Amsterdam), FP (Paris), GY/GR (Germany), LN (London)
4. **Any known exchange** — fallback

OpenFIGI `exchCode` is mapped to Yahoo suffix (e.g., `GY`→`.DE`, `NA`→`.AS`, `FP`→`.PA`, `HK`→`.HK`).

A 2026-08-05 attempt swapped steps 2 and 3 for Irish/Luxembourg-domiciled ISINs
(see Gotchas) but was reverted the same day — it fixed one holding and broke
two others on the same live portfolio. The order above is the original,
restored one; since 2026-08-10 it is no longer the last word, because
`priceable()` verifies the result. `pickBest()` stays a pure offline heuristic:
it decides which listing is *preferred among those that work*.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| OpenFIGI `/v3/mapping` endpoint | Direct identifier lookup, returns structured results with exchCode and name | `/v3/search` (keyword-based, different request/response format) |
| `TickerResult` record (ticker + name) | Frontend needs display name; avoids a second API call | Separate name lookup endpoint |
| Home exchange preference by ISIN country | US stocks get US tickers (better Yahoo coverage), HK stocks get `.HK`, etc. | Always EU exchanges → `NVD.DE` for NVIDIA works but less reliable |
| In-memory `ConcurrentHashMap` cache | Avoids repeated API calls during bulk sync | Database caching (adds complexity for ephemeral data) |
| Sentinel-free caching (store `TickerResult` or null via `Map.get()`) | Clean null check | Sentinel string `"ISIN"` (used previously, more error-prone) |

## Gotchas / Pitfalls

- **Wrong endpoint = silent failure**. The old code used `/v3/search` with a malformed body. OpenFIGI returns `400` but WebClient doesn't throw — it returns null data. Always verify with `curl` against the API when debugging.
- **`Map.of()` has a 10-entry limit**. Use `Map.ofEntries()` for the exchange suffix maps (30+ entries).
- **`useMemo` before conditional return**. React hooks must not be after `if (!data) return`. In `DashboardPage`, the `historyForRange` memo must be computed before the early return.
- **Yahoo Finance rejects ISIN-format strings**. `YahooFinancePriceProvider.supports()` uses regex `[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]` to detect 12-char ISINs and returns false. Unconverted ISINs never get price data.
- **Deduplication aggregates by ticker**. Multiple ISINs mapping to the same ticker are merged in `TradeRepublicSyncService` via `Map.merge()`. The name from the first ISIN wins.
- **Some tickers may not exist on Yahoo Finance**. German-listed tickers like `6RJ0.DE` (internal Bloomberg ID) may not resolve. The home-exchange-first strategy mitigates this.
- **`HOME_EXCHANGE` has no entry for Ireland or Luxembourg** — where most UCITS
  ETFs are legally domiciled, even though they primarily trade on other European
  exchanges (Paris, Amsterdam, Frankfurt...). Confirmed live: an Irish-domiciled
  ETF (`IE000BI8OT95`, Amundi Core MSCI World) resolves to the US OTC ticker
  `MWRDF`, which has no live Yahoo quote — `YahooFinancePriceProvider` then
  can't price it, and `AccountService.toHoldingResponse` (per the
  FX-conversion ADR) doesn't fall back to a stored price when the live one is
  missing, so the holding's value drops out of the dashboard total (shows as
  a missing "Valeur" rather than a wrong one).
  **Tried and reverted the same day**: swapping the priority to try EU
  exchanges before US OTC/ADR for ISINs with no home exchange. That did fix
  `MWRD` (→ `WRDU.AS`, live on Yahoo) but broke two *other* real holdings on
  the same test portfolio: `IE00BGSF1X88` and `IE00BD6FTQ80` resolved to
  `IB01.AS` / `SC0L.DE`, both confirmed delisted/no-data on Yahoo, while their
  original US OTC tickers (`ISHUF`, `IBBCF`) are live and priced. There is no
  exchange-code heuristic that reliably predicts which specific ticker variant
  has a live Yahoo quote for a given Irish/Luxembourg ISIN — it varies per
  instrument. Adding `IE`/`LU` to `HOME_EXCHANGE` wouldn't help either, for the
  same reason.
  **Resolved differently (2026-08-10)**: rather than a better guess, the pick is
  now verified against Yahoo and falls back to Yahoo's own search for the ISIN —
  see [Verification](#verification-the-pick-is-checked-against-yahoo). The
  ordering above is unchanged and its tests still lock it in; what changed is
  that a dead pick no longer reaches the holding. The gap this bullet used to
  describe as accepted is closed (GH issues #74, #78).
- **A missing ticker costs the whole position, not just its own row.**
  `AccountService.valuation()` excludes a holding it cannot price from the
  account total *and* from the cost basis (ADR 2026-08-01). That is the right
  call for one holding out of ten; when every holding of an account is
  unpriceable there is nothing left, and a manual account (no `cash_balance`,
  no provider total to fall back on) displays `0 €` with a flat graph. Any
  change to ISIN resolution should be read with that amplification in mind.
- **OpenFIGI's `ticker` field is not always a symbol**. For bonds it holds the Bloomberg *description*: querying `XS2657412201` (airBaltic 14.5% 2029) returns `ticker: "AIRBAL 14.5 08/14/29 REGS"` on `exchCode: "EURONEXT-DUBLIN"`, which is absent from `EXCHANGE_SUFFIX` — so `byExchange` stayed empty and step 5 ("raw ticker from first entry") emitted that description verbatim as the holding's ticker. `pickBest()` now filters every candidate through `SYMBOL_PATTERN` (`[A-Z0-9][A-Z0-9.-]{0,14}`, checked before the exchange suffix is appended) and returns `null` when nothing plausible remains. `resolve()` then falls back to the ISIN, which `YahooFinancePriceProvider.supports()` already rejects — so an unmappable bond costs zero HTTP requests instead of one 404 per price lookup (GH issue #76).

## Tests

- `OpenFigiIsinConverterTest` — `priceable()`: keeps the OpenFIGI pick when Yahoo quotes it (and does *not* search, so a working portfolio pays nothing and its tickers don't churn), falls back to the first quotable search symbol when the pick is delisted, skips search symbols that don't quote either, keeps the pick when nothing quotes (a degraded Yahoo must never downgrade a result), never probes the same symbol twice, resolves from search when OpenFIGI returned nothing at all, and returns null when neither side knows the instrument.
- `YahooFinancePriceProviderTest` — `hasQuote()`: true on a price and with a single request (no FX call), false for a delisted symbol, false without any request for an ISIN or a non-symbol. `searchSymbols()`: Yahoo's relevance order preserved, `longname` preferred over `shortname`, `isYahooFinance: false` entries dropped, empty on an unknown ISIN / transport failure / blank query.
- `IsinTickerRepairRunnerTest` — rewrites raw-ISIN tickers and recomputes the derived holdings, preserves a user-typed name, leaves the row untouched (and recomputes nothing) when the ISIN still doesn't resolve, ignores 12-character tickers that aren't ISINs, stops at the per-boot limit, and never lets a failure keep the application from starting.
- `TransactionRepositoryTest` — the repair queries are scoped to manual rows of manual accounts, and the account lookup dedupes two buys of the same instrument into one recompute.
- `OpenFigiIsinConverterTest` — 15 unit tests: the `isIsin()` detector (valid ISINs, case/whitespace normalization, rejects tickers and non-ISIN strings, rejects null/blank), the TR-native crypto short-circuit (BTC/ETH ticker+name, a generic symbol (SOL) to prove it isn't hardcoded per coin, case/whitespace normalization consistency), `pickBest()`'s exchange-priority ordering (package-private for this — US-OTC-over-EU when no home exchange matches, EU fallback when no US OTC listing exists, home exchange still wins over both, any-known-exchange fallback), and `pickBest()`'s symbol filtering (rejects a bond *description* as a ticker, still resolves real symbols on known exchanges, returns the normalized symbol rather than the raw OpenFIGI value). The ordering tests lock in the *reverted* (original) order — see Gotchas for why the EU-first variant was tried and abandoned. The network-bound OpenFIGI *request* itself still has no unit test (WebClient mock setup is complex); callers that use `resolve()` end-to-end (`ManualTransactionServiceTest`) mock the converter instead.
- `OpenFigiIsinConverterTest` — 16 unit tests: `resolveIsinOrSymbol()`'s no-ISIN branch (broker symbol and label kept, no OpenFIGI call), the `isIsin()` detector (valid ISINs, case/whitespace normalization, rejects tickers and non-ISIN strings, rejects null/blank), the TR-native crypto short-circuit (BTC/ETH ticker+name, a generic symbol (SOL) to prove it isn't hardcoded per coin, case/whitespace normalization consistency), `pickBest()`'s exchange-priority ordering (package-private for this — US-OTC-over-EU when no home exchange matches, EU fallback when no US OTC listing exists, home exchange still wins over both, any-known-exchange fallback), and `pickBest()`'s symbol filtering (rejects a bond *description* as a ticker, still resolves real symbols on known exchanges, returns the normalized symbol rather than the raw OpenFIGI value). The ordering tests lock in the *reverted* (original) order — see Gotchas for why the EU-first variant was tried and abandoned. The network-bound OpenFIGI *request* itself still has no unit test (WebClient mock setup is complex); callers that use `resolve()` end-to-end (`ManualTransactionServiceTest`) mock the converter instead.
- Manual verification with `curl` against OpenFIGI API:
  - `US0378331005` (Apple) → `AAPL`
  - `IE00B4L5Y983` (iShares MSCI World) → `IWDA.AS`
  - `KYG9830T1067` (Xiaomi) → `1810.HK`
  - `DE0007100000` (Mercedes-Benz) → `MBG.DE`
- Backend tests: `mvn test` passes (`GoalServiceTest`)

## Links

- Related feature: [price-service.md](./price-service.md) (price lookups)
- Related feature: [trade-republic.md](./trade-republic.md) (TR sync)
- ADR: [2026-08-10 — Verify an ISIN's ticker against Yahoo instead of predicting it](../decisions/2026-08-10-yahoo-verified-isin-tickers.md)
- ADR: [2026-08-01 — Value assets from the last known price rather than not at all](../decisions/2026-08-01-last-known-price-fallback.md) (why an unpriceable holding drops out of the total)
