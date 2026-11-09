# ADR: Wise jars through a personal API token, beside Enable Banking

> Date: 2026-11-03
> Status: ✅ Active

## Context

Wise lets a customer set money aside in jars, which Wise calls `SAVINGS` balances. Enable
Banking links Wise through PSD2 and returns the current balances only, so jars never reach
Picsou and the member's Wise net worth is short by whatever sits in them. Revolut pockets had
the same gap (see the [Revolut pockets ADR](./2026-06-28-revolut-pockets-reconstruction.md)).

Wise has a public API. On a live French personal account, a read-only personal token:

- lists profiles (`GET /v2/profiles`) and balances, jars included
  (`GET /v4/profiles/{id}/balances?types=SAVINGS`);
- is refused balance statements with HTTP 403 and an `x-2fa-approval` challenge. Wise documents
  statements as unavailable to personal tokens outside the US, Canada, Australia, New Zealand,
  Singapore and Malaysia;
- reads the activity feed, but its amounts are display strings with no sign and no balance id.

## Decision

Add a Wise connector on the SimpleFIN shape: its own table (`wise_connection`), service, and
`/api/wise` endpoints. One personal token per member, encrypted at rest. It imports **jars
only**, as `SAVINGS` accounts, balance only. Wise current balances and their transactions keep
coming from Enable Banking.

Jars are stamped `provider = "Wise Jars"` with external ids `wise_jar_{balanceId}`. The provider
string differs from Enable Banking's, which stamps the institution name (`Wise`), so Sync All and
account deletion never mistake one connection for the other.

## Alternatives considered

### Import every Wise balance through the token

- **Pros**: One Wise connection covers everything.
- **Cons**: A member who already links Wise through Enable Banking gets each current balance
  twice, and the token copy has no transactions. Nothing in the balance response matches an
  Enable Banking account (no IBAN), so the duplicate cannot be detected.

### Reconstruct jars from transfers on the Enable Banking account

- **Pros**: No new credential, the Revolut pockets approach.
- **Cons**: Wise's PSD2 feed would have to name the jar on each transfer, and the result is a
  guess that drifts from the real balance. The API states the balance directly.

### Sign statement requests to get transactions through the token

- **Pros**: The token could then carry the current account with its history, and Wise would be one
  connection instead of two.
- **Cons**: Not possible. Wise answered, when a member tried to add a public key: request signing
  for strong customer authentication is no longer supported on personal accounts, so statements
  cannot be retrieved that way.

### Move the whole Wise account to the token

- **Pros**: One Wise connection.
- **Cons**: The current account would lose its transactions, so budget and cashflow would go
  blank for Wise.

## Trade-offs accepted

- Jars carry no transactions. Moves between the current account and a jar still appear on the
  Enable Banking side as transfers.
- A jar's balance is its `totalWorth`, so an invested jar counts at market value, not at its
  uninvested cash.
- A jar closed in Wise stays in Picsou with its last balance until the member deletes it, like
  any connector's vanished account.
- Wise documents personal tokens for business accounts. A personal account's token works today;
  if Wise stops issuing them, the connector stops with an `NEEDS_REAUTH` status.

## Consequences

- Only `https://api.wise.com` is called, without redirects. The token is checked to be letters,
  digits and dashes before it reaches a header.
- The token is never logged, returned, or exported. The status shows its last four characters.
- Deleting the last `wise_jar_` account removes the connection, consistent with the
  [account-deletion ADR](./2026-08-11-account-deletion-removes-its-connection.md).
- The UI names the split: the Wise Jars tab says it only covers jars, and a Wise connection on the
  Banks tab points to it.
- Wise joins the MCP bank sources: `get_sync_status`, `trigger_bank_sync`, and the full sync.
  HTTP 401 or 403 reports `NEEDS_REAUTH`.
