# ADR: Exchange credentials carry an optional API passphrase

> Date: 2026-11-20
> Status: ✅ Active

## Context

The upcoming OKX connector requires a user-chosen API passphrase alongside the key and secret.

## Decision

Pass an `ExchangeCredentials` record to the adapter for connection tests and position fetching.
Its string representation redacts all credentials. The service validates the adapter's requirements.
Store each passphrase in a dedicated nullable `api_passphrase` column encrypted by `CryptoEncryption`.

## Alternatives considered

- A third positional `String` keeps the signatures familiar but allows the nullable secret and passphrase to be swapped.
- Packing the passphrase into `api_secret` reuses the column but requires a combined format and a reader for legacy secrets.

## Reasoning

Named accessors make each field explicit. A dedicated column preserves the existing secret format.

## Trade-offs accepted

Adapter callers must construct the record. The schema gains a nullable column.

## Consequences

V154 leaves the new column null on existing sessions. Future adapters can opt in with `requiresApiPassphrase()`.
