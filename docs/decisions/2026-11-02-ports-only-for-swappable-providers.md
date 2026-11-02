# ADR: Ports only for swappable providers

> Date: 2026-11-02
> Status: ✅ Active

## Context

The [ports and adapters ADR](./2026-01-01-ports-and-adapters.md) put external integrations behind interfaces in `port/` so that a provider can be swapped. Sixteen of those interfaces never earned the seam: each had exactly one implementation and one consuming service (`AmexPort`, `AmundiPort`, `BourseDirectPort`, `BoursoPort`, `CicPort`, `CmesPort`, `CorumPort`, `DegiroPort`, `FortuneoPort`, `SofidyPort`, `TradeRepublicPort`, `DividendHistoryPort`, `NasdaqDividendPort`, `HousingPriceIndexPort`, `PropertyValuationPort`, `IbkrFlexPort`). Each one duplicated the adapter's method list and carried its records away from the only class that fills them.

## Decision

A port exists only when there are at least two implementations, or a real swap seam (for example `BankConnectorPort`, `CryptoExchangePort`, `WalletPort`, `PriceProviderPort`). A single-implementation connector client is a concrete `@Component` in `adapter/` (or its feature package), injected directly into the service. Its request and response records are nested public types of that class. `CaisseEpargnePort` and `RevolutPort` keep their port although each has one implementation: several services share them (`CaisseEpargnePerService`, `SyncProgressService`), and #336 only removed the single-caller ones. Tests mock the class (Mockito's default inline mock maker mocks concrete classes).

The 16 listed interfaces are deleted. `PropertyValuationService` takes the single `CeremaDv3fValuationProvider`; it still asks `supports(...)` and reports `UNSUPPORTED_AREA` when the area is not covered.

## Alternatives considered

### Keep every port

- **Pros**: uniform layout; a swap needs no service change
- **Cons**: 16 files that only mirror one class; no swap has ever used them

### Delete all ports

- **Pros**: fewest files
- **Cons**: removes the seams that do have several implementations and callers

## Reasoning

An interface is worth its file when something varies behind it. Where nothing does, it only adds a second place to edit per change.

## Trade-offs accepted

Adding a second provider to a connector now means extracting the interface at that point, which touches the one consuming service and its test.

## Consequences

- `port/` keeps the shared contracts (`BankConnectorPort`, `CryptoExchangePort`, `WalletPort`, `PriceProviderPort`, ...) and the shared sidecar types (`SidecarErrorCode`, `SidecarInitiate`, `SidecarTransaction`).
- Records such as `CorumAdapter.Snapshot` or `IbkrFlexClient.IbkrPosition` live in the class that produces them.

## Supersedes

Partially supersedes [Ports & Adapters](./2026-01-01-ports-and-adapters.md): the rule that services depend only on port interfaces now applies where a port exists.
