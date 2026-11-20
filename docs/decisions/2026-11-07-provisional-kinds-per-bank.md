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

- `Site.provisional_kinds` lists the kinds a bank has seen in few live runs: `{"PEL", "LEP"}` on Caisse d'Epargne and `{"SAVINGS", "PEL", "TERM_DEPOSIT", "COOPERATIVE_SHARES"}` on Banque Populaire.
- For such a kind, a synthesis entry the parser cannot read is listed in `unsupported` (family and sub-family code, with
  the failing field path in the sidecar log) instead of failing the sync, and a failed transactions read still imports
  the account from its synthesis entry. Every other kind keeps failing loudly.
- The same set turns on the kind's sub code (`Site.unsupported_families`): without it, `3/02`, `3/04`, `3/27`, `4/09` and `4/10` stay unsupported.
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
- Caisse d'Epargne's `Site` enables `PEL` (`3/04`) and `LEP` (`3/27`) while keeping `3/02`, `4/09` and `4/10` unsupported; Banque Populaire's `Site` enables `SAVINGS` (`3/02`, Compte sur Livret), `PEL` (`3/04`), `TERM_DEPOSIT` (`4/09`, term deposit) and `COOPERATIVE_SHARES` (`4/10`, cooperative shares) while keeping `3/27` unsupported.
- Banque Populaire's `Site` also has `LOAN` (`6/12`, `6/13`) as a provisional kind, since its loan clients were never observed live: a loan app refusal never ends the session, an unreadable detail imports the loan without it (`loan: null`), and an unreadable entry is demoted to `unsupported`.
