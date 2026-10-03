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
`LogoProviderPort.getLogoUrls` call, which reads `image` off `/coins/markets?ids=…`. The lookup is
gated on the same `TICKER_TO_ID` registry the crypto *prices* come from, so an equity ticker
resolves to nothing rather than to an unrelated coin that happens to share its symbol — the same
reason `quotesFor` resolves a `CRYPTO` account crypto-only.

The service depends on the **port**, not on `CoinGeckoPriceProvider`, per the ports & adapters
rule in [`../CLAUDE.md`](../CLAUDE.md) ("controllers/services never import adapters directly").
CoinGecko implements it beside `PriceProviderPort`; a second mark source would be a bean rather
than an edit to the service. `LogoProviderWiringTest` pins the seam, because a service that took
the concrete adapter again would compile and pass every other test in the suite.

**Nothing is stored.** A coin's image is a read-only attribute of the coin, not state Picsou
owns: the provider already serves it for free, so persisting it would buy a migration and a
lifecycle for a value that cannot go stale. The one-time-fetch argument that applies to equities
(issue #162) does not bite here, because a batched call is already O(1) requests per page.

The service caches in memory, and a resolved URL is trusted for 24h rather than
`PriceService`'s 15 minutes, because a coin's mark does not go stale: re-resolving it every quarter
of an hour would spend CoinGecko calls to learn nothing. A *miss* is remembered too — an unmapped
ticker is asked about once rather than on every render of a page that lists it — but on a much
shorter clock, 60s. The split is the load-bearing part. An absent key is indistinguishable from
"this coin has no logo", so it is also what a rate-limited or unreachable provider returns;
remembering it for 24h would turn one throttled page render into blank marks on every portfolio
page for the rest of the day. This is the same two-TTL shape `PriceService` uses, for the same
reason (`docs/features/price-service.md`).

The URLs point at `coin-images.coingecko.com` and are fetched by the browser, the same shape as
the Enable Banking institution logos Picsou already hotlinks (`bank-logos.md`, which considered
bundling and rejected it here for the same reason: one call and one cache beats shipping assets
for a set nobody can enumerate).

## Degradation

Every failure mode is decoration-only, by construction — `logoUrl` is null and the ticker stands:

- **Ticker not in the registry** (every equity, today) → null, no request.
- **Provider down, 5xx, timeout, 429** → logged, cooldown armed on a 429 (shared with the price
  path, so a paused provider is not asked for decoration while it is still serving prices), null
  returned, and the absence cached for a minute rather than a day. The price path is untouched: an
  NPE or parse defect in the new code is **rethrown**, not swallowed into "no logos" — the
  classifier that decides swallow-or-rethrow is shared with the price path
  (`CoinGeckoPriceProvider.isExpectedUpstreamFailure`), so the two routes cannot drift apart.
- **Image 404s in the browser** → `HoldingLogo` drops the `src` and shows an empty disc. Radix
  keeps a failed image mounted, so without that reset one failed request would leave the mark
  blank for the rest of the session.

`AccountService.logosFor` is called outside the stream in `getHoldings`, next to `quotesFor`.
Inside the lambda it would be one provider request per holding instead of one per page.

## Equities: deliberately not implemented

There is no logo source for equities or ETFs that Picsou can use without a scrape, and the
candidates were measured (issue #162). Rather than pick one inside a diff, the shape leaves room
for it: a new source implements `LogoProviderPort` beside CoinGecko and becomes a bean, and
`logosFor` stops being crypto-gated. The `logoUrl` field, the component and the DTO shape are
already the ones equities will need.

The one decision that changes with it is where the bytes live. A durable store means either a
Postgres column (survives a rebuild; adds a migration) or a new `picsou_data` volume on the
backend (currently no writable volume exists in `docker-compose.yml`), and the caching policy
would move from "in-memory 24h" to "fetch once per ticker, ever". That is the maintainer's call.

## Tests

- `CoinGeckoPriceProviderTest` — the failure contract on the new path: images re-keyed by ticker,
  an unmapped equity never reaching the network, a 200 with no image, a 429 arming the cooldown
  that the price path then reads, and a genuine bug propagating instead of degrading to "no logo".
- `CryptoLogoServiceTest` — batching, the two cache clocks, partial hits, empty/blank input. The
  clock is injected (`Clock`, the bean `PersistentSessionService` already uses) so a TTL is
  asserted by moving time rather than by sleeping.
- `AccountServiceTest` — a crypto holding carries its logo; an equity leaves `logoUrl` null and
  never reaches the crypto resolver.
- `LogoProviderWiringTest` — the service resolves through the port, and the port resolves to
  CoinGecko. The seam itself is what the equity work will touch, and it is invisible to a unit
  test of either class.
- `HoldingsTable.test.tsx` / `HoldingLogo.test.tsx` — the mark beside the ticker, an equity row on
  its ticker alone, recovery from a failed load, and the ticker appearing exactly once.

`frontend/src/test/stubImage.ts` holds the global `Image` stub Radix needs, shared by the tests
that drive an avatar's load outcome rather than copied into each.
