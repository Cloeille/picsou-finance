# Feature: Crypto Tracking

> Last updated: 2026-08-07

## Context

Picsou tracks cryptocurrency holdings from two sources: centralized exchanges (Binance, Meria) and on-chain wallets (Bitcoin, EVM, Solana). The `EVM` chain is a **multichain fan-out**: a single `0x` address is tracked across every enabled EVM network (Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base, Avalanche C-Chain) — see the [EVM multichain wallets ADR](../decisions/2026-07-17-evm-multichain-wallets.md). Exchange credentials are encrypted at rest with AES-256-GCM. On-chain wallets query public blockchain RPCs (no API key). All crypto balances are converted to EUR via the `PriceService`.

## How it works

### Three subsystems

1. **CryptoExchangeSyncService** -- Manages exchange connections. Stores an encrypted API key, plus an encrypted secret **when the exchange has one**, in `CryptoExchangeSession`. Which credentials an exchange needs is declared by its adapter (`CryptoExchangePort.requiresApiSecret()`) and enforced here before any network call: a missing secret where one is required, and a stray secret where none is, are both a `400`. Fetches positions, converts to EUR via `PriceService.refreshCryptoQuotes()`, and upserts a single account per exchange with per-coin holdings in `AccountHolding` plus a per-product breakdown in `CryptoExchangePosition`.

2. **WalletSyncService** -- Manages on-chain wallet addresses. Stores chain type + address in `WalletAddress`. Fetches balances via `WalletPort` (a list — native asset plus any tokens), prices each ticker, sums to EUR, and upserts one account with a per-ticker `AccountHolding`. Does NOT store a ticker on the account itself to prevent double price conversion (the account balance is already in EUR).

3. **PriceService** -- Provides EUR prices for crypto tickers via CoinGecko. See [price-service.md](./price-service.md).

### AES-256-GCM encryption

