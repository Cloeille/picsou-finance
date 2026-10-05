# ADR: Value assets from the last known price rather than not at all

> Date: 2026-08-01
> Status: ✅ Active

## Context

On the morning of 2026-08-01 a connected Meria account displayed **-85%**. Nothing had moved on
the market: BTC and SOL — the two largest lines — showed `—` instead of a price, and the account
reported a loss the size of the two missing positions.

The chain behind it:

1. The container restarted. `PriceService`'s cache lives in the heap only, so it started empty,
   and `StartupSyncService` replays the entire daily sync on every boot.
2. `PriceBackfillRunner` then re-requested twelve months of history per held ticker — history that
   was already in `price_snapshot` — and CoinGecko's keyless free tier started returning 429.
3. Nothing was cached on failure, so every read retried. `AccountService.liveBalanceEur` issued
   one single-ticker request **per holding, per page render**; the logs show `spot prices for
   [BTC]`, `for [SOL]` repeating across six request threads within the same second. CoinGecko
   counts the calls it rejects, so the rate limit sustained itself for over two hours.
4. Meanwhile `price_snapshot` held `BTC = 54619 EUR` and `SOL = 63.20 EUR`, recorded at 04:23
   **that same morning**. The valuation path never looked at it.
5. The displayed -85% came from an asymmetry: `liveBalanceEur` dropped the assets it could not
   price, while `calculateInvestedAmount` kept their full cost. Value fell by 379 EUR of a 448 EUR
   cost basis; the percentage followed.
6. Worse than the display: a sync that ran during the rate limit wrote `balance_snapshot(account 5,
   2026-08-01) = 0.00 EUR` against an `invested_amount` of 448.24 EUR. Nothing goes back to fix a
   past day — `dailySnapshots` only writes when the row is absent.

So a transient upstream refusal produced a permanent wrong point in the net-worth history, and a
user-visible loss that never happened.

## Decision

**A price that cannot be fetched falls back to the most recent one recorded in `price_snapshot`
(up to 7 days old), and the interface shows that figure marked with its date.** An asset that
resolves to no price at all — no live quote and nothing recorded — is excluded from the account's
value **and** from its cost basis, so the percentage stays honest. A sync that can value nothing
at all refuses to write rather than recording a zero.

## Alternatives considered

### Keep returning "no price" and just fix the display

- **Pros**: smallest change; never shows a number that is not current.
- **Cons**: does not address the balance, the daily snapshot, or the history — the account is still
  worth less than it is, and the wrong figure is still engraved. Honest about the price, dishonest
  about the portfolio.

### Persist the in-memory cache (or add Redis)

- **Pros**: survives restarts; no new read path.
- **Cons**: a second store for something the database already holds. `price_snapshot` is written
  daily, is durable, and has exactly the right shape (one EUR price per ticker per day). Redis is
  also excluded by design — Picsou is single-process and self-hosted
  ([ADR 2026-04-26](./2026-04-26-loan-amortization-on-the-fly.md)).

### Fall back to `account_holding.current_price`

- **Pros**: already on the row being displayed.
- **Cons**: broker adapters store that field in the security's **native currency** without
  conversion, so using it as an EUR price silently produces native-as-EUR values. `AccountService`
  already refuses it for exactly this reason.

### Freeze the account at its last known total when anything fails

- **Pros**: no partial totals at all.
- **Cons**: hides genuine movement in everything that *did* price, and needs a "last good total"
  concept that does not exist. The Bourse Direct escape hatch does this narrowly because that
  broker reports an authoritative EUR total; nothing else does.

## Reasoning

The three possible states of a price are *current*, *dated*, and *unknown*, and the failure modes
are not symmetric. A dated price is wrong by one day of market movement. An unknown price, given
how the callers were written, was wrong by the **entire position** — and it propagated into the
balance, the daily snapshot and the net-worth chart, where it looked exactly like a real loss.
A slightly stale number that is marked as stale is the smallest lie available.

The 7-day ceiling is where that stops being true: prices are recorded daily, so a gap that wide
means the provider has been unreachable for a week, and a week-old crypto price is no longer a
approximation of anything.

Symmetry between value and cost basis is not a refinement, it is the actual bug. Any rule that
drops an asset from one side must drop it from the other, which is why both now come from a single
`AccountService.valuation()` pass over one quote map instead of two independent computations that
happened to disagree.

## Trade-offs accepted

- **A displayed figure may be up to a week old.** Mitigated by marking it (amber dot, "Price from
  {date}"), never by hiding it.
- **A total may still be understated** when an asset has no price of any age — typically a coin
  with no CoinGecko mapping. That is deliberate: the alternative is inventing a value. The
  percentage is now honest about it, since the cost basis is excluded too.
- **A failed lookup is not retried for 60 seconds.** A price that comes back mid-window is served
  from the cache or the fallback until then. That is the cost of not answering a rate limit with
  more requests.
- **Reads are only as fresh as one batched call per set.** Accepted: the per-holding fan-out it
  replaces is what made the outage self-sustaining.

## Consequences

- `PriceService` gains `Quote(price, asOf, live)` and `resolve()`; `getPriceEur` /
  `getCryptoPriceEur` delegate to it, so every existing caller inherits the fallback unchanged.
- `PriceSnapshotRepository.findRecentByTickers` resolves a whole set in one query — the fallback
  fires precisely when the provider is rate-limiting, so it must not answer a request storm with a
  query storm.
- `AccountService.valuation()` becomes the single source of value **and** cost basis;
  `liveBalanceEur` and `calculateInvestedAmount` delegate. `HistoryService`, which combines both,
  inherits the symmetry.
- `CoinGeckoPriceProvider` holds a post-429 cooldown and sends nothing while it lasts.
- `CryptoExchangeSyncService.sync` throws when no held asset can be valued **and an account
  already exists**, mirroring `WalletSyncService`. The session goes to `ERROR`, and the previous
  balance and snapshot stand. On a first sync it proceeds unvalued instead — there is nothing to
  protect, and throwing would roll back `addExchange` along with the session it just saved. No
  snapshot is written in either case.
- `SchedulerService.dailySnapshots` skips an account whose valuation priced nothing. The sync-path
  guard alone was insufficient: the 08:05 job values every account itself, with no adapter in the
  loop, so an outage would have engraved zeros through that path regardless.
- The Bourse Direct override swaps in the broker's authoritative total, so it also swaps in a cost
  basis covering every holding. Leaving the partial basis in place would report a gain the size of
  the unpriced positions — the same mismatch, sign-flipped.
- `HoldingResponse` and `ExchangePositionResponse` carry `priceAsOf` / `priceStale`;
  `PriceFreshnessDot` renders the marker.
- `SchedulerService.refreshPrices` warms holding tickers, not just account-level ones — the
  omission that left exactly the most-priced tickers cold between syncs. Crypto holdings are
  routed through `refreshCryptoPrices` so an unmapped coin cannot reach Yahoo Finance and have an
  equity's share price recorded under its symbol, which would poison the fallback table itself.
  Soft-deleted accounts' holdings are excluded, or their tickers would be fetched hourly forever.
- `backfillHistoricalPrices` skips a ticker only when its recorded history has no hole longer than
  a week. Probing the range's two ends instead would call a months-long outage "covered" and never
  refill it.
