# ADR: Exchange credentials carry an API passphrase and region when required

> Date: 2026-11-20
> Status: ✅ Active

## Context

The upcoming OKX connector requires a user-chosen API passphrase alongside the key and secret.
Its Global, EEA and US entities use different API domains.

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
The region selects the API endpoint of the entity with which the account is currently registered.
An OKX account that moves to another entity can keep its API key and use the new entity's API domain.
[OKX account migration guidance](https://www.okx.com/en-sg/help/country-of-residence-change).

## Trade-offs accepted

Adapter callers must construct the record. The schema gains two nullable columns.

## Consequences

V155 leaves both columns null on existing sessions. Future adapters can opt in with
`requiresApiPassphrase()` and `regions()`. The exchange status API exposes the selected region.
