# Holding logos — crypto only

> Issue: [#162](https://github.com/Cloeille/picsou-finance/issues/162) — the equity half is still
> an open question there. This note covers what is implemented and why equities are not in it.

Shows a crypto asset's mark beside its ticker in the portfolio tables. Nothing else changes: the
ticker is still rendered, and it is what the row is still keyed and searchable by.

## What is shown, and where

`logoUrl` is a new nullable field on `HoldingResponse` and `ExchangePositionResponse`, so it
travels to every place those DTOs are already rendered. It is currently used in two components —
`HoldingsTable` (flat holdings) and `PositionsByProduct` (a crypto exchange's per-product
breakdown) — via a shared `HoldingLogo`.

`HoldingDetailModal` and `HoldingsCard` do not show it yet. `HoldingsCard` is a *portfolio line*
(an account, not a holding), so the identity on it is the account, which already has a logo from
`bank-logos.md`; adding a second mark there would be a decision about the page, not about this
feature.

## Resolution: one batched call, no storage

`CryptoLogoService.getLogoUrls` resolves a whole page's tickers in a single
`CoinGeckoPriceProvider.getLogoUrls` call, which reads `image` off `/coins/markets?ids=…`. The
lookup is gated on the same `TICKER_TO_ID` registry the crypto *prices* come from, so an equity
ticker resolves to nothing rather than to an unrelated coin that happens to share its symbol — the
same reason `quotesFor` resolves a `CRYPTO` account crypto-only.

**Nothing is stored.** A coin's image is a read-only attribute of the coin, not state Picsou
owns: the provider already serves it for free, so persisting it would buy a migration and a
lifecycle for a value that cannot go stale. The one-time-fetch argument that applies to equities
(issue #162) does not bite here, because a batched call is already O(1) requests per page.

The service caches in memory for 24h, including misses — a ticker CoinGecko has no image for is
asked about once per TTL rather than on every render. A 24h TTL rather than `PriceService`'s 15
minutes because the value is not time-sensitive.

The URLs point at `coin-images.coingecko.com` and are fetched by the browser, the same shape as
the Enable Banking institution logos Picsou already hotlinks (`bank-logos.md`, which considered
bundling and rejected it here for the same reason: one call and one cache beats shipping assets
for a set nobody can enumerate).

## Degradation

Every failure mode is decoration-only, by construction — `logoUrl` is null and the ticker stands:

- **Ticker not in the registry** (every equity, today) → null, no request.
- **Provider down, 5xx, timeout** → logged, cooldown armed on a 429 (shared with the price path,
  so a paused provider is not asked for decoration while it is still serving prices), null
  returned. The price path is untouched: an NPE or parse defect in the new code is **rethrown**,
  not swallowed into "no logos", matching `handleFetchFailure`.
- **Image 404s in the browser** → `HoldingLogo` drops the `src` and shows an empty disc. Radix
  keeps a failed image mounted, so without that reset one failed request would leave the mark
  blank for the rest of the session.

`AccountService.logosFor` is called outside the stream in `getHoldings`, next to `quotesFor`.
Inside the lambda it would be one provider request per holding instead of one per page.

## Equities: deliberately not implemented

There is no logo source for equities or ETFs that Picsou can use without a scrape, and the
candidates were measured (issue #162). Rather than pick one inside a diff, the shape leaves room
for it: when a source lands, it belongs behind `CryptoLogoService`'s method — or a sibling — and
`logosFor` stops being crypto-gated. The `logoUrl` field, the component and the DTO shape are
already the ones equities will need.

The one decision that changes with it is where the bytes live. A durable store means either a
Postgres column (survives a rebuild; adds a migration) or a new `picsou_data` volume on the
backend (currently no writable volume exists in `docker-compose.yml`), and the caching policy
would move from "in-memory 24h" to "fetch once per ticker, ever". That is the maintainer's call.

## Tests

- `CryptoLogoServiceTest` — batching, the negative cache, partial hits, empty/blank input.
- `AccountServiceTest` — a crypto holding carries its logo; an equity leaves `logoUrl` null and
  never reaches the crypto resolver.
- `HoldingLogo.test.tsx` — image shown, empty fallback, recovery from a failed load.
