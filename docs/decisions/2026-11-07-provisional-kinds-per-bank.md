# ADR: Contract kinds seen in few live runs are enabled per bank and demoted, not fatal

> Date: 2026-11-07
> Status: ✅ Active

## Context

The Caisse d'Epargne sidecar and the Banque Populaire sidecar share one parser and fetcher (`services/base/bpce`).
A contract family is only mapped to a kind once it has been seen on a real account, because the bank's codes are
never guessed. The PEL (`3/04`) was the first kind backed by a single live run: a parser strict about it would let one
odd field in a rarely held contract fail the whole synchronisation, current account included, while a parser lenient
about it everywhere would change what Banque Populaire does with a code nobody has seen there.

## Decision

- `Site.provisional_kinds` lists the kinds a bank has seen in few live runs. Only the Caisse d'Epargne site sets it.
- For such a kind, a synthesis entry the parser cannot read is listed in `unsupported` (family and sub-family code, with
  the failing field path in the sidecar log) instead of failing the sync, and a failed transactions read still imports
  the account from its synthesis entry. Every other kind keeps failing loudly.
- The same set turns on the kind's sub code (`Site.unsupported_families`): without it, `3/04` stays unsupported.
- Contracts still unsupported are shown as `family/sub` in the Sync tab, and logged once per sync with a digit-masked
  id shape (letters and the catalogue name only with `CE_LOG_PRODUCT_DETAILS=1`), so the next product can be identified
  from a real sync.

## Alternatives considered

### Strict for every kind

- **Pros**: one rule, no leniency to reason about.
- **Cons**: a rarely held contract with one unexpected field breaks the sync of every account.

### Lenient for every site

- **Pros**: no per-site configuration.
- **Cons**: changes Banque Populaire's behaviour for codes never observed there.

## Reasoning

A bank-connector change must not alter another bank, and a sync must not fail for a product that only one live run backs.
Demotion keeps the problem visible (unsupported list, field path in the log) without blocking the supported accounts.

## Trade-offs accepted

- An account already synced that turns unreadable keeps its last values and shows up in the unsupported list, since rows are never deleted.
- A failed transactions read of such an account is only logged by the sidecar; showing it needs a stored field and a Sync-tab line for every kind (the backend ignores `unavailable` today).

## Consequences

- A new kind seen on one bank is added to that bank's `provisional_kinds`, then moved to a strict kind once it has been observed repeatedly.
- Banque Populaire's `Site` sets none: its `3/04` and `3/27` contracts stay unsupported.
