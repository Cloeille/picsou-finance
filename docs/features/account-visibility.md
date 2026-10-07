# Feature: Account Visibility (hidden accounts)

> Last updated: 2026-07-15

## Context

Chloé wanted a way to hide an account from `/accounts`, the dashboard, and budget/savings totals
without stopping its sync — soft-delete (`deleted_at`) does both today, which isn't what she wants
for e.g. an old Revolut pocket she no longer looks at but still wants tracked in the background.
This feature adds a `hidden` flag that is purely a *display* preference: the account keeps syncing,
exporting, and being scheduled exactly like a visible one.

## How it works

### Data model

`account.hidden boolean NOT NULL DEFAULT false` (`V55__account_hidden.sql`). Deliberately **not**
folded into the entity's `@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")`
(`Account.java`). That restriction is global — it also gates every sync-matching query
(`findByExternalAccountIdAndMemberId`, `findByIbanAndMemberId`, etc., see
[bank-sync.md](./bank-sync.md)). Baking `hidden` into it would silently stop a hidden account from
being matched/updated by sync, reproducing the soft-delete behavior this feature exists to avoid.

### Backend filtering — explicit per call site, not global

`AccountRepository` exposes two finders side by side:

- `findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc` — excludes hidden accounts.
- `findAllByMemberIdOrderByCreatedAtAsc` — everything (still excludes soft-deleted, via
  `@SQLRestriction`).

**Excluded from user-facing totals** (call the `HiddenFalse` finder directly, or go through
`AccountService.findAll(memberId)` which defaults `includeHidden` to `false`):

- `AccountService.findAll(memberId)` → `GET /api/accounts`, feeds `/accounts`.
- `DashboardService` (net worth / dashboard cards).
- `budget.AllocationService` (budget allocation).
- `SavingsService` (savings/livrets totals).
- `FamilyViewService`'s `SharingLevel.ALL` branch (shared family dashboard another member sees).
  Added in a final-review follow-up (commit `ae09139`) — this call site wasn't in the original
  scope and a hidden account briefly leaked into the shared dashboard until it was found. The
  `SharingLevel.MANUAL` branch is untouched: it uses an explicit per-account share list
  (`findByIdInAndMemberId`), a different, intentional opt-in mechanism unaffected by `hidden`.

**Intentionally NOT excluded** — these must keep operating on hidden accounts, since hiding is a
display preference, not a deletion (rationale detailed in the Task 3 brief that shipped this
filtering):

- `SchedulerService` (daily balance snapshots).
- `TradeRepublicSyncService`, `finary/FinaryApiSyncService`, `FinaryImportService` (sync/import).
- Every `export/*Exporter` (GDPR data export) — a hidden account must still appear in the user's
  export.
- All sync-matching lookups (`findByExternalAccountIdAndMemberId`, `findByIbanAndMemberId`, and
  friends) — untouched, same reasoning as the `@SQLRestriction` decision above.

### New endpoints

- `GET /api/accounts?includeHidden=true` — returns every non-deleted account, hidden or not.
  Used only by the `/sync` visibility tab; every other caller of `GET /api/accounts` omits the
  param and gets the default (hidden excluded).
- `PUT /api/accounts/{id}/visibility` — body `AccountVisibilityRequest { hidden: boolean }`.
  `AccountService.setHidden()` loads the account member-scoped (`getOrThrow`), flips the flag,
  saves, and returns the updated `AccountResponse`. Same small-dedicated-endpoint pattern as
  `/real-estate` and `/debt` on `AccountController`.

### Frontend — `/sync` "Comptes" tab

`AccountsVisibilityTab.tsx`, wired into `SyncPage.tsx` as a new `TabsTrigger`/`TabsContent` pair
(`value="visibility"`, label `sync.visibility.title`). It:

1. Fetches every account via `useAllAccounts()` (`GET /accounts?includeHidden=true`).
2. Groups them with `useAccountTree()` (`features/accounts/hooks.ts`) into wallet/pocket pairs
   plus standalone accounts, then re-groups by `account.provider` (falling back to
   `sync.visibility.manualGroup` for manual accounts) so the tab reads
   **provider → wallet → pockets**, one `Card` per provider.
3. Renders one `VisibilityRow` per account: name, muted balance (`CurrencyDisplay`), and a shadcn
   `Switch` (`checked={!account.hidden}`, indented for pockets). Toggling calls
   `useToggleAccountVisibility()`.

`useAccountTree` was extracted out of `AccountsPage.tsx` into the shared
`features/accounts/hooks.ts` specifically so `/accounts` and the visibility tab can't drift apart
on wallet/pocket grouping logic — it's a pure `useMemo` over an `Account[]`, no query involved. A
pocket whose parent wallet isn't in the given list (e.g. soft-deleted) falls back into
`standaloneAccounts`/`nonPocketAccounts` instead of silently vanishing.

