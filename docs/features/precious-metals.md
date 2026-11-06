# Feature: Precious metals (physical gold)

> Last updated: 2026-11-06

## Context

Physical gold held directly (bars, coins) had no account type. The Accounts page's `METALS`
filter listed every `OTHER` account, metal or not, and nothing priced the gold. A
`PRECIOUS_METALS` account now holds gold positions valued from a live quote, in net worth and in
the `ALTERNATIVE` tier of the pyramid. Issue #448.

## How it works

A `PRECIOUS_METALS` account holds positions, like a brokerage account: `AccountType.isInvestment()`
is true, so BUY/SELL transactions derive `account_holding` rows and the balance is the sum of the
priced positions. Each position's ticker comes from a closed catalogue (`PreciousMetal`):

- `GOLD:GRAM`: bullion, quantity in fine grams (a 1 kg bar is 1000, a one-ounce bar 31.1035).
  Priced at spot: Yahoo Finance `GC=F` (USD per troy ounce, converted to EUR by the existing FX
  path) divided by 31.1034768.
- `GOLD:NAPOLEON-20F`, `GOLD:KRUGERRAND`, `GOLD:SOVEREIGN`, … : coins, quantity in coins.
  Priced at the coin's own daily quote from Loomis FXGS (formerly CPoR Devises, the reference
  quote for gold coins in France), read from its public page
  `https://www.loomis-fxgs.fr/fr/or/cotation-produits/`.

The catalogue is enforced on every write: a transaction carrying a ticker on a `PRECIOUS_METALS`
account must name a catalogue product, or the request fails with a 400 (a per-row error in a CSV
import). The stored ticker is the catalogue's spelling (`PreciousMetal.canonicalTickerFor`, shared
by `ManualTransactionService` and `TransactionRowMapper`). Tickers stay within 20 characters,
the account-level ticker limit (`GOLD:SOVEREIGN-EII` for the Elizabeth II sovereign).

A coin's quote includes its premium over the metal it contains. On 2026-11-05 a Napoléon 20 F
contained about 678 € of gold and was quoted 709.90 €. Valuing coins at weight × spot would
understate them by that premium, so they are priced as coins, not as metal.

### Key files

- `backend/.../model/PreciousMetal.java`: the catalogue (ticker, name, unit, Loomis slug)
- `backend/.../adapter/PreciousMetalPriceProvider.java`: spot and coin quotes behind `PriceProviderPort`
- `backend/.../adapter/CompositePriceProvider.java`: routes `GOLD:` tickers to it
- `backend/.../controller/PriceController.java`: `GET /api/prices/precious-metals` (the catalogue)
- `backend/src/main/resources/db/migration/V122__account_type_precious_metals.sql`
- `frontend/src/components/shared/AddTransactionModal.tsx`: product picker on a metals account

### Flow

```
BUY GOLD:NAPOLEON-20F × 10 ──► account_holding(GOLD:NAPOLEON-20F, qty 10)
                                   │
PriceService ──► CompositePriceProvider ──► PreciousMetalPriceProvider
                                               ├─ coins: one GET of the Loomis page per batch
                                               └─ GOLD:GRAM: Yahoo GC=F (EUR) / 31.1034768
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| New `AccountType` | `WealthTier.of` is an exhaustive switch; the type picks `ALTERNATIVE` explicitly and the `METALS` filter gets a type of its own | A holding kind on `OTHER`: the filter would keep listing every `OTHER` account |
| Positions, not a balance | A user holds several products in one safe; each is priced on its own | One balance per account, typed by hand |
| Closed ticker catalogue with a `GOLD:` prefix | The `:` fails Yahoo's symbol pattern, so the logo, security-profile and dividend jobs never send these tickers to Yahoo | Free-text tickers |
| Coin quote from Loomis FXGS | Daily reference quote for French gold coins, premium included, one page for every coin | Dealer sites (buy/sell spreads differ per dealer); weight × spot (misses the premium) |
| `GC=F` for spot | Already reachable through the Yahoo adapter and its FX conversion | `XAUEUR=X` (404 on Yahoo) |

## Gotchas / Pitfalls

- `GC=F` is the COMEX front-month future, a little above spot. The gap is small next to coin premiums.
- Loomis publishes one quote per business day. A suspended coin (`Cotation suspendue`) is absent
  from the result, so `PriceService` falls back to its last recorded price, like any unpriced ticker.
- No price history is reachable for coins: the history and intraday calls return nothing, and the
  account's history starts from the daily snapshots taken after the account exists.
- The page is HTML. A redesign of the Loomis page drops coin prices, it does not break the app;
  bullion keeps its spot price. A page that is fetched but yields no quote logs a `WARN` naming
  Loomis (markup change or maintenance page).
- Each Loomis row carries its own `Cotation du dd/MM/yyyy` date. A quote dated more than 7 days
  back (`MAX_QUOTE_AGE_DAYS`) is dropped with a `WARN`, so `PriceService` falls back to its last
  recorded price, flagged stale, rather than showing a frozen quote as live. A row without a
  readable date keeps its quote: the age check is a guard against a stuck page, not a parser
  contract. The check does not touch `PriceProviderPort`: the provider never reports an age, it
  only withholds a quote it no longer trusts.

## Tests

- `PreciousMetalPriceProviderTest`: page parsing (French amounts, suspended coin), spot per gram,
  upstream failure returns nothing
- `CompositePriceProviderTest`: `GOLD:` routing next to crypto and equities
- `PreciousMetalTest`: catalogue contract, ticker length, `canonicalTickerFor`
- `ManualTransactionServiceTest`, `TransactionRowMapperTest`: the catalogue enforced on both write paths
- `AccountTypeTest`, `AssetClassTest`, `WealthTierTest`: the new type's placement
- `AddTransactionModal.test.tsx`, `constants.test.ts`: product picker and type lists

## Links

- Issue: https://github.com/Cloeille/picsou-finance/issues/448
