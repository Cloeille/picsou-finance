# Feature: Ownership shares

> Last updated: 2026-08-10

## Context

A couple who both use Picsou own their house together, and their mortgage together. Before
this, an account belonged to one `FamilyMember` at 100%, so a jointly-owned property either
sat entirely on one partner's net worth or got entered twice and counted twice at family level.

Ownership shares let a property (or the loan financing it) be split between members, with each
member's totals counting only their part.

## How it works

`account_ownership` maps `(account_id, member_id) → share_percent`. Four rules carry the design:

1. **No rows ⇒ the owning member holds 100%.** Absence is the default, so introducing this
   required no backfill.
2. **The sum may be < 100.** The remainder is held outside Picsou (indivision with a
   non-member, an SCI) and counts towards nobody's net worth, while still being part of the
   property's gross value. Over 100 is rejected.
3. **Shares weight reads, never writes.** `balance_snapshot` always stores 100% of the value.
4. **Reading is broader than writing.** A co-owner reads the account and their share; only
   `account.member` may edit, revalue or delete it.

Everything resolves through `AccountAccessResolver` — the single exception to the project's
"never query a repository without a member filter" rule, and therefore the single place to
audit.

### Flow

```
GET /dashboard
  └─> AccountAccessResolver.readableAccounts(memberId)   owned ∪ co-owned
       └─> sharesFor(accounts, memberId)                  one query for the whole set
            └─> each accountValue x share/100 before it enters any total

PUT /accounts/{id}/ownership
  └─> requireOwner            a co-owner cannot reallocate shares
       ├─ type is REAL_ESTATE, SCPI or LOAN?   else 422
       ├─ sum <= 100?                    else 422
       ├─ owner present in the split?    else 422
       └─ delete-then-insert (JPQL delete: a derived one trips the unique key)
```

### Key files

- `service/AccountAccessResolver.java` — visibility, shares, read/write guards, `weigh()`
- `service/AccountOwnershipService.java` — validation and replace-the-whole-split writes
- `model/AccountOwnership.java`, `repository/AccountOwnershipRepository.java`
- `dto/OwnershipRequest.java`, `dto/OwnershipResponse.java`
- Weighted consumers: `service/DashboardService.java`, `service/HistoryService.java`,
  `service/FamilyViewService.java`, `service/GoalService.java`
- `components/property/OwnershipEditor.tsx`

## Technical choices

| Choice | Why | Rejected alternative |
|---|---|---|
| Weight on read | A share is a statement about now; weighting at write time would rewrite history whenever a split changes | Store the member's part in `balance_snapshot` |
| One resolver class | Co-ownership is the only place one member reads another's row — worth a single audited choke point | Ad-hoc ownership queries per service |
| Sum ≤ 100, not = 100 | Models indivision with someone who does not use Picsou | Force 100 and give the rest to the owner |
| Restricted to `REAL_ESTATE`, `SCPI` and `LOAN` | A joint current account raises transaction and sync questions this does not answer | Apply to every account type |
| `sharePercent` null at 100% | Lets the UI treat "co-owned" as a distinct state without every ordinary account carrying a meaningless 100 | Always send the number |
| Full value in `AccountResponse` | A half-owned house is still a €400,000 house, and the edit form must load the real figure | Pre-weight the balance |

## Gotchas / Pitfalls

- **`FamilyViewService` weights by the *owner's* share, not the viewer's.** That list is "what
  other members hold". On a 50/50 house the viewer's own half already appears on their personal
  dashboard, so counting the full value there would report the property twice.
- **A property and its mortgage have independent splits.** `RealEstateSummaryService` applies
  each account's own share. Assuming they match would quietly produce the wrong equity.
