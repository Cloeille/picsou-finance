# ADR: Daily NAV source for unlisted funds, by ISIN

> Date: 2026-11-22
> Status: 🟡 Proposed

## Context

Epic #698 prices every holding with an ISIN by its ISIN. The two sources of #700, justETF and
Yahoo on the ISIN's reference listing, cover listed shares and ETFs. They have nothing for
unlisted funds (OPCVM with one NAV a day): justETF answers `latestQuote: null` for
`FR0010135103` (Carmignac Patrimoine A EUR Acc). Until a source exists, those lines take the
bank's own value (#701), which only moves when the account syncs. This is spike #704.

## Decision

Use **Boursorama** as the third ISIN source, after justETF and Yahoo. Two unauthenticated
calls, both checked live on 2026-11-22:

1. `GET https://www.boursorama.com/recherche/ajax?query=<ISIN>&searchId=` returns an HTML
   fragment. Its first result links to `/bourse/<kind>/cours/<symbol>/` and shows the last
   price. For `FR0010135103` that is `/bourse/opcvm/cours/MP-829413/`, at 827,89 €.
2. `GET https://www.boursorama.com/bourse/action/graph/ws/GetTicksEOD?symbol=<symbol>&length=30&period=0&guid=`
   returns JSON: `{"d":{"Name":"Carmignac Patrimoine A EUR Acc","SymbolId":"MP-829413","QuoteTab":[{"d":20748,"c":858.82,…}]}}`.
   `d` is a day count since 1970-01-01 (20748 = 2026-10-22), and `c` is the close. `length` is
   the window in days, and `length=1` returns `[]`, so the quote is read as the last point of a
   short window.

The same pair answers for listed lines (`LU1829219390` → `1rTBNKE`, 4,41 €), but those
already have two sources. Boursorama is only asked when both have no price.

Rules carried over from #700:
- The symbol must come from the ISIN search, never from the holding's ticker.
- Only a result whose search row is the queried ISIN is accepted. The search is fuzzy, and
  `/bourse/opcvm/recherche/?q=<ISIN>` returned unrelated funds.
- The quote must be in EUR (the row shows `€`). Anything else gets no price.
- Cache, timeout and a pause on 429 or 403, as for justETF.

`Boursorama` (`backend-rs/src/market/boursorama.rs`) already exists for ETF composition, so no
new host or dependency is added.

## Alternatives considered

### Morningstar
- **Pros**: broadest fund coverage, clean NAV data.
- **Cons**: the public screener API answers 401 without a key. No unauthenticated route found.

### Quantalys
- **Pros**: French fund coverage.
- **Cons**: the search requires JS and cookies (`This website requires JS enabled and cookies`).

### Financial Times fund tearsheet
- **Pros**: lookup by `ISIN:EUR`.
- **Cons**: 403 from a server client.

### Fund-house sites (Amundi, Carmignac…)
- **Pros**: the official NAV.
- **Cons**: one integration per house, each with its own page shape. Not maintainable here.

### Bank value only
- **Pros**: no new source, nothing to break.
- **Cons**: the line only moves on sync, and is flat between syncs on the daily and 24H charts.

## Reasoning

Boursorama is the only candidate that answers by ISIN, without a key or a browser, with a daily
history, for French unlisted funds. It is already a dependency of the app.

## Trade-offs accepted

- Undocumented endpoints, as for justETF. A layout change in the search fragment breaks the
  symbol lookup. The lines then fall back to the bank value, as today.
- Coverage outside French-distributed funds is unknown.
- The `d` day count is inferred from two points (20748 = 2026-10-22, 20752 = 2026-10-26, with a
  weekend between). The adapter test pins it on a captured payload.

## Follow-up

The adapter (`BoursoramaIsinPriceSource`, ordered third in `IsinPriceService` after justETF and
Yahoo) is its own PR, once #700 (#712) has landed: it implements the `IsinPriceSource` trait
proposed in `backend-rs/src/market/isin_prices.rs`.

Refs #704, #698
