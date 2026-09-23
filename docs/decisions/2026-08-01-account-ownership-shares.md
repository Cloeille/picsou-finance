# ADR: Per-member ownership shares on properties and loans

> Date: 2026-08-01
> Status: ✅ Active

## Context

A couple who both use Picsou own their house together. Before this change, an account belonged
to exactly one `FamilyMember` at 100%, and family sharing (`SharingSettings` + `SharedResource`)
was read-only and binary: another member either saw the whole account or none of it.

That left no honest way to record a jointly-owned property. Either one partner carried the
full €400,000 and the other nothing, or both created their own entry and the household total
counted the house twice.

`TODO.txt` had asked for exactly this — family sharing with "immo [% détenu]" and the ability
to attach debt to an object.

## Decision

A new `account_ownership` table maps `(account_id, member_id) → share_percent`, and all
member-facing aggregation weights by that share.

Four rules do the real work:

1. **No rows means the owning member holds 100%.** Absence is the default, which is why
   introducing this needed **no backfill** — every pre-existing account stays correct without
   writing a single row.
2. **The per-account sum must be ≤ 100, not = 100.** The remainder is held by parties outside
   Picsou (an indivision with a non-member, an SCI) and belongs to **nobody's** net worth,
   while still counting towards the property's gross value. It is reported to the user rather
   than silently folded into the owner's share, which would invent money.
3. **Shares weight reads, never writes.** `balance_snapshot` always stores 100% of an
   account's value; the share is applied when a total is read.
4. **Reading is broader than writing.** A co-owner reads a co-owned account and counts their
   part; only the *administrative owner* (`account.member`) may edit, revalue or delete it.

All of this is resolved in one place, `AccountAccessResolver`. `backend/CLAUDE.md` makes
member scoping non-negotiable — "never query a repository without a member filter" — and
co-ownership is a deliberate exception to that rule, so it is confined to a single audited
class rather than spread across services as ad-hoc queries.

**Scope is intentionally limited to `REAL_ESTATE`, `SCPI` and `LOAN`.** The table is keyed on
`account_id` generally, but the write path refuses other types.

## Alternatives considered

### A scalar `ownership_share` column on `real_estate_metadata`

- **Pros**: One column, no join, no new authorization path. Each partner records their own
  property entry at their own percentage.
- **Cons**: Does not model the household. The family dashboard cannot tell that two entries
  are the *same* house, so it cannot show it once at full value. Duplicate data entry, and any
  correction has to be made twice. It also says nothing about who may edit what.

### Weight the shares at write time (store the member's part in `balance_snapshot`)

- **Pros**: Reads stay trivial — no weighting anywhere, totals are plain sums.
- **Cons**: Changing a split would have to **rewrite the entire history**, or leave the past
  expressed in one ratio and the present in another. A percentage is a statement about now,
  not about what the account was worth in 2023.

### Extend the existing `SharedResource` model with a percentage

- **Pros**: Reuses the sharing tables already in place.
- **Cons**: Conflates two different things. Sharing answers "who may look at this"; ownership
  answers "whose wealth is this". A property can be co-owned without being shared for viewing,
  and shared for viewing without being co-owned.

### Apply shares to every account type

- **Pros**: More general, one consistent rule.
- **Cons**: A joint current account immediately raises questions this feature does not answer:
  whose transactions are they, who syncs it, what is half a transaction. Halving a checking
  balance silently would be worse than refusing.

## Reasoning

The distinction that drove the design is that **a share is a view, not a fact about the past**.
Once that is accepted, weighting has to happen on read, snapshots stay at 100%, and changing a
split becomes a cheap, reversible operation rather than a history rewrite.

The second driver was blast radius. Co-ownership is the only place in the codebase where one
member legitimately reads another member's row, so it is worth paying for a single choke point
with its own test suite instead of sprinkling exceptions through the services.

## Trade-offs accepted

- **Aggregation got more expensive.** `DashboardService`, `HistoryService`, `FamilyViewService`
  and `GoalService` all resolve shares now. Mitigated by a batch `sharesFor(...)` that resolves
  a whole set in one query.
- **A co-owner cannot fix a typo.** If the owner mistypes the surface area, the co-owner must
  ask. Deliberate: the alternative lets one person silently rewrite another's net worth.
- **A non-admin owner cannot add a member to a split.** The member roster lives behind an
  admin-only endpoint, so a non-admin edits the members already in the split. Adding someone
  new needs an admin.
- **`AccountResponse.currentBalanceEur` reports the account's full value**, with `sharePercent`
  alongside, rather than a pre-weighted figure. A half-owned house is still a €400,000 house
  and the edit form must load the real number; weighting belongs to the totals, not the record.
- **Removing a member drops their share** via `ON DELETE CASCADE`, and the freed percentage
  becomes unassigned rather than being redistributed. Automatic redistribution would be a
  guess about a real-world event Picsou knows nothing about.

## Consequences

- New table `account_ownership` (migration `V66`), new entity, repository and
  `AccountOwnershipService`.
- New `AccountAccessResolver` — the single authorization and weighting point. `HistoryService`'s
  `assertOwnership` becomes `assertReadable` and returns the share map it validated.
- `AccountResponse` gains `sharePercent` and `isOwner`; the UI hides write actions on
  co-owned accounts rather than letting users discover the rule through a 403.
- `GET`/`PUT /api/accounts/{id}/ownership`.
- Extending shares to other account types is a follow-up, not an oversight.
