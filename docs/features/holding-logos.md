# Holding logos

> Issue: [#162](https://github.com/Cloeille/picsou-finance/issues/162). Crypto shipped first
> (#163); shares and ETFs follow, with the Yahoo quote-page source the maintainers accepted on
> the issue.

Shows an asset's mark beside its ticker in the portfolio tables. Nothing else changes: the
ticker is still rendered, and it is what the row is still keyed and searchable by. A holding
with no mark renders exactly as it did before logos existed.

## What is shown, and where

`HoldingResponse` carries `logoUrl` and `logoUrlDark` (both nullable);
`ExchangePositionResponse` carries `logoUrl` only, since exchange positions are crypto. A
shared `HoldingLogo` renders them in:

- `HoldingsTable` (an account's holdings) and `PositionsByProduct` (a crypto exchange's
  per-product breakdown): the mark, or an empty disc so the column does not jump.
- `HoldingDetailModal`: the mark beside the title, only when there is one. That header never
  had a disc, so an absent mark leaves it unchanged.

`HoldingsCard` does not show one. It is a *portfolio line* (an account, not a holding), so the
identity on it is the account, which already has a logo from `bank-logos.md`.

`logoUrlDark` is a mark drawn for a dark background, set only when the source has a distinct
one (Apple's is black on the light variant, white on the dark one). `HoldingLogo` picks it when
`<html>` carries `.dark`, via `useDarkTheme`, which follows the same class the CSS does.

## Crypto: one batched call, no storage

`CryptoLogoService.getLogoUrls` resolves a whole page's tickers in a single
`LogoProviderPort.getLogoUrls` call, which reads `image` off `/coins/markets?ids=…`. The lookup is
gated on the same `TICKER_TO_ID` registry the crypto *prices* come from, so an equity ticker
resolves to nothing rather than to an unrelated coin that happens to share its symbol. The same
reason `quotesFor` resolves a `CRYPTO` account crypto-only, and why `AccountService.logosFor`
never reads the share store for a `CRYPTO` account.

The service depends on the **port**, not on `CoinGeckoPriceProvider`, per the ports & adapters
rule in [`../CLAUDE.md`](../CLAUDE.md). `LogoProviderWiringTest` pins the seam.

**Nothing is stored.** A coin's image is a read-only attribute the provider already serves for
free, and a batched call is already O(1) requests per page. The service caches in memory: a
resolved URL for 24h, a miss for 60s (a rate-limited answer looks exactly like "no logo", so a
long negative TTL would blank every mark for a day after one throttled render). The URLs point
at `coin-images.coingecko.com` and are fetched by the browser, like the Enable Banking
institution logos.

## Shares and ETFs: read once, stored, served by Picsou

### Source

`finance.yahoo.com/quote/{symbol}/` embeds its API responses as
`<script type="application/json">` blocks. A quote object there has `symbol`, `logoUrl` and
`logoUrlDarkMode`, pointing at a 50px rendition on `s.yimg.com` of
`s.yimg.com/lg/logos/{ISIN}/{light|dark}/{hash}.png`. Measured live on AAPL, MSFT, MC.PA and
IWDA.AS (issue #162; the parser fixtures are trimmed copies of those pages).

The page also lists dozens of *other* companies with their own logos (trending tickers, "people
also watch"), and the first logo URL in the AAPL page belongs to a different company. So
`YahooQuotePageParser` never scans for a logo URL: it parses each JSON block (and the escaped
`body` inside it) and accepts only the object whose `symbol` is the one asked for. It answers
one of four things, and only the second is a permanent miss:

- **marked**: an object for the symbol with a usable `logoUrl`;
- **unmarked**: a *quote* object for the symbol (it carries `quoteType`) with no `logoUrl`, on a
  page that carries at least one usable mark for another symbol, so Yahoo has no mark
  (`ABSENT`). Both conditions matter. The page has other objects keyed by the symbol that never
  carry a logo (`recommendationsbysymbol`), and a quote page without a single usable mark means
  the field was renamed or moved, which would otherwise make every ticker look unmarked;
- **refused mark**: the symbol has a `logoUrl` this parser will not download (off `s.yimg.com`,
  not https). That is a CDN move, not a missing mark, and is `FAILED`;
- **not quoted**: anything else (no quote object for the symbol, or one on a page with no usable
  mark at all). That is a layout this parser no longer reads, or a consent or anti-bot page
  served with a 200, and says nothing about the mark.

Everything but unmarked is recorded as `FAILED` and retried after 7 days, so a later parser fix
picks every ticker up again instead of leaving the whole portfolio `ABSENT`. A layout change can
therefore make it find nothing for a while, which shows the ticker; it cannot make it store the
wrong company's mark.

The 50px rendition, not the original: the IWDA.AS original is 292 KB, its rendition 6 KB, and
the table draws a 24px mark (48px on a 2x screen).

### Validation

The page is untrusted input, and the bytes end up served from Picsou's own origin:

- the logo URL must be `https` on exactly `s.yimg.com` (no user-info, no other port);
- the bytes must carry a PNG, JPEG or WebP signature. The signature decides, not the header: an
  image served without a type, as `application/octet-stream`, `text/plain`, `text/html` or under
  the wrong image type is still that image. The stored type is the canonical one for the
  signature, so the upstream label never reaches the browser;
- SVG is refused outright, by its declared type, whatever the bytes: it is a document that can
  carry script;
- 256 KB cap per image, 4 MB cap on the page;
- redirects are not followed, so the page cannot steer a download to another host. A 3xx on the
  page or on an image is `FAILED`, not an empty answer: a redirect to `consent.yahoo.com` or to a
  normalised URL says nothing about the mark.

The client raises Reactor Netty's response-header limit to 64 KB. The quote page answers with
more than the default 8 KB of headers, and the first live run failed every lookup on it; the
fixtures cannot show this, so it is pinned here rather than in a test.

A refused image is a permanent miss (`ABSENT`) only when the refusal is about the mark itself: an
SVG, or an image over 256 KB. "An image" means bytes with a PNG, JPEG or WebP signature; a body
over the 4 MB read cap is never read, so there it means a declared `image/*` type. A 404 on the
page or the image is `ABSENT` too. A response that is not an image at all (an HTML error or
anti-bot page served with a 200, whatever its size, an empty body) says nothing about the mark,
and is `FAILED`, like everything else that goes wrong upstream: 5xx, 3xx, timeout, a connection
cut mid-body, a Content-Type the codecs cannot parse, and a page over its 4 MB cap (the page's
size is Yahoo's layout, the same for every ticker). The rule behind all of these: a CDN or layout
quirk must never settle a ticker, because it would settle every ticker at once.

A 404 on the quote page stays `ABSENT`: it is what Yahoo answers for a delisted or unknown symbol,
and nothing observed so far shows Yahoo answering 404 transiently for a symbol it quotes.

The dark variant follows the light one. When the page gives no distinct dark URL, or Yahoo has no
usable file behind it (404, a mark refused as above), the light mark is stored alone. When the dark
download is rate-limited or fails transiently, the whole lookup reports that instead (`RateLimited`
records nothing, anything else records `FAILED`), so the ticker is retried later with both
variants rather than settled without its dark one.

### Storage: `instrument_logo` (V104)

One row per ticker that was *attempted*, global rather than member-scoped (a logo is not
private data, and AAPL in two accounts or two members' portfolios is one row):

| Column | Meaning |
|---|---|
| `ticker` | upper-cased holding ticker, unique |
| `status` | `STORED`, `ABSENT` (the page quotes the symbol without a mark, the mark itself is refused, or a 404: never retried), `FAILED` (no usable answer: retried after 7 days) |
| `image`, `content_type` | the light mark; present exactly when `STORED` (CHECK constraint) |
| `image_dark`, `content_type_dark` | the dark mark, optional, both or neither |
| `attempted_at`, `fetched_at` | the miss marker's clock, and the stored mark's version |

PostgreSQL rather than the container filesystem, which has no writable volume and is lost on
every `docker compose up --build`. Not encrypted: see
[encryption-at-rest.md](./encryption-at-rest.md).

**Rows are never deleted** when a position disappears. A ticker can be held in another account
or by another member, and a sync's prune can remove and re-insert the same ticker in one unit
of work. An orphan costs a few kilobytes; a wrongly deleted mark costs a page fetch and a blank
row.

### When it runs

Never on a render. `SchedulerService.refreshPrices` (hourly, and once at boot) calls
`InstrumentLogoService.requestResolution()` **after** it has refreshed the prices. That queues
one pass on a single background thread and returns immediately; a pass already in flight
absorbs the request.

A pass looks up at most 10 tickers that are:

1. held in a live, non-`CRYPTO` account;
2. accepted by `InstrumentLogoPort.supports`: a Yahoo-quotable symbol that CoinGecko does not
   claim (the same split `CompositePriceProvider` prices by), so coins, ISINs and contract
   addresses never cost a request;
3. **priced at least once** (`price_snapshot` has a row). This is the request-free proof that
   the ticker is a real quoted symbol rather than a fund code or a cash line, and it is what ties
   the logo pass to the price pass: a new ticker becomes a candidate the moment the price pass
   records its first price;
4. not settled: no row yet, or a `FAILED` row older than 7 days.

Why after the price pass and not inside the nine services that write holdings: the third
condition is only met once prices are recorded, so a trigger on the holding write would find
nothing new yet, and it would put logo work on those services' transaction paths. A new
position gets its mark within the hour.

Seven days for `FAILED`, not a day: the pass runs hourly, so a page Yahoo refuses this host
should cost a request a week, while a real outage still heals within days. `ABSENT` is never
retried, which is what stops a ticker with no mark from being fetched on every pass.

### Rate limits: logos yield to prices

Yahoo had no 429 cooldown before this. `YahooCooldown` is that cooldown, deliberately
asymmetric:

- the **price path arms it** on a 429 (`YahooFinancePriceProvider.getPricesEur`) and never reads
  it, so prices behave exactly as before;
- the **logo path reads it** before every lookup and arms it on a 429 or a 5xx.

So a pass that meets a 429 stops, records nothing for that ticker (it was not really asked, so
it stays first in line) and leaves the rest for a later pass. A 5xx or a timeout records the
ticker as `FAILED` and also stops the pass. The pause is the server's `Retry-After` when sane,
60s otherwise, capped at 15 minutes, as in `CoinGeckoPriceProvider`. Lookups are 500 ms apart.

### Serving

`GET /api/instrument-logos/{ticker}[?variant=dark]` returns the stored bytes. It is
authenticated by `SecurityConfig`'s catch-all like every `/api` route, and not member-scoped:
any signed-in member can load any stored mark. That reveals that *someone* in the instance held
a ticker at some point, the same exposure `security_profile` already has. No rate limit,
unlike the merchant-logo proxy: it reads one row and never reaches the network.

`AccountService.logosFor` builds the URLs with one projection query that reads no image bytes.
Each URL carries `v={fetchedAt}`, so the response can be cached hard
(`Cache-Control: max-age=2592000, private`) with an `ETag`, and a replaced mark would be a
different URL. A missing dark variant falls back to the light one.

### Switching it off

`INSTRUMENT_LOGOS_ENABLED=false` (`app.instrument-logos.enabled`) stops new lookups, for
instance if Yahoo changes its page. Marks already stored keep showing; a holding without one
shows its ticker.

## Degradation

Every failure mode is decoration-only: `logoUrl` is null and the ticker stands.

- **Crypto provider down, 5xx, timeout, 429**: logged, cooldown armed on a 429 (shared with the
  CoinGecko price path), absence cached for a minute. A genuine bug in that path is rethrown,
  through the classifier shared with the price path.
- **Yahoo page or image unavailable**: the pass stops; the ticker is `FAILED` (5xx, 3xx,
  timeout, cut or unreadable body, a page that does not quote the symbol) or untouched (429),
  and the read path keeps showing the ticker.
- **A bug in the logo adapter**: the lookup throws, the pass logs it at error level, records the
  ticker as `FAILED` and goes on with the next one. Left unrecorded, the same ticker would head
  the sorted batch every hour and starve every ticker after it. It never reaches a page render
  or the price pass.
- **Image 404s in the browser**: `HoldingLogo` drops the `src` and shows an empty disc. Radix
  keeps a failed image mounted, so without that reset one failed request would leave the mark
  blank for the rest of the session.

## Tests

- `CoinGeckoPriceProviderTest`, `CryptoLogoServiceTest`, `LogoProviderWiringTest`: the crypto
  path, as in #163.
- `YahooQuotePageParserTest`: trimmed fixtures (`src/test/resources/yahoo/`) of the AAPL and
  IWDA.AS pages. The right company's mark when another company's logo comes first, an ETF with
  an exchange suffix, marked / unmarked / refused / not quoted (an unknown symbol, JSON quoting
  only other symbols, a page with no JSON blocks, a recommendations-only object for the symbol, a
  quote without a logo on a page with no usable mark, the real page with `logoUrl` renamed),
  off-host and look-alike URLs.
- `YahooQuotePageLogoProviderTest`: both variants downloaded, 429 arming the cooldown from
  `Retry-After` and recording nothing, no request while cooling down, 5xx stopping the next
  lookup, 404 and an unmarked quote as permanent misses, a consent page, other-symbols-only JSON,
  a recommendations-only object, a quote without a logo on a page with no usable mark, an
  off-host logo URL, a 3xx on the page or the image, a cut body, a malformed Content-Type and a
  page over its cap as retryable, SVG and an oversized image refused, an image without a type, as
  `octet-stream`, `text/plain`, `text/html` or the wrong image type stored by its signature, an
  HTML body of any size (under the image cap, over it, over the read cap) on an image (light or
  dark) as retryable, a dark variant Yahoo lacks keeping the
  light one, a 429 or 503 on the dark variant failing the whole lookup, coins and ISINs never
  reaching the network, and the price path arming the cooldown on a 429 but never waiting on it.
- `InstrumentLogoServiceTest`: candidate selection (crypto accounts, unsupported, unpriced,
  settled, the cap), what each outcome records, a 429 or 5xx stopping the pass, a throwing
  lookup recorded as `FAILED` without stopping the pass, the
  single-flight trigger and the switch, URLs only for stored rows, the dark fallback.
- `InstrumentLogoRepositoryTest` (H2): the settled-ticker query (an old `ABSENT` stays settled,
  an old `FAILED` comes due again), the byte-free projection, the priced-ticker query.
- `InstrumentLogoControllerTest` (web slice with the real `SecurityConfig`): 401 for an
  anonymous caller, the cache headers and ETag, a 304 on revalidation, the dark variant, 404.
- `InstrumentLogoWiringTest`: the price provider's constructor choice and the port seam.
- `SchedulerServiceTest`: logos are queued after the prices, even when the price refresh fails.
- `V104InstrumentLogoMigrationTest` (Testcontainers): the migration applies on the full chain
  and its CHECK and UNIQUE constraints hold.
- `AccountServiceTest`: a crypto holding carries its CoinGecko logo, a share its stored URLs,
  a share with nothing stored stays null, and a crypto account never reads the share store.
- `HoldingLogo.test.tsx` / `HoldingsTable.test.tsx`: an internal URL, the dark variant and a
  live theme switch, a stock row with mark and ticker, a share with no mark rendering as before.

`frontend/src/test/stubImage.ts` holds the global `Image` stub Radix needs.