- **A zero share means "worth nothing to you", not "not yours".** For a non-owner the two
  coincide, and `HistoryService.assertReadable` treats a zero share as inaccessible. For the
  *owner* they do not: they may transfer their whole share away and still administer the
  account, which is why `AccountAccessResolver.requireReadable` lets an owner through
  regardless of share. `assertReadable` did not, and it rejects the **whole batch** — since
  `DashboardService` passes every readable id to `buildHistory` at once, one such account 404'd
  the entire dashboard history instead of showing itself as worth nothing. Both guards now
  answer the same question. Note `shareFrom` still reports 0 rather than inventing an implicit
  100%: the split is the truth about value, ownership is the truth about access.
- **`deleteAllForAccount` is JPQL on purpose.** A derived `deleteByAccountId` lets Hibernate
  flush the inserts first, tripping `uk_account_ownership_account_member` on a rewrite.
- **…and it detaches whatever the caller is holding.** It is declared
  `clearAutomatically = true`, so the persistence context is empty once it returns: the
  `Account` from `requireOwner` is detached and its lazy `member` is a proxy that can never be
  initialised again. Anything the response needs off that graph must be read *before* the
  delete — `replace` calls `Hibernate.initialize(owner)` for exactly this. Clearing a split
  shipped broken because of it: the response read the owner's name off the dead proxy,
  `LazyInitializationException` rolled the transaction back, and the delete went with it, so
  the split could never be cleared at all. `isOwner`/`validate` never noticed because reading
  a proxy's *identifier* does not initialise it.
- **Writing through the detached `Account` is fine, but only just.** The new rows reference it
  and Hibernate resolves the FK from its id without needing it managed. That is behaviour, not
  a guarantee — `AccountOwnershipReplaceIntegrationTest` pins it.
- **Removing a member frees their share rather than redistributing it.** Automatic
  redistribution would be a guess about a real-world event Picsou knows nothing about.
- **`/family/members` is admin-only.** The ownership editor fetches the roster only for
  admins — a non-admin owner edits the members already in the split.
- **`shareFor` is for a single account, `sharesFor` for a set — never `shareFor` in a loop.**
  `shareFor` issues one `findByAccountId` per call, so a loop turns into one round trip per
  account; `sharesFor` answers the whole set with one `IN` clause. It matters most where the
  loops nest: `FamilyViewService` iterates accounts *inside* a loop over every family member,
  and `GoalService.toProgressResponse` / `RealEstateSummaryService.loansFor` run once per goal
  and per property respectively. All four were migrated on 2026-08-10, and each carries a test
  that verifies `sharesFor` is called once and `shareFor` not at all — nothing else fails if
  the per-account form comes back, since the numbers are identical either way.
- **Write guards are `requireOwner`, read guards are `requireReadable`.** Mixing them up is
  how a co-owner ends up able to rewrite someone else's net worth.

## Tests

- `AccountAccessResolverTest` — the security-critical one: co-owned reads allowed, co-owned
  writes refused, non-holders 404'd, weighting arithmetic
- `AccountOwnershipServiceTest` — over-100 rejected, owner-must-remain, type restriction,
  clearing restores the implicit default. Mockito throughout, so its `Account` carries a real
  `FamilyMember` and it cannot see anything the persistence context does
- `AccountOwnershipReplaceIntegrationTest` — the same `replace` against Flyway-migrated
  PostgreSQL, where `member` really is a lazy proxy: clearing a split, writing one, rewriting
  one over itself, and clearing one that exists. Skips itself without Docker. Note the ordering
  trap it was written around — a `replace` that ran earlier in the same context leaves the
  owner loaded, so the association resolves to that managed instance and the proxy never
  appears; only a cold context (every HTTP request) reproduces the failure
- `RealEstateSummaryServiceTest` — divergent property/loan shares, and loan shares resolved in
  one query per property
- `FamilyViewServiceTest` / `GoalServiceTest` — shares resolved in a single batch call
- `RealEstateValuationMigrationTest` — constraint bounds and cascade behaviour

## Links

- ADR: [Per-member ownership shares](../decisions/2026-08-01-account-ownership-shares.md)
- Related feature: [Real estate valuation](real-estate-valuation.md)
- Related feature: [Multi-account family](multi-account-family.md)
