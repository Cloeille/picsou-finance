# ADR: Ports & Adapters (Hexagonal Architecture)

> Date: 2026-01-01
> Status: ✅ Active

## Context

Picsou integrates with many external services: bank providers (Enable Banking, Powens), brokers (Trade Republic), crypto exchanges (Binance), blockchain RPCs (Bitcoin, Ethereum, Solana), and price APIs (CoinGecko, Yahoo Finance). These integrations may need to be swapped, replaced, or extended over time. The codebase needs a clean separation between business logic and external dependencies.

## Decision

Use hexagonal architecture (ports & adapters) with 5 port interfaces in the `port/` package. Controllers and services depend only on port interfaces; adapter implementations are injected by Spring. Adapters are never imported directly by the service layer.

The 5 port interfaces:

| Port | Implementations |
|------|-----------------|
| `BankConnectorPort` | `EnableBankingBankConnector`, `PowensBankConnector` |
| `TradeRepublicPort` | `TradeRepublicAdapter` |
| `CryptoExchangePort` | `BinanceAdapter`, `MeriaAdapter` |
| `WalletPort` | `BitcoinWalletAdapter`, `EvmWalletAdapter`, `SolanaWalletAdapter` |
| `PriceProviderPort` | `CompositePriceProvider` (`@Primary`, routes crypto → CoinGecko, rest → Yahoo), delegating to `CoinGeckoPriceProvider` and `YahooFinancePriceProvider` |

Swapping a provider means implementing the port and swapping the `@Primary` bean.

## Alternatives considered

### Direct service-to-API calls

- **Pros**: Simpler initial setup, fewer classes
- **Cons**: External API details leak into business logic; swapping a provider requires modifying service classes; untestable in isolation

### Spring Events / event-driven decoupling

- **Pros**: Fully async, loosely coupled
- **Cons**: Over-engineered for a single-user sync app; harder to trace request flows; no clear contract between producer and consumer

## Reasoning

Ports & adapters provide explicit contracts (port interfaces) that make it easy to swap providers without touching business logic. The pattern fits naturally with Spring DI -- ports are interfaces, adapters are `@Component` beans, and `@Primary` handles provider selection. The cost is a few extra files per integration, but the benefit is clean testability and provider independence.

## Trade-offs accepted

- More interfaces and files to maintain (one port + one adapter per integration)
- All implementations of a port must conform to the same contract, which may feel constraining when providers have very different capabilities

## Consequences

- New providers can be added by implementing the relevant port and registering as a Spring bean
- Services can be unit-tested by mocking port interfaces (no external API calls needed)
- The `adapter/` package contains all external integration details; changes to external APIs are isolated there
- `@Primary` is used for provider selection (e.g., Powens over Enable Banking when configured)

Note: This decision was made during initial development. This ADR is documented retroactively.
