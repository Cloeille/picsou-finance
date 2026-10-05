# ADR: Verify an ISIN's ticker against Yahoo instead of predicting it

> Date: 2026-08-10
> Status: ✅ Active

## Context

A user reported a PEA displaying **0 €**, graph included, right after an update — every position
entered manually by ISIN ([GH issue #74](https://github.com/Cloeille/picsou-finance/issues/74)).

An account that holds positions has no stored value: `AccountService.valuation()` recomputes it
from each holding's ticker, and a holding it cannot price is excluded from the total (and from the
cost basis — [ADR 2026-08-01](./2026-08-01-last-known-price-fallback.md)). Excluding *every*
holding leaves the account's cash balance, which on a manual account is nothing. Zero.

The tickers come from `OpenFigiIsinConverter`, which asks OpenFIGI for an ISIN's listings and picks
one by exchange priority: home exchange, then US OTC/ADR, then EU, then anything known. OpenFIGI
answers with *every* listing of the instrument. It has no idea which of them Yahoo quotes, and
neither does the priority order:

| ISIN | OpenFIGI pick | Yahoo |
|------|---------------|-------|
| `IE000BI8OT95` (Amundi Core MSCI World) | `MWRDF` (US OTC) | **404, delisted** |
| `IE00BGSF1X88` (iShares $ Treasury 0-1yr) | `ISHUF` (US OTC) | 121.42 USD ✓ |
| `IE00BD6FTQ80` (Invesco Bloomberg Commodity) | `IBBCF` (US OTC) | 33.26 USD ✓ |

[Issue #78](https://github.com/Cloeille/picsou-finance/issues/78) records the attempt to fix this by
reordering: preferring EU exchanges for Irish/Luxembourg ISINs fixed the first row and broke the
other two. Which listing has a live quote varies per instrument, and no exchange code predicts it.
Irish and Luxembourg domiciles are where this concentrates — i.e. most UCITS ETFs, i.e. the normal
content of a French PEA.

The second half of the same failure: when OpenFIGI resolves nothing at all (down, or rate-limited
at 25 requests/min without an API key, which entering a portfolio in one sitting reaches easily),
`resolve()` falls back to the ISIN itself as the ticker. `YahooFinancePriceProvider.supports()`
rejects ISIN-shaped strings, so that holding can never be priced — and nothing ever re-resolves it.

## Decision

**Stop predicting which listing Yahoo quotes and ask it.**

1. `OpenFigiIsinConverter.priceable()` probes the OpenFIGI pick against Yahoo's chart endpoint. If
   Yahoo quotes it, it is kept — unchanged behaviour for every portfolio that works today.
2. If it does not, Yahoo's own search endpoint (`/v1/finance/search?q=<ISIN>`) is asked what *it*
   knows for that ISIN, and the first returned symbol that actually quotes wins.
3. If neither produces a quote, the OpenFIGI pick is returned unverified, exactly as before.
4. `IsinTickerRepairRunner` re-resolves manual transactions whose stored ticker is still a raw
   ISIN, then recomputes the holdings derived from them.

The OpenFIGI pick is only ever replaced on a **positive** quote for a different symbol, never on a
failure to quote the pick itself.

## Alternatives considered

### Reorder the exchange priority (tried, reverted 2026-08-05)

- **Pros**: offline, no new dependency, no extra request.
- **Cons**: measured on a live portfolio, it traded one broken holding for two. There is no
  ordering that is right for every instrument, because the question it answers ("which listing do
  we prefer") is not the question that matters ("which listing does Yahoo carry").

### A manual per-ISIN override table

- **Pros**: zero risk of new regressions; entirely under our control.
- **Cons**: someone has to maintain it, forever, for an open-ended set of instruments — and a
  self-hosted user hitting an unlisted ISIN has to wait for a release to get a value.

### Use Yahoo search as the primary resolver, drop OpenFIGI

- **Pros**: one request instead of two; Yahoo is the authority on what Yahoo quotes.
- **Cons**: it would rewrite tickers that work today — `IE00B4L5Y983` resolves to `IWDA.AS` (EUR,
  Amsterdam) via OpenFIGI and to `IWDA.L` (USD, London) via search. Both price correctly, but
  churning every holding to a different listing and quote currency is a large change to fix a
  narrow bug. OpenFIGI also returns the instrument's official name, where search returns a display
  label truncated to ~32 characters.

### Accept the gap and only fix the display

- **Pros**: no new coupling; a missing value is a safe failure mode (never wrong, just absent).
- **Cons**: does not answer issue #74. The account is worth what it is worth; the user wants the
  number, not a better-worded absence of it.

## Reasoning

The two providers know different things, and the bug was asking one of them the other's question.
OpenFIGI is an identifier registry: it maps an ISIN to every listing that exists. Yahoo is a quote
service: it knows which symbols *it* serves. Every heuristic in `pickBest()` was an attempt to
infer the second from the first, and issue #78 is the record of that inference failing on real
holdings in both directions.

Verification costs one HTTP request per newly resolved ISIN, on a write path that already makes an
OpenFIGI round-trip and caches its result for the process lifetime. It is not on any read path.

The repair pass needs no gate flag, and that is a property of the predicate rather than an
omission: the rows it rewrites are exactly those whose ticker is a raw ISIN, which `supports()`
rejects by construction. Such a row cannot be priced today, so rewriting it cannot make anything
worse; when resolution still fails, the row is left alone and retried on the next boot.

## Trade-offs accepted

- **Yahoo requests per newly resolved ISIN**, once per process (the converter caches by ISIN):

  | Case | Requests |
  |------|----------|
  | The OpenFIGI pick quotes (the common case) | 1 probe |
  | It does not | 1 probe + 1 search + up to 3 candidate probes = **5 max** |
  | OpenFIGI returned nothing | 1 search + up to 3 candidate probes = 4 max |

  Yahoo returns matches in relevance order, so a listing that is not in the first three is not the
  one being looked for; probing the whole `quotesCount=6` response would turn a miss into eight
  requests. A sync of 40 positions pays 40 probes once, against the one request per ticker every
  15 minutes the price refresh already makes. Nothing here is on a read path.

- **Time on the write path.** `resolve()` runs inside the transaction of a user saving a
  transaction or importing a CSV row, so a bound on the *number* of probes is not a bound on the
  time they take. The catalog calls get a 3-second timeout rather than the 10 seconds a price read
  gets — a price that fails to arrive leaves a holding with no value, a verification that fails to
  arrive costs nothing — and `priceable()` abandons verification after a 10-second budget per ISIN.
  Worst case per newly-seen ISIN: ~15s including the OpenFIGI call, against ~5s before this feature.
  A CSV import with N *distinct* unresolvable identifiers still multiplies that by N inside one
  transaction; that multiplication predates this change (OpenFIGI was already called per row) and
  is not addressed here.
- **A dependency from the ISIN converter to a quote source.** Held behind `SymbolCatalogPort`
  rather than the concrete adapter, so the converter states what it needs ("do you carry this
  symbol") instead of naming who provides it. Kept separate from `PriceProviderPort`: `searchSymbols`
  has no meaning for a provider keyed by coin id rather than by listing (CoinGecko), and a port
  every implementer must stub out is not an abstraction.
- **A degraded Yahoo degrades to the old behaviour, silently.** A rate-limited probe leaves the
  OpenFIGI pick in place, so an ISIN resolved during an outage can still be persisted with a dead
  ticker. The repair runner does not catch it either — its predicate is "is a raw ISIN", and a dead
  symbol is not one. What limits the blast radius is that resolution is cached per process, so the
  next restart re-resolves it correctly.
- **A repair is capped at 25 ISINs and 60 seconds per boot.** An `ApplicationRunner` runs before
  `ApplicationReadyEvent`, so the pass delays readiness by whatever it spends. Both limits are
  checked before starting an ISIN, so a pass can overrun its budget by at most the resolution
  already in flight (~15s). A very large stuck portfolio converges over several restarts rather
  than holding one up, and what is left over is logged.

  Where the next pass resumes is the part that matters: only an ISIN that *resolves* leaves the
  candidate list, and some never will (a bond, an unlisted fund). A fixed window from the front of
  a stably-ordered list would hand those the same slots on every boot and never reach anything
  behind them. The pass therefore stores its position in `app_setting` — runtime state, not schema,
  so no migration — and the next boot starts just after it, wrapping at the end.

## Consequences

- New `SymbolCatalogPort` (`hasQuote`, `searchSymbols`), implemented by
  `YahooFinancePriceProvider` alongside `PriceProviderPort`. Both operations are read-only and
  reuse the unauthenticated endpoints already in use; `fetchMeta()` is extracted so the price read,
  the instrument-type read and the probe share one request shape.
- `OpenFigiIsinConverter` takes `SymbolCatalogPort` as a second constructor dependency.
  `pickBest()` stays a pure offline heuristic and keeps its priority order — it now decides which
  listing is *preferred among those that work*, rather than which one is used.
- `IsinTickerRepairRunner` (`@Order(2)`) runs after `StartupSyncService` and before
  `PriceBackfillRunner`, so the history backfill requests repaired tickers rather than ISINs. It
  deletes the holdings keyed by the old ISIN before recomputing: `recomputeHoldings` rebuilds a
  holding for every ticker its transactions mention but leaves alone one whose ticker they no
  longer mention — correct for a synced account, and exactly what a rename creates otherwise.
- Each ISIN is applied in **its own transaction**, opened after its resolution returns
  (`TransactionTemplate`, not `@Transactional` on the pass): a half-applied repair is not
  retryable, because renamed rows no longer match the raw-ISIN query and nothing would find their
  stale holdings again. Rolling back restores the ISIN, which is what the next boot looks for.
- The repair queries carry **no `member_id` predicate**, unlike every request-scoped query. It is a
  maintenance pass with no caller and no member context, in the same family as
  `PriceFxCleanupRunner` (purges `price_snapshot` wholesale), `PriceBackfillRunner` and
  `SchedulerService.dailySnapshots` (iterate every member). A member loop here would iterate all
  members anyway and touch exactly the same rows; the isolation rule protects request paths, where
  a caller's identity decides what may be read.
- No schema change: the repair pass is keyed on data shape, not on a migration-provided flag.
- Synced accounts need no repair pass — their adapters call `resolve()` on every sync, so they pick
  up the verified ticker on their own.

## Links

- [ISIN → ticker conversion](../features/ISIN_TO_TICKER_CONVERSION.md)
- [ADR 2026-08-01 — last known price fallback](./2026-08-01-last-known-price-fallback.md)
- [ADR 2026-05-19 — Yahoo FX conversion](./2026-05-19-yahoo-fx-conversion.md)
- GH issues [#74](https://github.com/Cloeille/picsou-finance/issues/74),
  [#78](https://github.com/Cloeille/picsou-finance/issues/78)
