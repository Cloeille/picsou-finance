# ADR: EVM multichain wallets — one address, many chains

> Date: 2026-07-17
> Status: ✅ Active

## Context

Picsou tracked on-chain wallets as `Chain ∈ {BITCOIN, ETHEREUM, SOLANA}`, one wallet = one chain = one address, native coin only. A user asked to add **BNB (BNB Chain)**, which surfaced a deeper modelling problem: a hardware wallet (Ledger) derives a single secp256k1 key exposing the **same `0x` address** on every EVM chain — Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base, Avalanche C-Chain. Under the old model, tracking BNB meant re-typing the same address under a second chain, and it still wouldn't surface any tokens (only Solana returned tokens; the Ethereum adapter was native-ETH-only).

The user also referenced Etherscan/BscScan, whose keyed APIs auto-detect all balances and tokens across chains — which raised the question of whether to adopt an API-key explorer or stay with the project's existing **keyless public RPC** convention.

## Decision

Model the EVM family as a **single fan-out chain**. Replace the `ETHEREUM` enum value with `EVM`; one `EvmWalletAdapter` iterates a registry of EVM networks and, for a single `0x` address, calls `eth_getBalance` (native) plus `eth_call balanceOf` (curated ERC-20/BEP-20 tokens) on each. Balances are **aggregated by symbol** across networks. Data comes from **keyless PublicNode RPCs** (no API key), and token coverage is a **curated contract list per chain** (like Solana's `KNOWN_MINTS`). A Flyway migration (`V56`) converts existing `ETHEREUM` wallets and their accounts to `EVM` in place.

## Alternatives considered

### Per-chain wallet entries (add `BNB` as a flat sibling chain)

- **Pros**: Smallest change; matches the existing one-chain-per-adapter pattern exactly.
- **Cons**: User re-types the same `0x` address for every chain; no shared model; N wallet rows and N accounts for one physical address; still native-only unless each adapter grows its own token logic.

### Etherscan V2 / BscScan API-key explorer

- **Pros**: One key covers many chains via `chainid`; auto-discovers **all** tokens with no curation; richest data.
- **Cons**: Requires an API key — a new env var, setup docs, and rate limits (5 req/s free tier). Breaks the deliberate keyless convention (`docs/features/crypto-tracking.md` "Technical choices": Blockstream/PublicNode chosen precisely because they need no key). Adds an external account dependency to a self-hosted app.

### EVM fan-out over keyless RPC (chosen)

- **Pros**: One address → all chains, directly matching the hardware-wallet reality. `eth_getBalance`/`eth_call` are byte-identical across EVM chains, so one adapter parameterized by `{rpcUrl, nativeSymbol, tokens}` covers every network with no API key. Honors the existing convention. Fits the current abstractions (`WalletPort` already returns a list; `AccountHolding` is per-ticker).
- **Cons**: Tokens must be curated per chain (keyless `balanceOf` needs the contract up front); a sync makes many more RPC calls than the old single-chain path (one native + curated tokens per network), mitigated by running them concurrently with a per-call timeout + retry.

## Reasoning

The decisive fact is that an EVM address is *physically* one thing across many chains — the shared-address problem is intrinsic to how the user's Ledger works, so the model should reflect it. Keyless RPC keeps the self-hosted, zero-setup property the project already committed to, and native-coin fan-out (the 80% case, including BNB) costs nothing extra because the JSON-RPC calls are identical per chain. Curated tokens are an acceptable trade for avoiding an API-key dependency; the curated list mirrors an already-accepted pattern (Solana `KNOWN_MINTS`).

## Trade-offs accepted

- **No automatic token discovery.** Only curated ERC-20/BEP-20 contracts are tracked; a token not in the registry is invisible until added. Full discovery is deferred to a possible future Etherscan-backed provider.
- **Fan-out RPC cost.** A sync makes ~7 networks × (1 native + a few token calls). These run **concurrently** (reactive `Flux` fan-out, per-call timeout + small retry), so wall-clock is roughly the slowest chain, not the sum — but it is still many more calls than the old single-chain path.
- **All-or-nothing on native failure.** Any one chain's native probe failing aborts the whole sync (see Consequences). This trades availability for correctness: with 7 chains a single blip fails the sync more often than the old single-chain path, but retries absorb transient blips and a failed sync safely retains the last balance rather than under-reporting.
- **Fixed network set.** The enabled EVM networks are hardcoded (extensible by a registry row); per-user enable/disable is out of scope for now.

## Consequences

- `Chain` enum: `{BITCOIN, SOLANA, EVM}`. `EthereumWalletAdapter` is removed; `EvmWalletAdapter` replaces it.
- Migration `V56__wallet_ethereum_to_evm.sql` converts `wallet_address.chain` and rewrites `account.external_account_id` (`wallet_ethereum_<id>` → `wallet_evm_<id>`) so migrated wallets keep their account, snapshots, and holdings. `V57__wallet_evm_account_name.sql` follows up on the display name: `resolveAccount` never refreshes an existing account's `name`, so an unlabelled wallet named `"ETHEREUM Wallet"` would have kept that name forever while tracking BNB, POL and AVAX too. It keys on `wallet_address.label IS NULL` joined through the rewritten `external_account_id`, **not** on the display name — `resolveAccount` uses a user's label verbatim, so a wallet labelled `"ETHEREUM Wallet"` is indistinguishable by name from an auto-named one, and renaming it would destroy a label nothing could restore.
- Failure isolation is asymmetric: a network whose **native** probe fails fails the whole sync (`422`, wallet keeps its last balance) so a down chain is never silently dropped from net worth; a single bad **token** `balanceOf` skips only that token (per-asset, like Solana). Transport-level errors (connection reset, HTTP 5xx, timeout, retries exhausted) are wrapped as `WalletRpcException` so they classify as expected failures (WARN/422) and the token-skip path catches them — not as unexpected bugs.
- `WalletSyncService` prunes `AccountHolding` rows for assets no longer **held** (keyed on the adapter's positive on-chain balances, never on which prices resolved), so a sold/moved token can't inflate the live balance — while a transient CoinGecko outage can't delete a still-held asset or its cost basis.
- `CoinGeckoPriceProvider.TICKER_TO_ID` gains `POL` and stablecoin tickers (`USDT`, `USDC`, `DAI`, `EURC`) — also fixes previously-unpriced Solana stablecoins.
- `WalletPort` gains a `validateAddress` hook (no-op by default) so a chain that *can* check its address format offline rejects a typo as a `400` naming the expected format, instead of a `422` "try again later" for input that can never succeed — and without a wasted RPC round-trip. (It is not a durability guard: `WalletSyncService` is `@Transactional`, so a failing sync already rolls the insert back.)
- Migrations `V54`/`V55` are covered by `WalletEvmMigrationTest`, which introduces **Testcontainers** to the project — H2 cannot run the PostgreSQL-flavoured migration chain, and the account-id rewrite is the step whose silent failure would cost users their snapshot history and cost basis. See [testing conventions](../conventions/testing.md#testcontainers--only-for-real-postgresql-behaviour).
- Frontend: `ChainType` becomes `'BITCOIN' | 'EVM' | 'SOLANA'`; both chain pickers show `EVM` with a hint that one address covers all EVM chains.

## Supersedes

Partially supersedes the native-ETH-only wallet behaviour described in earlier revisions of [crypto-tracking.md](../features/crypto-tracking.md). Builds on [Ports and adapters](2026-01-01-ports-and-adapters.md).