`useToggleAccountVisibility()` invalidates `['accounts']`, `['dashboard']`, and `['history']` on
success, since a visibility change can affect all three.

### Key files

- `backend/src/main/resources/db/migration/V55__account_hidden.sql` — the `hidden` column
- `backend/src/main/java/com/picsou/model/Account.java` — `hidden` field, outside `@SQLRestriction`
- `backend/src/main/java/com/picsou/repository/AccountRepository.java` — `HiddenFalse` vs.
  unfiltered finders
- `backend/src/main/java/com/picsou/service/AccountService.java` — `findAll(memberId, includeHidden)`, `setHidden()`
- `backend/src/main/java/com/picsou/controller/AccountController.java` — `GET ?includeHidden`, `PUT /{id}/visibility`
- `backend/src/main/java/com/picsou/dto/AccountVisibilityRequest.java` — `{ hidden: boolean }`
- `backend/src/main/java/com/picsou/service/DashboardService.java`,
  `backend/src/main/java/com/picsou/service/budget/AllocationService.java`,
  `backend/src/main/java/com/picsou/service/SavingsService.java`,
  `backend/src/main/java/com/picsou/service/FamilyViewService.java` — hidden-excluding call sites
- `frontend/src/features/accounts/hooks.ts` — `useAccountTree`, `useAllAccounts`, `useToggleAccountVisibility`
- `frontend/src/pages/sync/AccountsVisibilityTab.tsx` — the "Comptes" tab UI
- `frontend/src/pages/sync/SyncPage.tsx` — tab wiring

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Separate `hidden` column, outside `@SQLRestriction` | Sync-matching queries must keep seeing hidden accounts; folding `hidden` into the global restriction would silently break their sync | Reuse `deleted_at` / fold `hidden` into `@SQLRestriction` |
| Explicit exclusion per call site (Dashboard, Allocation, Savings) rather than a repository-level default | Sync/export/scheduler *must not* exclude hidden accounts — a single global filter can't serve both needs | One global "hidden-aware" repository default |
| `includeHidden` query param on the existing `GET /api/accounts` | Avoids a parallel `/accounts/all` endpoint; every other caller already omits the param and gets today's behavior unchanged | Separate `GET /api/accounts/all` endpoint |
| `useAccountTree` extracted to a shared hook | `/accounts` and the visibility tab render the same wallet/pocket tree; duplicating the grouping logic risked the two views drifting apart | Reimplement grouping locally in `AccountsVisibilityTab` |

## Gotchas / Pitfalls

- **Do not add `hidden` to `Account`'s `@SQLRestriction`.** It would silently stop hidden accounts
  from being matched during sync (`findByExternalAccountIdAndMemberId`, `findByIbanAndMemberId`,
  Revolut pocket lookups, etc.) — the exact soft-delete behavior this feature was built to avoid.
- **New user-facing account aggregations must explicitly filter `hidden`.** There is no repository-
  or entity-level default doing this for you; any new "list accounts" or "sum balances" call site
  has to call the `HiddenFalse` finder (or go through `AccountService.findAll(memberId)`) the same
  way `DashboardService`/`AllocationService`/`SavingsService`/`FamilyViewService` do, or it will
  silently start including hidden accounts — exactly the gap the final whole-branch review caught
  in `FamilyViewService` before it shipped (see lesson
  [final-whole-branch-review-catches-what-task-scoped-review-cannot.md](../lessons/final-whole-branch-review-catches-what-task-scoped-review-cannot.md)).
- **Sync/export/scheduler paths must NOT filter `hidden`.** `SchedulerService`,
  `TradeRepublicSyncService`, `FinaryApiSyncService`, `FinaryImportService`, and every `*Exporter`
  intentionally call the unfiltered finder — a hidden account is still a fully live account from
  their point of view.
- **All pre-existing accounts default to `hidden = false`** (migration `DEFAULT false`) — shipping
  this feature causes no visible behavior change until a user actually flips a toggle.
- **No controller-level test exists yet** for `PUT /accounts/{id}/visibility` — coverage is at the
  service layer (`AccountServiceTest`) only.

## Tests

- `backend/src/test/java/com/picsou/service/AccountServiceTest.java`:
  - `findAll_excludesHiddenAccounts_byDefault()`
  - `findAll_includesHiddenAccounts_whenRequested()`
  - `setHidden_persistsFlag_forOwnedAccount()`
  - `setHidden_throws_whenAccountNotOwnedByMember()`

No frontend test exists yet for `AccountsVisibilityTab.tsx` or the visibility-related hooks
(`useAllAccounts`, `useToggleAccountVisibility`, `useAccountTree`).

## Links

- Related: [bank-sync.md](./bank-sync.md) — IBAN/external-id sync matching that hidden accounts
  must keep participating in
- Design doc: `docs/briefs/2026-07-14-account-visibility-and-pocket-cleanup-design.md`
