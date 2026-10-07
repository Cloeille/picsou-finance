# ADR: SimpleFIN as its own connector, beside Enable Banking

> Date: 2026-10-04
> Status: ✅ Active

## Context

Picsou's bank sync goes through a single `BankConnectorPort` bean. Enable Banking is that bean. SimpleFIN is a different protocol: the member pastes a one-time setup token, Picsou claims an access URL, and one request returns every linked account with its balance and posted transactions. There is no institution catalog, no country list, and no OAuth redirect.

## Decision

Add SimpleFIN as a parallel connector, on the same shape as Interactive Brokers: its own table (`simplefin_connection`), service, and `/api/simplefin` endpoints. One access URL per member, encrypted at rest. Enable Banking stays the `BankConnectorPort` implementation.

Accounts are stamped `provider = "SimpleFIN"` with external ids prefixed `sfin_`, so Sync All and account deletion can see the connection without parsing a bank name. Posted transactions reuse `BankTransactionImportService`'s dedup. Pending transactions are not requested.

## Alternatives considered

### Implement `BankConnectorPort`

- **Pros**: Reuses `SyncService`, the bank wizard, and the requisition lifecycle.
- **Cons**: Spring injects one port. A SimpleFIN implementation would replace Enable Banking, and the port's institution search and OAuth methods have nothing to call.

### A provider switch inside `SyncService`

- **Pros**: One sync entry point.
- **Cons**: The service would import a second protocol's credentials and error handling, which is what the port was meant to prevent. The OAuth requisition model still would not fit a setup token.

## Reasoning

The IBKR connector already proved the shape for "paste a credential, store it encrypted, sync on the daily job." SimpleFIN matches that shape and does not match the OAuth bank wizard. Keeping it off `BankConnectorPort` leaves European bank sync alone.

## Trade-offs accepted

- A second bank-sync code path to maintain.
- No institution search inside Picsou. Linking happens on SimpleFIN Bridge.
- Only SimpleFIN Bridge is supported. A self-hosted SimpleFIN server would need its host added in code.
- Accounts are created as checking accounts, like Enable Banking's, because the protocol has no account type. The member sets savings or credit card in the account form, and a resync never changes it. Balances are stored as reported; Bridge sends card debt as a negative number, which is how Picsou stores a credit card.
- The shared 90-day download repeats known transactions; dedup drops them.

## Consequences

- `SimplefinUrls` only accepts `https://beta-bridge.simplefin.org` for the claim URL and the access URL, so neither a pasted token nor a claim response can point Picsou at another host. Both are checked before any HTTP call.
- The access URL is never logged, returned, or exported.
- Deleting the last `sfin_` account removes the connection, consistent with the account-deletion ADR.
