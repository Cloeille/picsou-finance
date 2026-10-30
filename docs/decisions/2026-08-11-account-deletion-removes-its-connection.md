# ADR: Deleting an account removes the connection behind it

> Date: 2026-08-11
> Status: ✅ Active

## Context

An account and the connection that feeds it had independent lifecycles. `AccountService.delete`
set `deleted_at`; nothing touched the `wallet_address` row, the `requisition`, or the provider
session that produced the account.

That reads as a bug from the outside, and it produced two on this instance:

- A wallet account deleted from the Accounts page came back within the hour. The wallet row
  survived, and `WalletSyncService.resolveAccount` — the only connector that skipped the
  `existsSoftDeletedByExternalAccountIdAndMemberId` guard — inserted a fresh account on every
  scheduled resync. Ten deletions left ten rows for one `external_account_id`, each holding a
  slice of the balance history.
- A deleted bank account left its requisition `LINKED` and syncing. Both it and the wallet kept
  appearing in the dashboard's "Sync accounts" list, offering to sync something the user had
  removed from their tracking.

The guard is now in place everywhere, which fixes the resurrection but sharpens the second
problem: a connection whose account is soft-deleted can *never* produce an account again. What
survives is a row that syncs forever, costs an outbound call per run, and can only mislead.

## Decision

Deleting an account also removes its connection, once no live account is left on that
connection. `AccountConnectionService` owns the rule; `AccountController.delete` and the MCP `delete_account` tool go through it
rather than through `AccountService.delete`.

`deleteAccount` returns the `DeletionImpact` of what it actually removed in the same transaction:
a removal is reported only when a wallet row, exchange session, stored session or requisition was
there to delete. It captures the connection label before removing the connection. The MCP tool returns that
result directly; the REST delete keeps its existing no-content response. `describeDeletion`
remains a separate read-only preview for confirmation, which can become stale before deletion
and must not be reported as the applied result.

"Its connection" is resolved from `external_account_id`, whose namespaces are disjoint —
`wallet_`, `crypto_exchange_`, `amundi_`, `tr_`, `bd_`, `ibkr_`, `sfin_`, `degiro-portfolio` — falling
back to `account.requisition_id` for Enable Banking, whose ids are the bank's own opaque
strings and carry no namespace. V76 adds that column; `SyncService.upsertAccount` had the
requisition in hand all along and persisted only its name.

The "no live account left" test is what keeps this safe: one requisition can back several
accounts, Amundi routinely holds several plans on one session, and Trade Republic writes a cash
and a securities account from a single login.

## Alternatives considered

### Leave both lifecycles independent, and add a "remove" button to each sync row

- **Pros**: no change to deletion semantics; the account and the connection stay conceptually
  separate, which is what they are in the data model.
- **Cons**: leaves the orphan state reachable and undiscoverable — the user deletes an account,
  the connection stays in the list, and syncing it appears to succeed while doing nothing. Puts
  the burden of knowing the two are different on the person least placed to know it.

### Ask in the confirmation dialog, with a checkbox

- **Pros**: nothing is removed without an explicit answer; the user can keep a bank
  authorisation they intend to re-use.
- **Cons**: asks about an implementation detail. "Do you also want to remove the connection?"
  only means something to someone who already knows the two exist separately, and the answer
  that leaves things consistent is always the same one.

## Reasoning

Once no live account is left on a connection, it has no observable purpose: it cannot create an
account, its syncs write nothing, and the only place it appears is a list of things the user can
sync. Removing it makes "delete this account" mean what it looks like it means.

The dialog still names what goes — `GET /accounts/{id}/deletion-impact` returns the connection's
label — because the cost is asymmetric. An on-chain wallet is re-added by pasting an address; an
Enable Banking requisition costs a full OAuth round trip through the bank.

## Trade-offs accepted

- **Deleting the last account of a bank connection discards its authorisation.** Re-adding the
  bank means going through the consent flow again. Accepted: the alternative was a requisition
  that syncs indefinitely and can never produce an account.
- **Accounts the V76 backfill could not attribute keep their connection.** The backfill matches
  `provider` against `institution_name` and skips members holding several requisitions for one
  institution. Those rows link themselves on their next sync; until then their requisition is
  never auto-removed — the safe direction to fail.
- **One more query on the delete path.** `hasOtherLiveAccount` loads the member's accounts and
  resolves each, rather than running a prefix query. Deliberate: `LIKE 'bd\_%'` with an
  unescaped underscore is a single-character wildcard, and Enable Banking's opaque ids are free
  to start with any three characters. Resolving through the same function that decided the
  connection keeps one definition of "same connection".

## Consequences

- `AccountConnectionService` is the only caller of the connectors' removal methods on this path
  (`removeWallet`, `removeExchange`, each `clearSession`, `deleteConnection`). It sits outside
  `AccountService` because the connectors already depend on it, and calling them from there
  would close a Spring dependency cycle.
- An Enable Banking requisition is removed with a single member-scoped `DELETE` that returns
  its row count, not through `SyncService.deleteRequisition`. That method throws when the
  requisition is missing. A requisition deleted concurrently from the sync page would then roll
  back the whole account deletion. With the count, it just reports that nothing was removed.
- Deletion order is fixed: the account is soft-deleted first, so a connector running
  concurrently finds the soft-deleted row and refuses to rebuild it rather than racing the
  removal.
- `removeWallet` and `removeExchange` now soft-delete their account instead of deleting the row.
  `balance_snapshot` cascades on `account`, so the old behaviour erased the connection's whole
  net-worth history — and left nothing for the resurrection guard to recognise.
- V77 merges the duplicate rows already written, re-pointing their history onto the survivor.

## Links

- [`docs/features/crypto-tracking.md`](../features/crypto-tracking.md)
- [`docs/features/bank-sync.md`](../features/bank-sync.md)
