# ADR: CORUM and Sofidy syncs create and link their SCPI accounts

> Date: 2026-10-16
> Status: ✅ Active
> Supersedes: point 5 of [the CORUM sidecar ADR](2026-09-26-corum-scpi-sidecar.md) and point 5
> of [the Sofidy sidecar ADR](2026-09-27-sofidy-scpi-sidecar.md)

## Context

Both ADRs kept account creation in the manual flow: a CORUM or Sofidy fund updated an
account only after the user had created a SCPI account and typed the provider's internal
fund code (`US`, `XL`, `DY`…) on it. Neither portal shows that code clearly.

A real first-time user test (CORUM with two funds, Sofidy with one, no SCPI account) ended
with "sync succeeded" and no shares. The only trace was an INFO line in the backend log.
Every other connector creates its accounts during the sync. SCPI was the only exception, and
nothing in the UI said so ([#232](https://github.com/Cloeille/picsou-finance/issues/232)).

The risks the ADRs named are real: a fund code is not an account, silent creation bypasses
the manual flow, and a code mismatch or a second connection could create a phantom account.

## Decision

A sync reconciles every fund of a complete snapshot, in this order (`ScpiFundReconciler`):

1. **Ignored fund**: nothing is written. Outcome `IGNORED`.
2. **Linked fund**: the linked account is updated through
   `ScpiPositionService.applySyncedPosition`, as before. Outcome `UPDATED`, or
   `PRICE_INCOMPLETE` when the withdrawal price is missing.
3. **Adoption**: a manual SCPI account linked to no provider fund, whose name equals the fund
   label after normalization (case, accents, punctuation), is linked when it is the only
   match. Outcome `LINKED`. Two or more matches link and create nothing. Outcome `AMBIGUOUS`.
4. **Creation**: otherwise a SCPI account is created, named after the fund label, with the
   provider, the fund code, and the shares and withdrawal value applied. Outcome `CREATED`.

A fund the member has seen before and the provider no longer lists is reported as
`MISSING_AT_PROVIDER`. Its account is never deleted or closed by this rule. Sofidy's existing
rule (point 8 of its ADR) still applies on top: a complete Sofidy snapshot is authoritative,
so a linked fund it no longer lists is written at zero shares. CORUM never zeroes.

A new table `scpi_provider_fund` (V114) holds one row per (member, provider, fund code):

- `ignored` is the tombstone. It is set when the member deletes the account of a linked fund,
  clears the fund code on the form, or clicks "Ignore this fund". Typing or picking the code
  again lifts it, and so does the restore endpoint.
- `last_outcome` and `last_synced_at` are what the latest sync did with the fund. Every fund
  of one run shares the timestamp, so the UI reports the latest run as a whole.

The account link itself stays on `scpi_position.corum_fund_code` / `sofidy_fund_code`. Their
per-member unique indexes, plus the member row lock each sync takes before it writes, are
what stop a re-sync or a second connection from creating a second account.

The sync panels show a per-fund summary instead of "SCPI shares synced". A run that found
funds and applied none is a warning, never a plain success. The free-text fund-code fields
move under an "Advanced" section, and adding a SCPI by hand offers the unlinked funds a sync
has already seen.

## Alternatives considered

### Keep manual linking, and only report unlinked funds

- **Pros**: no account is ever created without a click.
- **Cons**: the user still has to find the provider's internal code, which is the problem.
  Every other connector creates its accounts.

### Fuzzy name matching for adoption

- **Pros**: links "CORUM Origin" to an account typed "Corum origine".
- **Cons**: a fuzzy match is exactly how a phantom link happens. Normalization stops at case,
  accents and punctuation. Everything looser is reported as a created account the user can
  merge or ignore.

### Store the tombstone on `scpi_position`

- **Pros**: no new table.
- **Cons**: a deleted account keeps its position row only by accident of soft-delete, and the
  per-fund sync outcome would have no place to live for a fund with no account.

### A per-provider "create accounts automatically" setting (issue item 9)

Not built. The tombstone and the "Ignore this fund" action already cover the user who does not
want a fund mirrored, at fund granularity. The setting can be added later without a schema
change to this design.

## Reasoning

The two ADRs refused creation because of phantom and duplicate accounts. Duplicates are
prevented by the existing unique indexes and the member lock, phantoms by unique-match-only
adoption and by the tombstone. With those in place, the manual step protects nothing and costs
every new user a broken first sync.

## Trade-offs accepted

- A sync can now create accounts the user did not ask for one by one. The summary names
  each, and "Ignore this fund" undoes it without losing the account.
- An account whose name differs from the portal label is not adopted. The sync creates a
  second account, which the user then reconciles by hand once.
- `MISSING_AT_PROVIDER` only reports. Closing a sold CORUM fund stays manual.

## Consequences

- New: `ScpiFundReconciler`, `ScpiFundService`, `ScpiFundController`
  (`GET /api/scpi/funds`, `POST /api/scpi/funds/{provider}/{code}/ignore|restore`),
  `ScpiProviderFund`, migration `V114__scpi_provider_fund.sql`.
- `CorumSyncService` and `SofidySyncService` delegate per-fund writes to the reconciler.
- `AccountService.delete` and `ScpiPositionService.save` set or lift the tombstone.
- Frontend: `ScpiSyncSummary` in both sync panels, "Ignore this fund" on the SCPI detail,
  fund picker in `AddScpiModal`.

## Supersedes

Point 5 of [2026-09-26-corum-scpi-sidecar.md](2026-09-26-corum-scpi-sidecar.md) and point 5 of
[2026-09-27-sofidy-scpi-sidecar.md](2026-09-27-sofidy-scpi-sidecar.md).