`CryptoEncryption` handles encryption/decryption of API keys and secrets stored in the database. Both `apiKey` and `apiSecret` are encrypted; `apiSecret` is `NULL` for single-key exchanges (`encrypt(null)` and `decrypt(null)` both return `null`, and V73 dropped the column's `NOT NULL`). It uses `AES/GCM/NoPadding` with a 12-byte IV and 128-bit tag. The IV is prepended to the ciphertext before Base64 encoding. The encryption key is provided via the `CRYPTO_ENCRYPTION_KEY` environment variable (Base64-encoded 256-bit key). The app **refuses to start** if the key is not set. See [encryption-at-rest.md](./encryption-at-rest.md) for full details.

### Binance adapter

`BinanceAdapter` implements `CryptoExchangePort`. It calls the Binance REST API (`GET /api/v3/account`) with HMAC-SHA256 signed requests. Returns one `SPOT` `ExchangePosition` per asset with a non-zero balance (free + locked) — it reads `/api/v3/account`, which does not cover Binance Earn. The `testConnection()` method validates credentials before saving.

### Meria adapter

`MeriaAdapter` implements `CryptoExchangePort` against [docs.meria.com](https://docs.meria.com). It differs from Binance in two structural ways:

- **A single read-only API key**, sent as an `API-KEY` header — no secret, no request signing. Users generate it at `dashboard.meria.com/account/api`. The adapter reports `requiresApiSecret() == false`.
- **Three endpoints, one balance.** A Meria position is spread across `GET /wallets` (spot), `GET /stakings` and `GET /lendings`, all shaped as `currencyCode` + amount. The adapter sums them per currency, counting a staking/lending contract as its **`amount` alone** — see the double-counting gotcha below before adding `reward` back. `GET /status` backs `testConnection()`.

Every call must return a `success: true` envelope, or an empty body on a 2xx; anything else fails the whole sync — see the all-or-nothing gotchas below. Note the staking payload is **large** (full reward history per contract), which is why the client raises its in-memory buffer to 16 MB — and why the body is parsed straight from the byte array rather than decoded to a `String` first, which would hold a second full copy of it. `app.meria.base-url` accepts a `MERIA_BASE_URL` override like every other external base URL, and `MeriaAdapter` carries the same value as its `@Value` default, so a stripped config still boots.

### Per-product positions

`CryptoExchangePort.fetchPositions` returns one `ExchangePosition` per (product, asset) — `SPOT`,
`STAKING` or `LENDING` — instead of a flat balance list, because an exchange can hold the same
asset under several products at once. `CryptoExchangeSyncService` then does two different things
with them:

- **sums them per asset** into the `AccountHolding` rows and the account balance (net worth does
  not care which product an asset sits under), and
- **stores the breakdown as-is** in `crypto_exchange_position`, which backs
  `GET /api/accounts/{id}/positions` and the account page's Spot / Staking / Lending sections.

The breakdown is display-only and fully derived: each sync rewrites it wholesale, it carries no
cost basis, and nothing computes net worth from it. That is why it is a separate table rather than
a `product` column on `account_holding` — that table is the valuation model shared by every
connector, unique on `(account_id, ticker)`, and splitting it per product would ripple into all of
them plus the dashboard aggregations, for a display feature.

A position's `interest` is a **decomposition** of its quantity, never an addition: `principal +
interest = quantity`. `principal` is null when the exchange does not report yield (all of Binance,
which is spot-only here), and the UI then drops both columns.

That identity is enforced, not assumed: a `reward` outside `[0, quantity]` cannot decompose
anything, so `MeriaAdapter` reports the quantity with **both** halves null rather than publishing
the impossible figure with the principal blanked beside it. Merging two contracts of the same
asset follows the same rule — one undecomposable side makes the merged position undecomposable,
since summing a known interest with an unknown one yields a total the principal cannot be
reconciled against.

Cost basis stays **per asset**: Meria reports no per-contract acquisition price, so each line
reuses its asset's `AccountHolding.averageBuyIn` and multiplies by its own quantity. A coin held
both spot and staked therefore shows the same unit cost on both lines, and the per-line P&L still
sums to the holding's — which is what keeps the sections reconcilable with the account balance.

### Bitcoin wallet adapter

`BitcoinWalletAdapter` implements `WalletPort`. It supports three input formats for the address field:

- **Plain address** (`bc1q...`, `1...`, `3...`) -- Single address lookup via Blockstream Esplora API.
- **Extended public key** (`xpub...`, `zpub...`) -- BIP32 HD wallet key derivation. Derives P2WPKH addresses (BIP84) along external (`m/0/*`) and change (`m/1/*`) chains.
- **Output descriptor** (`wpkh([fingerprint/path]xpub.../chain/*)#checksum`) -- Proton Wallet format, normalized to xpub before derivation.

The adapter scans each chain until `GAP_LIMIT` (20) consecutive unused addresses are found (BIP44 standard). `BitcoinKeyUtils` provides BIP32 key derivation, Base58Check decoding, and Bech32 encoding.

### EVM wallet adapter (multichain fan-out)

`EvmWalletAdapter` handles the `EVM` chain — one `0x` address, every enabled EVM network. It iterates a registry of networks (`EvmNetwork{ displayName, nativeSymbol, rpcUrl, tokens }`), all keyless PublicNode RPCs:

| Network | Native | RPC |
|---|---|---|
| Ethereum | ETH | `ethereum-rpc.publicnode.com` |
| BNB Chain | BNB | `bsc-rpc.publicnode.com` |
| Polygon | POL | `polygon-bor-rpc.publicnode.com` |
| Arbitrum | ETH | `arbitrum-one-rpc.publicnode.com` |
| Optimism | ETH | `optimism-rpc.publicnode.com` |
| Base | ETH | `base-rpc.publicnode.com` |
| Avalanche C-Chain | AVAX | `avalanche-c-chain-rpc.publicnode.com` |

For each network it calls `eth_getBalance` for the native coin (wei→coin, 18 decimals on all these chains) and one `eth_call` `balanceOf(address)` per curated ERC-20/BEP-20 token. `eth_getBalance`/`eth_call` are byte-identical across EVM chains — only the RPC URL, native symbol and token list differ. The networks are queried **concurrently** (a reactive `Flux` fan-out, not a sequential loop), each call bounded by a 10s timeout and a small backoff retry, so total latency is roughly the slowest chain rather than the sum of ~20 calls. The raw transport is a thin `EvmRpc` seam (`(rpcUrl, request) → Mono<JsonNode>`); the adapter layers timeout, retry, error-classification and envelope validation around it, which also lets tests stub it by request content.

Balances are **aggregated by symbol** before returning: ETH held on Ethereum + Arbitrum + Optimism + Base rolls into one `ETH` entry, and a stablecoin held on two chains sums into one — which also keeps `WalletSyncService`'s per-ticker holding upsert (unique on `account_id, ticker`) collision-free. Token coverage is a **curated** contract list per network (only tokens `PriceService` can price and whose contract is verified), mirroring Solana's `KNOWN_MINTS` — full auto-discovery would need an API-key explorer (see the ADR).

### Solana wallet adapter

`SolanaWalletAdapter` calls `getBalance` on the Solana mainnet RPC (`api.mainnet-beta.solana.com`). Returns balance converted from lamports to SOL (9 decimals). It also calls `getTokenAccountsByOwner` to pick up known SPL stablecoins (USDC/EURC/USDT).

### JSON-RPC error handling

The on-chain adapters (EVM, Solana) validate the JSON-RPC envelope through `JsonRpcResponse.requireResult(response, context)` (`adapter/util/`) before reading a balance. A present `error` field, a missing `result`, or an empty response throws `WalletRpcException`, which `WalletSyncService` surfaces as a `422` sync failure. This is deliberate: reading `result` with `path(...)` returns a non-null `MissingNode`, so an error payload would otherwise default to a **silent 0 balance** — indistinguishable from a genuinely empty wallet. A real `0x0` / `value:0` result still returns 0. On Solana, an error on the SPL-token call fails the whole sync rather than silently dropping stablecoin holdings.

`WalletSyncService.sync()` splits its catch: **expected** failures (`WalletRpcException`, `SyncException`) log at `WARN` and become the friendly `422`; **unexpected** ones (NPE, `ClassCastException`, …) log at `ERROR` with the full stacktrace so a real bug can't hide as a transient sync. `WalletRpcException` has no dedicated `@ExceptionHandler` — it stays wrapped in `SyncException`, which keeps the `422` mapping. Two per-item cases inside the Solana adapter are **not** fatal, to avoid one bad field hiding the rest of the wallet: a non-array token `value` and a malformed `uiAmountString` are logged (WARN / ERROR respectively) and that one entry is skipped, while SOL and every parseable token still come through. `resyncAll()` returns a `ResyncSummary(total, succeeded, failed)` so its callers (the daily scheduler and the `trigger_crypto_wallet_sync` MCP tool) can report per-wallet outcomes instead of a blanket "done".

### Key files

- `backend/src/main/java/com/picsou/service/CryptoExchangeSyncService.java` -- Exchange connection management, holding sync
- `backend/src/main/java/com/picsou/service/WalletSyncService.java` -- On-chain wallet management, balance sync
- `backend/src/main/java/com/picsou/config/CryptoEncryption.java` -- AES-256-GCM encrypt/decrypt for API secrets
- `backend/src/main/java/com/picsou/adapter/BinanceAdapter.java` -- Binance REST API with HMAC-SHA256
- `backend/src/main/java/com/picsou/adapter/MeriaAdapter.java` -- Meria REST API, single `API-KEY` header, wallets + stakings + lendings
- `backend/src/main/java/com/picsou/model/CryptoExchangePosition.java` -- per-product breakdown behind an exchange account's holdings
- `frontend/src/components/shared/PositionsByProduct.tsx` -- Spot / Staking / Lending sections on the account page
- `backend/src/main/java/com/picsou/adapter/BitcoinWalletAdapter.java` -- Blockstream Esplora, BIP32 key derivation
- `backend/src/main/java/com/picsou/adapter/EvmWalletAdapter.java` -- EVM multichain fan-out (native + curated ERC-20 across all enabled networks), keyless PublicNode RPCs
- `backend/src/main/java/com/picsou/adapter/SolanaWalletAdapter.java` -- Solana mainnet RPC
- `backend/src/main/java/com/picsou/adapter/util/JsonRpcResponse.java` -- JSON-RPC envelope validation shared by the wallet adapters
- `backend/src/main/java/com/picsou/exception/WalletRpcException.java` -- thrown on a JSON-RPC error/missing result
- `backend/src/main/java/com/picsou/adapter/util/BitcoinKeyUtils.java` -- BIP32 derivation, Base58Check, Bech32
- `backend/src/main/java/com/picsou/port/CryptoExchangePort.java` -- Exchange port interface
- `backend/src/main/java/com/picsou/port/WalletPort.java` -- Wallet port interface

### Flow

```
Add Exchange:
User submits API key (+ secret, if the exchange uses one)
        |
        v
CryptoExchangeSyncService.addExchange()
        |
        v
validate against adapter.requiresApiSecret() -- 400 before any network call
        |
        v
CryptoExchangePort.testConnection() -- validate credentials
        |
        v
CryptoEncryption.encrypt(key [+ secret]) -- AES-256-GCM
        |
        v
Save CryptoExchangeSession (encryptedKey + encryptedSecret or NULL)
        |
        v
CryptoExchangePort.fetchPositions() -- one line per (product, asset)
        |
        v
PriceService.refreshCryptoQuotes() -- convert to EUR, crypto-only
        |
        v
Upsert Account (type=CRYPTO) + AccountHolding per coin
        + rewrite crypto_exchange_position
        |
        v
Mark the session CONNECTED -- last, see the transaction gotcha below

Add Wallet:
User submits chain + address
        |
        v
WalletSyncService.addWallet()
        |
        v
WalletPort.fetchBalance() -- blockchain RPC call
        |
        v
PriceService.getPriceEur() -- convert native to EUR
        |
        v
Upsert Account (type=CRYPTO, no ticker)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| AES-256-GCM (symmetric) | Simple, no external dependency; sufficient for single-user self-hosted app | HashiCorp Vault, cloud KMS (overkill for self-hosted) |
| No ticker on wallet accounts | Balance is already converted to EUR at sync time; storing a ticker would cause `PriceService.toEur()` to multiply a second time | Store ticker and handle conversion in dashboard (risk of double conversion) |
| BIP32 key derivation in Java | Full control over derivation; no native library dependency | Use a separate Bitcoin library (unnecessary complexity) |
| Blockstream Esplora (public) | Free, no API key needed, reliable | Run own Electrum server (too much for self-hosted) |
| EVM multichain fan-out over keyless RPC | One `0x` address = many chains; `eth_getBalance`/`eth_call` are identical across EVM chains, so one adapter covers all with no API key | Per-chain wallet entries (re-typed address); Etherscan/BscScan API-key explorer (breaks keyless convention) — see ADR |
| Curated ERC-20 contract list per chain | Only tokens `PriceService` can price and whose contract is verified; keyless `balanceOf` needs the contract up front | Auto-discover every token via an indexer/explorer (needs API key + rate limits) |
| GAP_LIMIT=20 | BIP44 standard; covers the vast majority of HD wallets | Custom gap limit (not configurable, hardcoded) |

## Gotchas / Pitfalls

- **A Bitcoin extended-key scan that fails is a failed sync, not a zero.** `BitcoinWalletAdapter.fetchExtendedKeyBalance` no longer catches every exception into `BTC = 0`: an Esplora 429 or 5xx, a timeout or an invalid xpub propagate to `WalletSyncService`, which reports the sync as failed and leaves the previous balance and snapshot standing. Returning zero used to be written as the wallet's real balance.
- **The exchange sync prunes holdings the exchange no longer reports.** `upsertHolding` only adds or updates, so a coin sold or withdrawn kept its last price and stayed in the account's value and cost basis; `CryptoExchangeSyncService` now deletes the account's holdings whose ticker is absent from the fetched positions (all of them when the exchange reports nothing).
- **CRYPTO_ENCRYPTION_KEY required**: The app refuses to start without it. Lost key = cannot decrypt existing secrets = must re-enter exchange credentials.
- **No ticker on wallet accounts**: Wallet accounts have `ticker = null` and `provider = "BTC"/"ETH"/"SOL"`. The balance is already in EUR. Do not set a ticker on wallet accounts -- it will cause double price conversion.
- **Bitcoin xpub vs zpub**: Both are supported. `BitcoinKeyUtils.normalizeToXpub()` converts zpub to xpub before derivation. The derivation always produces P2WPKH (native segwit) addresses.
- **Output descriptor parsing**: Descriptors are parsed by extracting the xpub between brackets. The checksum after `#` is ignored. Complex descriptors (multisig, P2SH-wrapped) are not supported.
- **Exchange holdings use PriceService**: holdings are converted to EUR at sync time using `PriceService.refreshCryptoQuotes()`, which forces a provider call and falls back to the last recorded `price_snapshot` (≤ 7 days) for whatever it cannot fetch. Read paths (`getPositions`, `AccountService.valuation`) resolve the whole ticker set in **one** call. See [price-service.md](./price-service.md) and [ADR 2026-08-01](../decisions/2026-08-01-last-known-price-fallback.md).
- **An exchange sync that can value nothing refuses to write — unless there is nothing to protect**: same rule as the fully-unpriced wallet below, and it exists for the same reason — on 2026-08-01 a sync ran while CoinGecko was rate-limiting, priced nothing, and wrote `currentBalance = 0` plus a **0 EUR `BalanceSnapshot`** over a 448 EUR account. Past days are never revisited (`dailySnapshots` only writes when the row is absent), so that zero was permanent. `sync` therefore throws `SyncException` when assets are held, none resolve to any price (live *or* recorded), **and an account already exists**; the session goes to `ERROR` and the previous figures stand. On the **first** sync it proceeds instead: there is no balance to overwrite, and throwing would roll back the `@Transactional` `addExchange` including the session it had just saved — a user whose coins are unmapped could then never add the exchange, and would be told to "try again later" for a failure that never resolves. Either way no snapshot is written when nothing priced, so no zero reaches the history. A **partial** valuation still writes, deliberately — refusing whenever one obscure coin is unmapped would block snapshots forever — and the excluded tickers are logged.
- **`dailySnapshots` applies the same refusal on the read side**: it takes one `AccountService.Valuation` per account and skips the day when `anyPriced()` is false. The sync-path guard alone was not enough — the 08:05 scheduler values every account through `liveBalanceEur`, with no adapter involved, so a provider outage would have engraved zeros through that path regardless.
- **Which credentials an exchange needs lives in three places, with no codegen**: `CryptoExchangePort.requiresApiSecret()` (the truth), `CryptoExchangeSyncService.addExchange` (the enforcement), and `SUPPORTED_EXCHANGES` in `frontend/src/types/api.ts` (the form). The default is `true`, so an existing adapter needs no edit. Getting the frontend flag wrong is a loud `400`, not a silent bug — but the form must also **clear the secret field when the user switches exchange**, or a value typed under Binance would be posted to a single-key exchange and rejected for a reason the user never chose.
- **A failed Meria sub-call fails the whole sync — deliberately**: `MeriaAdapter` reads three endpoints that sum into **one** account balance, so a silently dropped `/stakings` or `/lendings` would not surface as a missing sub-account; it would write a smaller total into `account.currentBalance` **and** stamp it into today's `BalanceSnapshot`, denting the net-worth history while everything looks healthy. Any non-2xx, transport error, timeout, unparseable body or `success != true` therefore throws `SyncException` → session `ERROR` → `422`, and nothing is written. Same rule as `JsonRpcResponse.requireResult` on-chain and the fully-unpriced-wallet refusal above.
- **`/stakings` needs a 16 MB response buffer, not WebClient's 256 KB default**: each staking contract carries its full `variations` and `credits` history — one entry per reward credit — so a few years of daily rewards runs to megabytes, and the API offers no way to ask for less. `MeriaAdapter.STRATEGIES` raises the limit; without it the body is never assembled and every sync fails. **This is the bug that broke the first release, and it lied twice about its cause**: through a typed codec the truncation surfaced as a `WebClientResponseException` carrying the *original* `200` with an unreadable body, logged as `Meria answered HTTP 200 for /stakings -- failing the sync: <empty body>` — which reads as "the endpoint returned nothing" and produced two confident, wrong diagnoses (an unsubscribed product, then a content-type mismatch). Only after the adapter was reading raw bytes did the real `DataBufferLimitException` appear. `MeriaAdapterWiringTest.theProductionClientReadsAStakingPayloadBiggerThanTheFrameworkDefault` serves an oversized payload over a real socket, because the `ExchangeFunction`-based unit tests decode with the *stub's* strategies and cannot see the production client's configuration at all.
- **Read the body as raw bytes, not a typed codec**: `fetch` uses `exchangeToMono` + `byte[]` and parses with an explicit `ObjectMapper`. The byte decoder accepts every media type, so no combination of content type — absent, wrong, malformed — can turn a healthy response into a decode error, and the status, declared content type and truncated body all reach the log. Whatever Meria does next is diagnosable from one line instead of a guess. An empty body on a 2xx then reads as "no entries", which is safe: failures arrive as a non-2xx status or a `success:false` envelope, never as a 200 with nothing in it.
- **Meria can answer `success:false` on HTTP 200**: gate on the envelope, never on the status code. `MeriaAdapter.fetch` checks `Boolean.TRUE.equals(envelope.success())` with a *boxed* Boolean so a missing or null field fails safe.
- **Route existence is observable without a key**: Meria answers `403 UNAUTHORIZED` for a real route and `404 ENDPOINT_NOT_FOUND` for one that doesn't exist, so a 404 on `/stakings` or `/lendings` means the endpoint moved — not that the product is empty. (The API doc's `RESSOURCE_NOT_FOUND` is for parameterised routes such as `/wallets/:currencyCode`.) Useful when diagnosing: probing costs nothing and needs no credentials.
- **Meria rate-limits at 30 requests per window** (`x-ratelimit-limit`) and sits behind Cloudflare. One sync costs 3 calls, adding an exchange costs 4. The three calls are deliberately **sequential**: a reactive fan-out would bound latency at ~10s instead of ~30s but burst three concurrent requests against that quota for no user-visible gain at n=3. Cloudflare also means a challenge page can replace the JSON, which is why the adapter logs a truncated response body — without it that failure is undiagnosable. The log never contains the API key.
- **A staking contract's `reward` must NOT be added to its `amount`**: `reward` is the contract's *cumulative* interest to date, and `amount` already reflects it when compounding is on. Adding them shipped, and overstated a real account by exactly its total interest: Meria showed 33.154 ATOM held and 13.424 ATOM of total interest, Picsou reported 46.577. Without compounding the interest is credited into the spot wallet instead (`credits` entries carry `released: 1`), so it arrives through `/wallets` — adding `reward` double-counts in that mode too. `lockedReward` is left out for the same reason plus one more: whether it sits inside `amount` could not be established (every observed contract reports 0), and understating a holding is recoverable in a way that overstating net worth is not. The API exposes no "unclaimed balance" field distinct from these, so **any future change here needs a real account to check against, not the field names**.
- **Exchange holdings are priced crypto-only**: `CryptoExchangeSyncService` calls `PriceService.refreshCryptoPrices`, not `refreshPrices`. The difference matters because `refreshPrices` sends anything `CoinGeckoPriceProvider.supports()` rejects to **Yahoo Finance** — right for a mixed portfolio, catastrophic for an exchange, where an unmapped coin would be valued at the share price of the listed company trading under the same symbol and written into the balance and its daily snapshot with nothing in the log to reveal it. Unmapped coins are now left unpriced and logged instead. Note `WalletSyncService` still uses plain `refreshPrices` and therefore keeps that fallback — same hazard, not addressed here.
- **`TICKER_TO_ID` is a router, so adding a symbol has a cost on the *stock* side**: `PriceService` and `AccountService` branch on `supports()` with no idea which account a ticker came from, so mapping a symbol that is also a listed equity makes that **stock** get priced as a token. `STX` (Seagate), `SNX` (TD SYNNEX), `SEI` (Solaris Energy Infrastructure) and `APT` (Alpha Pro Tech) are deliberately **not** mapped for that reason, even though Meria offers coins with those symbols — a missing value beats a wrong one. `CoinGeckoPriceProviderTest.supports_rejectsSymbolsSharedWithListedEquities` pins it. (`ATOM`/Atomera predates this rule.)
- **New Meria coin = one edit in `TICKER_TO_ID`**: same trap as the EVM token gotcha below — an unmapped `currencyCode` is fetched from Meria and then dropped from the EUR total, with only a WARN to show for it. Resolve each id on CoinGecko's `/coins/list` and pick the **canonical** entry: `TIA`, `ZEC`, `BCH`, `TRX`, `XTZ`, `ROSE` and `DYDX` all have `binance-peg-*` / `*-wormhole` / `bridged-*` homonyms, and a wrong id is a wrong valuation that nothing surfaces.
- **A failed exchange sync writes its `ERROR` status through `CryptoExchangeStatusWriter`**: `CryptoExchangeSyncService` is `@Transactional` and rethrows, so a plain `save` of the status inside the catch is rolled back on the manual path — the session would keep reading `CONNECTED` after a revoked key, while the scheduled path (which catches before crossing the proxy) reported `ERROR`. The writer's `REQUIRES_NEW` method commits independently. Same arrangement, and same reason, as `IbkrStatusWriter`.
- **…but it is called *after* the failing transaction ends, never inside it**: a second transaction updating the session row waits for the first one's row lock, and the first one holds that lock whenever it has touched the row — `addExchange` rewrites an existing session's credentials, and the `findByIdAndMemberId` opening `sync` queries the very table that update is pending on, so Hibernate flushes it first. Calling `markError` inline then blocks the new transaction on a lock only the caller can release, while the caller waits for `markError` to return. Postgres sees no cycle (one side waits in Java, not on a lock), so nothing breaks the tie and the request hangs until a `lock_timeout` that is unset by default, holding two pooled connections. `markErrorWhenThisTransactionEnds` registers a `TransactionSynchronization` and writes in `afterCompletion`; outside a transaction (unit tests) it writes inline. For the same reason the sync marks the session `CONNECTED` **last**, once the risky work is done, instead of before resolving the account.
- **Wallet RPC errors must not read as 0**: When parsing a blockchain JSON-RPC response, always go through `JsonRpcResponse.requireResult(...)` — never `response.path("result")` directly. `path(...)` returns a `MissingNode` for an error payload, which silently becomes a 0 balance (this caused the July 2026 Ethereum outage). `requireResult` uses `get(...)` to reject a missing/error result while still allowing a legitimate `0x0` / `value:0`.
- **Don't re-throw `WalletRpcException` raw from `sync()`**: it has no `@ExceptionHandler`, so a raw re-throw becomes a `500`, not the friendly `422`. Keep it wrapped in `SyncException`. The split catch is only about **log level** — unexpected errors log at ERROR with a stacktrace; the user-facing status/message is unchanged.
- **Per-token Solana failures are logged, not fatal**: a malformed `uiAmountString` or a non-array token `value` is logged and skipped so the SOL balance and other tokens survive. Only an envelope-level RPC `error`/missing-`result` (via `requireResult`) fails the whole sync. Loud (logged) ≠ fatal (thrown) — pick per blast radius.
- **EVM fan-out failure isolation is asymmetric**: a network whose native `eth_getBalance` probe fails **fails the whole sync** — it propagates as `WalletRpcException` → `422`, leaving the wallet un-synced with its last balance. Silently dropping a chain would understate net worth while still marking the wallet synced, the same "never read a failed RPC as 0" rule as the July 2026 outage. A single `balanceOf` error, by contrast, skips just that one token (a token call only runs *after* the network's native probe succeeded, so it's a per-asset problem — a reverting/non-standard contract — not a down chain), consistent with Solana's per-token handling. Loud (logged) ≠ fatal (thrown), chosen per blast radius.
- **Transport failures must classify as `WalletRpcException`, not bugs**: the shared `rpc(...)` helper wraps any non-`WalletRpcException` (connection reset, HTTP 5xx, timeout, retries exhausted) into a `WalletRpcException` before it leaves the reactive chain. Otherwise a raw `WebClientException`/`TimeoutException` would (a) hit `WalletSyncService`'s `catch (Exception)` **ERROR-as-genuine-bug** branch instead of the friendly WARN/422, and (b) escape the per-token `onErrorResume(WalletRpcException)` and abort the whole multi-chain sync. An empty body is turned into a failure via `switchIfEmpty` so it isn't read as a 0 balance.
- **EVM balances aggregate by symbol**: the same symbol across chains (ETH on L2s, USDC on several chains) is summed into one `WalletBalance`. This is intentional (net-worth total) **and** required — the caller upserts holdings unique on `(account_id, ticker)`, so un-aggregated duplicates would clobber each other.
- **EVM display ticker is a seeded native**: `fetchBalances` seeds the primary network's native (ETH) at zero so it always leads the returned list — `WalletSyncService` reads `balances.get(0)` as the account's display ticker, and a token-only or empty wallet must still read as the native, not whichever token was found first. The zero seed is dropped by the caller's positive-amount guard, so it never becomes a holding.
- **Wallet sync prunes stale holdings — keyed on *held*, never on *priced***: after upserting current balances, `WalletSyncService` calls `AccountService.pruneHoldings(account, keptTickers)` to delete `AccountHolding` rows for assets no longer held. `keptTickers` is built from the adapter's **positive on-chain balances**, deliberately *not* from which prices resolved this cycle. This distinction is load-bearing: `CoinGeckoPriceProvider.getPricesEur` swallows errors and returns an empty map on an outage, so keying prune on priced tickers would empty the set during a transient CoinGecko blip and `deleteByAccountId` would wipe **every** holding (and its `averageBuyIn` cost basis). A held-but-unpriced asset keeps its last row untouched. An empty `keptTickers` (genuinely empty wallet) clears all holdings. Without any pruning, a sold/moved-out token lingers and inflates `liveBalanceEur` forever — a real hazard now that the multi-token EVM fan-out makes disappearing tickers routine.
- **Protecting holdings is not the same as protecting the balance**: the prune rule above keeps the `AccountHolding` rows through a price outage, but the EUR *total* is computed from prices — so a total outage summed to `0` and that zero was written to `account.currentBalance` **and** stamped as today's `BalanceSnapshot`, flattening the net-worth chart for a transient failure while the holdings sat there looking healthy. `sync` now refuses: if the wallet holds assets (`amount > 0`) and **none** of them priced, it throws `SyncException` (422) so nothing is written and the wallet keeps its last balance — the same "never read a failed fetch as 0" rule the RPC layer follows. A **partial** outage still records a partial total deliberately, because refusing to snapshot whenever any single obscure token is unpriced would block snapshots indefinitely; those are logged per ticker. A genuinely empty wallet prices nothing and still syncs, since nothing is held.
- **`toEur` returns the balance UNCONVERTED when no rate is available**, and logs ERROR saying so. The number is then wrong, not merely missing — a USD balance flows into net worth as though it were EUR. It is deliberately not thrown and deliberately not zeroed: `toEur` backs `liveBalanceEur`, the dashboard and the history charts, so throwing would 500 all of them on one missing FX rate, and substituting zero would understate net worth just as quietly. Changing the number either way shifts every user's totals; making the failure loud does not.
- **Token failures are summarised per network**: each skipped token logs its own WARN, but with seven chains queried concurrently those lines scatter. `fetchNetwork` also emits one `N of M tokens failed` line per network, because the ratio is the part that matters — the sync still succeeds on the native balance, so without it a user can conclude their tokens are gone when the calls merely failed.
- **Retry is transport-only, and bounded**: the shared `rpc(...)` helper retries twice with a 200ms backoff, but `filter(ex -> !(ex instanceof WalletRpcException))` excludes JSON-RPC error payloads — a node answering "method not found" or "rate limited" answers the same way three times, so retrying only delays the failure and triples load on an endpoint that may already be throttling. `onRetryExhaustedThrow` re-surfaces the *original* cause rather than reactor's `RetryExhaustedException`, which would otherwise miss `WalletSyncService`'s expected-failure catch and read as a bug. Pinned by the `transientTransportFailure_*` / `jsonRpcErrorIsNotRetried` tests — before those, deleting `retryWhen` outright broke nothing.
- **The Bourse Direct override moves both sides at once**: when a position fails to price, that account falls back to the broker's authoritative EUR total — which covers *every* line, so the cost basis it is paired with must cover every line too. Pairing the full total with the partial basis reports a gain the size of the unpriced positions' cost: the same value/cost mismatch as the -85% incident with the sign flipped, and `dailySnapshots` would write it into `balance_snapshot`. `AccountService.valuation` keeps a second running total over all holdings for exactly this branch.
- **An unpriced holding leaves the cost basis too, not just the value**: skipping is deliberate (never value an asset at a guess), but `liveBalanceEur` used to drop it while `calculateInvestedAmount` kept its full purchase cost — so the account reported a loss the exact size of the missing positions. A Meria account of 448 EUR whose BTC and SOL failed to price displayed **-85%** on an untouched portfolio. Both figures now come from one `AccountService.valuation()` pass over one quote map, so whatever leaves one side leaves the other by construction; `AccountServiceTest.anUnpricedHoldingLeavesTheCostBasisTooNotJustTheValue` pins it. Reaching that branch at all is now the residual case — `PriceService` serves the last recorded price first — and it still logs a WARN per excluded holding so the dip is explicable. A holding with **no ticker** is different and unchanged: there was no lookup to fail, its value comes from the provider's own figures, and its cost basis still counts.
- **Read paths must price a CRYPTO account crypto-only**: `AccountService.valuation` routes `AccountType.CRYPTO` through `getCryptoQuotes`. It used to use the generic route, which meant the *read* side could value a coin at the share price of the same-named equity while the *write* side (`CryptoExchangeSyncService`) carefully refused to — the two disagreeing about the same account.
- **Adapter coverage is verified at startup**: `WalletSyncService.verifyAdapterCoverage` (`@PostConstruct`) fails boot if any `Chain` has no `WalletPort` bean, or more than one. Dispatch is a `findFirst` over injected beans, so without it a missing adapter would surface only when a user syncs that chain (a 422 reading "isn't supported yet"), and a duplicate would silently let whichever bean loaded first win. This is what makes `WalletPort.chain()` returning `Chain` rather than `String` actually pay off.
- **Concurrent syncs of one wallet converge on one account**: `resolveAccount` does find-then-insert, so a double-click (or a user sync racing the scheduler) can have both callers see no account and both insert — two accounts for one wallet, splitting its snapshot history. The insert is wrapped so a `DataIntegrityViolationException` re-resolves to the winning row instead of failing. Note this **narrows** the window rather than closing it: `account.external_account_id` carries only a plain partial index (V6), not a unique one, so there is no constraint guaranteeing the loser actually fails. Closing it properly needs a dedup pass plus a unique index across every sync path (bank, exchange, Finary, wallet), which is deliberately out of scope here.
- **Case conversions on identifiers must pass `Locale.ROOT`**: `WalletSyncService` builds the account key as `"wallet_" + chain.name().toLowerCase(Locale.ROOT) + "_" + id`, and tickers are upper-cased the same way before they meet `CoinGeckoPriceProvider`'s (likewise `Locale.ROOT`-keyed) price map. Under a Turkish/Azeri default locale a bare `toLowerCase()` turns `BITCOIN` into `bıtcoin` (dotless ı), so `resolveAccount` would miss its existing row, create a *second* account, and strand the original's snapshot history and `averageBuyIn`. No **repair** migration exists for already-mangled keys: the shipped container pins `LANG=en_US.UTF-8` so no Docker deployment can have written them, and a migration matching a mangled pattern risks corrupting correct rows. Only a JAR run directly on a Turkish-locale host could be affected.
- **BSC stablecoins are 18-decimal**: USDT/USDC on BNB Chain use 18 decimals, unlike the 6-decimal versions on Ethereum/Polygon/Arbitrum/Optimism/Base. Decimals live per-token in the `EvmNetwork` registry; get them right or the amount is off by orders of magnitude.
- **New EVM token/network = two edits**: add the `Erc20Token`/`EvmNetwork` row in `EvmWalletAdapter` **and** ensure its symbol maps to a CoinGecko id in `CoinGeckoPriceProvider.TICKER_TO_ID`, or the balance is fetched but dropped from the EUR total.
- **Addresses are validated up front for the error message, not for durability**: `WalletSyncService.addWallet` guards a null chain and a blank address, then calls `WalletPort.validateAddress(trimmed)`, all before attempting a sync. The payoff is a **400** naming the expected format instead of a **422** "try again later" for input that can never succeed, plus no wasted RPC/explorer round-trip. It is *not* what keeps a bad row out of the database — `WalletSyncService` is class-level `@Transactional`, so a throwing `sync()` already rolls the insert back. Don't delete the guards believing the transaction makes them redundant, and don't delete the transaction believing the guards do. The port default is a no-op (Bitcoin's several encodings and Solana's base58 aren't cheaply checkable offline); `EvmWalletAdapter` overrides it. Note the two-exception split: `validateAddress` throws `IllegalArgumentException` (**400**, user typo) while the fetch-time `requireEvmAddress` throws `WalletRpcException` (**422**, an already-stored address that no longer parses). Same regex, different blast radius.
- **A 429 pauses every CoinGecko call, not just the one that hit it**: the free tier is throttled per IP and counts the calls it rejects, so answering a rate limit with more traffic prolongs it. `CoinGeckoPriceProvider` records `rateLimitedUntil` (from `Retry-After` when it is sane, else 60 s, capped at 15 min) and returns no prices without sending a request while it holds — spot, historical and intraday alike, since they share one budget. Callers see the usual empty map and fall back to the last recorded price.
- **CoinGecko: expected outages return no prices; genuine bugs propagate**: the three fetch methods swallow *upstream* failures and return an empty map. Callers treat a missing price as "not valued this cycle", never "not held" (see the prune gotcha above), so a price blip must not fail a wallet sync. Severity is graded by *whose problem it is*: **WARN** for anything we can only wait out (429 rate-limit, 5xx outage, timeout with its duration, unreachable API), **ERROR** for a request we built wrong (400/404 — likely a bad `TICKER_TO_ID` coin id). Every line names the tickers, and bodies are truncated to 200 chars so one bad gateway's HTML page can't fill the log. **If you alert on ERROR you will not be paged for a CoinGecko outage** — deliberate, since these run on a scheduler per ticker and would otherwise emit ERROR for hours; alert on WARN for outage visibility. Anything that is *not* an upstream failure (NPE, `ClassCastException`, a parse defect) is **rethrown**: a bug that presents as "no prices" is indistinguishable from a quiet outage and would never get fixed. Keep the empty-map contract for the upstream cases, or the prune logic becomes destructive.
- **The catch must unwrap before classifying**: `Mono.timeout()` signals a *checked* `TimeoutException`, so `.block()` delivers it wrapped in a reactor `ReactiveException`. Matching on the declared type without `Exceptions.unwrap` silently stops catching timeouts — the most common real CoinGecko failure — and, now that unexpected types are rethrown, converts them into propagating errors. `CoinGeckoPriceProviderTest.timeout_returnsNoPrices_andWarns_despiteReactorWrapping` pins this.
- **Rethrowing is only safe because the batch loops are guarded**: `SchedulerService.dailySnapshots` (per account), `SchedulerService.refreshPrices` (one global pass) and `PriceService.backfillHistoricalPrices` (per ticker) each try/catch their loop body. `dailySnapshots` is `@Transactional`, so without its guard one failing price lookup would abort every remaining account **and member** and roll back the snapshots already written in that run; `backfillHistoricalPrices` runs from `PriceBackfillRunner`, an `ApplicationRunner`, where an unguarded throw fails Spring Boot startup outright. Do not remove a guard without also restoring the swallow-everything catch. Each guard logs at **ERROR**, not WARN: since expected outages never reach them, anything caught there is a bug, and a WARN would re-hide exactly what the adapter now rethrows to make visible. `PriceServiceTest.backfill_continuesPastAFailingTicker_andLogsItAtError` pins both halves (loop continues, level is ERROR).
- **The intraday chart degrades per ticker, not wholesale**: `HistoryService.buildIntradayHistory` guards each ticker's `getIntradayPricesEur` call, logging ERROR and omitting that series. Without it a single bug 500s the entire `/api/history/intraday` response. Note the fix deliberately lives in the *loop*, not in `PriceService.getIntradayPricesEur` — wrapping the service method and returning an empty map would re-hide bugs on that path, which is the thing this whole arrangement exists to prevent.
- **`prices` payloads are parsed defensively**: `forEachPricePoint` checks each step (`prices` is a list, each entry is a >=2 list, both slots are `Number`) instead of casting, skipping malformed points and warning **once per call** rather than per point. A wholesale format change degrades to empty data plus one warn, instead of a `ClassCastException` propagating into the snapshot batch.
- **Deleting a wallet's account removes the wallet, and the wallet row is what used to resurrect it**: `WalletSyncService.resolveAccount` was the **only** connector missing the `existsSoftDeletedByExternalAccountIdAndMemberId` guard every other one applies, so a user who deleted their wallet account from the Accounts page got it back on the next scheduled resync — with a *new* row, since `findByExternalAccountIdAndMemberId` cannot see through `@SQLRestriction("deleted_at IS NULL")`. Ten deletions left ten accounts on one `external_account_id`, each starting the balance history over; V77 merges them. Both halves are now fixed: the guard is in place, and `AccountConnectionService` deletes the `wallet_address` row with the account so nothing is left syncing. The skip is propagated as an `Optional` rather than an exception because `resyncAll` must count it as **skipped** — going through `sync()` would raise the 422 there and log an ERROR every scheduled run for a state the user chose deliberately.
- **`removeWallet` and `removeExchange` soft-delete their account, they do not delete the row**: `balance_snapshot` is `ON DELETE CASCADE` on `account`, so the row delete these used to do erased the wallet's or exchange's entire net-worth history — silently, and irreversibly, on what reads like a disconnect. It also left nothing for the resurrection guard to find, so the account could immediately be rebuilt. Both now stamp `deletedAt` and save.

## Tests

> `CryptoEncryptionTest` and `BitcoinKeyUtilsTest` were listed here for a long time without existing. AES-GCM round-tripping and BIP32 derivation are still untested — treat that as a known gap, not as coverage.

- `CryptoExchangeSyncServiceTest` -- credential contract: blank key rejected, missing secret rejected for an exchange that needs one and stray secret rejected for one that doesn't (both **before** `testConnection`, so no wasted round-trip), blank secret normalised to `NULL` for a single-key exchange, Binance still requires both, a failed adapter marks the session `ERROR` without touching `accountService` (no snapshot, no holdings), and an exchange with no adapter fails as a readable 422. Plus the two refusals and their messages: an existing account's balance is left standing when nothing can be valued (**stub the account**, or the test runs the first-sync path and passes for an unrelated reason), and a deleted account tells the user to remove the session — both assert the specific text, since the generic catch used to replace it with "try again later", and that no session write happens on either path
- `CryptoExchangePositionRepositoryTest` -- the delete-then-reinsert rewrite against the real `uq_crypto_exchange_position`, the exact quantities it leaves behind, a product no longer held disappearing, and — with a second seeded account — that the bulk delete filters by account rather than emptying the table
- `MeriaAdapterTest` -- aggregation across wallets/stakings/lendings per currency, staking counted as `amount` with accrued interest **not** added on top, a contract with no reward fields at all, a reward above the quantity or below zero reported with no decomposition at all (and a merge with one such contract staying undecomposed), symbols upper-cased under a forced Turkish locale, zero/blank entries dropped; and the all-or-nothing contract: every endpoint × {401, 403, 404, 429, 500} throws, as do `success:false` on HTTP 200, a reactor-wrapped timeout and a non-JSON (Cloudflare) body — while `data: []` **and an empty body on a 2xx** both yield an empty list without throwing. Also pins that only an `API-KEY` header is sent (no `Authorization`, no `X-MBX-APIKEY`, no `signature` query), that the secret argument is ignored, and that no message or log line contains the API key
- `MeriaAdapterWiringTest` -- Spring picks the production constructor with and without `app.meria.base-url` set, and the production client reads a staking payload larger than the framework's default buffer (served over a real socket — the `ExchangeFunction` fixtures decode with the stub's strategies and cannot see that configuration)
- `PositionsByProduct.test.tsx` -- positions grouped per product, the same asset kept in both its spot and staking sections, principal/interest columns only where the exchange reports yield, no subtotal at all when a product is fully *or partly* unvalued (an EUR 0 there reads as "worth nothing"), and the recorded-price marker naming its own day under a time zone behind UTC
- `WalletSyncServiceTest` -- unit tests for wallet sync
- `JsonRpcResponseTest` -- envelope validation: valid/zero/empty-array results returned; null/error/missing/explicit-null throw
- `WalletSyncServiceTest` -- RPC error wrapped as `SyncException` (wallet not marked synced), empty balances throw, `resyncAll` summary reports failed chains, holdings pruned to the currently-held tickers, held assets kept when prices are unavailable, malformed address rejected before anything is persisted
- `WalletEvmMigrationTest` -- Testcontainers/PostgreSQL: the `ETHEREUM` &rarr; `EVM` migration converts the wallet, rewrites `external_account_id` to exactly what `WalletSyncService` recomputes, preserves holdings and their `averageBuyIn` cost basis, leaves other chains and unrelated external ids untouched, renames the default `"ETHEREUM Wallet"` account while preserving a user label — including the trap case of a label that is *itself* `"ETHEREUM Wallet"` (V55) — and is idempotent when the real migration file is replayed on already-migrated data
- `EvmWalletAdapterTest` -- concurrent multi-network fan-out via a content-routed `EvmRpc` stub: aggregation by symbol (native across chains, token across chains), token `balanceOf` at correct decimals, native probe failure fails the whole sync for both JSON-RPC-error and **transport** errors (never read as 0), transport error on a token skipped (not fatal), malformed-hex / missing-`result` / null-body all throw, empty-`0x` call as zero, seeded native leads for a token-only/empty wallet, malformed address rejected before any RPC call, and `validateAddress` rejecting as a 400 while accepting EIP-55 mixed case
- `SolanaWalletAdapterTest` -- SOL + SPL parsing, unknown-mint drop, RPC-error fails sync, non-array/malformed token skipped (non-fatal)

## Links

- Related ADR: [EVM multichain wallets](../decisions/2026-07-17-evm-multichain-wallets.md)
- Related ADR: [AES-256-GCM for crypto secrets](../decisions/2026-03-01-aes-gcm-crypto-secrets.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Logos on account cards](./bank-logos.md) — Meria's mark ships in `frontend/public/exchanges/`, mapped on `Account.provider`; a wallet's account instead carries a `logoKey` (`blockchain` by default, `ledger` if the user picks it)
- Related feature: [Encryption at rest](./encryption-at-rest.md)
- Related feature: [Price service](./price-service.md)
