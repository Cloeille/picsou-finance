# ADR: Exchange credentials carry an API passphrase and region when required

> Date: 2026-11-20
> Status: ✅ Active

## Context

The upcoming OKX connector requires a user-chosen API passphrase alongside the key and secret.
Its API keys are issued separately by the Global, EEA and US entities.

## Decision

Pass an `ExchangeCredentials` record to the adapter for connection tests and position fetching.
Its string representation redacts credentials. The service validates the adapter's requirements.
Store each passphrase in a nullable `api_passphrase` column encrypted by `CryptoEncryption`.
When the adapter declares region codes, ask the user to choose one when connecting, with no default.
Store the normalized code in a nullable plaintext `region` column on the connection.

## Alternatives considered

- A third positional `String` keeps the signatures familiar but allows the nullable secret and passphrase to be swapped.
- Packing the passphrase into `api_secret` reuses the column but requires a combined format and a reader for legacy secrets.
- The maintainer rejected an instance-wide `*_BASE_URL` environment variable in #622. One Picsou instance can serve members with accounts at different regional entities, and each member knows where their account lives.

## Reasoning

Named accessors make each field explicit. A dedicated column preserves the existing secret format.
A regional API key exists only on the issuing entity's host, so its region is part of the key's identity.

## Trade-offs accepted

Adapter callers must construct the record. The schema gains two nullable columns.

## Consequences

V154 leaves both columns null on existing sessions. Future adapters can opt in with
`requiresApiPassphrase()` and `regions()`. The exchange status API exposes the selected region.
