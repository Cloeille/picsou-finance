# Per-Account Visibility Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-account `hidden` flag, toggleable from a new "Comptes" tab on `/sync`, that
excludes an account from every user-facing display/total (like `deleted_at` does today) **without**
affecting sync — a hidden account keeps being matched, updated, and synced normally.

**Architecture:** New `account.hidden` column, filtered explicitly (never via the entity's global
`@SQLRestriction`, which also gates sync-matching queries) in exactly the handful of read paths
that feed user-facing totals. New `PUT /accounts/{id}/visibility` endpoint. New `/sync` tab
rendering an indented provider→wallet→pocket tree with a toggle per row, sharing its grouping logic
with `/accounts` via an extracted hook.

**Tech Stack:** Java 21 / Spring Boot / Flyway (backend), React 19 / TypeScript / TanStack Query /
shadcn `Switch` (frontend).

**Depends on:** `docs/briefs/2026-07-14-remove-psd2-pocket-guess-plan.md` should land first — it
removes the "allocated" pocket label this plan's shared grouping hook must not resurrect. Not a
hard blocker (no file conflicts), but land that plan first to avoid rebasing this one.

## Global Constraints

- Backend: `mvn test` must stay green after every task.
- Frontend: `bun run typecheck && bun run lint && bun run build` must stay green after every task.
- `hidden` is NEVER added to `Account`'s `@SQLRestriction("deleted_at IS NULL")`. It is filtered
  explicitly, per call site, only where noted in Task 4. All sync/matching/export/scheduler call
  sites of `findAllByMemberIdOrderByCreatedAtAsc` are left untouched — hidden accounts must keep
  syncing exactly like visible ones.
- Migration file numbering: use the next free `V*` number in
  `backend/src/main/resources/db/migration/` on this branch at the time of implementation (was
  `V55` when this plan was written — check `ls backend/src/main/resources/db/migration | sort -V | tail -1` first, branch migration numbers can diverge from `main`/prod, reconciled at merge time
  per existing project convention).

---

### Task 1: Data model — `account.hidden` column

**Files:**
- Create: `backend/src/main/resources/db/migration/V55__account_hidden.sql` (renumber if a newer
  `V*` already exists on this branch — see Global Constraints)
- Modify: `backend/src/main/java/com/picsou/model/Account.java`
- Modify: `backend/src/main/java/com/picsou/repository/AccountRepository.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Account.isHidden()` / `Account.setHidden(boolean)`, defaulting to `false`.
  `AccountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(Long memberId)`. Later
  tasks build on both.

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE account ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;
```

- [ ] **Step 2: Add the `hidden` field to `Account.java`**

In `backend/src/main/java/com/picsou/model/Account.java`, add after the `parentAccountId` field
(before the closing `}` of the class):

```java

    @Column(nullable = false)
    @Builder.Default
    private boolean hidden = false;
```

- [ ] **Step 3: Add the derived query to `AccountRepository.java`**

Add next to `findAllByMemberIdOrderByCreatedAtAsc`:

```java
    List<Account> findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(Long memberId);
```

- [ ] **Step 4: Compile**

Run: `cd backend && mvn compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V55__account_hidden.sql \
        backend/src/main/java/com/picsou/model/Account.java \
        backend/src/main/java/com/picsou/repository/AccountRepository.java
git commit -m "$(cat <<'EOF'
feat(accounts): add hidden column for display-only account visibility

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `AccountResponse.hidden` + `AccountService.findAll` filtering + visibility endpoint

**Files:**
- Modify: `backend/src/main/java/com/picsou/dto/AccountResponse.java`
- Modify: `backend/src/main/java/com/picsou/service/AccountService.java`
- Modify: `backend/src/main/java/com/picsou/controller/AccountController.java`
- Create: `backend/src/main/java/com/picsou/dto/AccountVisibilityRequest.java`
- Modify: `backend/src/test/java/com/picsou/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `Account.isHidden()`, `AccountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc`
  from Task 1.
- Produces: `AccountService.findAll(Long memberId)` (excludes hidden — unchanged signature, new
  behavior), `AccountService.findAll(Long memberId, boolean includeHidden)` (new overload),
  `AccountService.setHidden(Long id, Long memberId, boolean hidden)` (new). `GET
  /api/accounts?includeHidden=true`. `PUT /api/accounts/{id}/visibility`. `AccountResponse.hidden`
  field, used by Task 5/6 frontend types.

- [ ] **Step 1: Add `hidden` to `AccountResponse`**

Replace the full file `backend/src/main/java/com/picsou/dto/AccountResponse.java` with:

```java
package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
    Long id,
    String name,
    AccountType type,
    String provider,
    String currency,
    BigDecimal currentBalance,
    BigDecimal currentBalanceEur,
    Instant lastSyncedAt,
    boolean isManual,
    String color,
    String ticker,
    String logoUrl,
    Instant createdAt,
    RealEstateMetadataResponse realEstate,
    DebtResponse debt,
    SavingsConfigDto savingsConfig,
    /** Non-null only for Revolut pocket sub-accounts; the parent wallet's account id. */
    Long parentAccountId,
    /** Display-only visibility flag; a hidden account still syncs normally. */
    boolean hidden
) {
    public static AccountResponse from(Account a, BigDecimal balanceEur) {
        return new AccountResponse(
            a.getId(),
            a.getName(),
            a.getType(),
            a.getProvider(),
            a.getCurrency(),
            a.getCurrentBalance(),
            balanceEur,
            a.getLastSyncedAt(),
            a.isManual(),
            a.getColor(),
            a.getTicker(),
            a.getLogoUrl(),
            a.getCreatedAt(),
            null,
            null,
            null,
            a.getParentAccountId(),
            a.isHidden()
        );
    }

    public AccountResponse withRealEstate(RealEstateMetadataResponse realEstate) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, lastSyncedAt, isManual, color, ticker, logoUrl, createdAt, realEstate, debt,
            savingsConfig, parentAccountId, hidden);
    }

    public AccountResponse withDebt(DebtResponse debt) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, lastSyncedAt, isManual, color, ticker, logoUrl, createdAt, realEstate, debt,
            savingsConfig, parentAccountId, hidden);
    }

    public AccountResponse withSavingsConfig(SavingsConfigDto savingsConfig) {
        return new AccountResponse(id, name, type, provider, currency, currentBalance,
            currentBalanceEur, lastSyncedAt, isManual, color, ticker, logoUrl, createdAt, realEstate, debt,
            savingsConfig, parentAccountId, hidden);
    }
}
```

- [ ] **Step 2: Create `AccountVisibilityRequest`**

```java
package com.picsou.dto;

public record AccountVisibilityRequest(boolean hidden) {}
```

- [ ] **Step 3: Write the failing tests in `AccountServiceTest.java`**

Add, near the other `findAll`/simple-behavior tests (after the `ownedAccount()` helper, alongside
the existing `@Test` methods):

```java
    @Test
    void findAll_excludesHiddenAccounts_byDefault() {
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(1L))
            .thenReturn(List.of(ownedAccount()));

        List<com.picsou.dto.AccountResponse> result = accountService.findAll(1L);

        assertThat(result).hasSize(1);
        verify(accountRepository).findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(1L);
        verify(accountRepository, never()).findAllByMemberIdOrderByCreatedAtAsc(any());
    }

    @Test
    void findAll_includesHiddenAccounts_whenRequested() {
        Account hidden = Account.builder()
            .id(2L).name("Hidden One").type(AccountType.CHECKING).currency("EUR").hidden(true)
            .build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L))
            .thenReturn(List.of(ownedAccount(), hidden));

        List<com.picsou.dto.AccountResponse> result = accountService.findAll(1L, true);

        assertThat(result).hasSize(2);
        assertThat(result).anyMatch(com.picsou.dto.AccountResponse::hidden);
        verify(accountRepository, never()).findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(any());
    }

    @Test
    void setHidden_persistsFlag_forOwnedAccount() {
        Account account = ownedAccount();
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        com.picsou.dto.AccountResponse result = accountService.setHidden(1L, 1L, true);

        assertThat(result.hidden()).isTrue();
        assertThat(account.isHidden()).isTrue();
    }

    @Test
    void setHidden_throws_whenAccountNotOwnedByMember() {
        when(accountRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.setHidden(1L, 1L, true))
            .isInstanceOf(ResourceNotFoundException.class);
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd backend && mvn test -Dtest=AccountServiceTest`
Expected: `FAIL` — `findAll(Long, boolean)` and `setHidden` don't exist yet (compile error).

- [ ] **Step 5: Implement `findAll` overload and `setHidden` in `AccountService.java`**

Replace the existing `findAll` method (currently):
```java
    public List<AccountResponse> findAll(Long memberId) {
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(this::toResponse)
            .toList();
    }
```
with:
```java
    public List<AccountResponse> findAll(Long memberId) {
        return findAll(memberId, false);
    }

    /**
     * @param includeHidden false (default) excludes hidden accounts, matching every other
     *                       user-facing account list. true is used only by the /sync visibility
     *                       tab, which must be able to see (and re-show) hidden accounts.
     */
    public List<AccountResponse> findAll(Long memberId, boolean includeHidden) {
        List<Account> accounts = includeHidden
            ? accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)
            : accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId);
        return accounts.stream().map(this::toResponse).toList();
    }

    @Transactional
    public AccountResponse setHidden(Long id, Long memberId, boolean hidden) {
        Account account = getOrThrow(id, memberId);
        account.setHidden(hidden);
        return toResponse(accountRepository.save(account));
    }
```

- [ ] **Step 6: Wire the controller endpoints in `AccountController.java`**

Replace:
```java
    @GetMapping
    public List<AccountResponse> findAll() {
        return accountService.findAll(userContext.currentMemberId());
    }
```
with:
```java
    @GetMapping
    public List<AccountResponse> findAll(@RequestParam(defaultValue = "false") boolean includeHidden) {
        return accountService.findAll(userContext.currentMemberId(), includeHidden);
    }
```

Add a new endpoint next to `update`:
```java
    @PutMapping("/{id}/visibility")
    public AccountResponse updateVisibility(@PathVariable Long id, @Valid @RequestBody AccountVisibilityRequest req) {
        return accountService.setHidden(id, userContext.currentMemberId(), req.hidden());
    }
```

Add the import:
```java
import com.picsou.dto.AccountVisibilityRequest;
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && mvn test -Dtest=AccountServiceTest`
Expected: `PASS`, all tests green.

- [ ] **Step 8: Run the full backend suite**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS` (the `AccountResponse` constructor signature changed — check for any other
direct `new AccountResponse(...)` call outside `AccountResponse.java` itself with
`grep -rn "new AccountResponse(" backend/src/main/java backend/src/test/java`; if any exist, add
`, false` — not hidden by default — as the trailing constructor argument).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/picsou/dto/AccountResponse.java \
        backend/src/main/java/com/picsou/dto/AccountVisibilityRequest.java \
        backend/src/main/java/com/picsou/service/AccountService.java \
        backend/src/main/java/com/picsou/controller/AccountController.java \
        backend/src/test/java/com/picsou/service/AccountServiceTest.java
git commit -m "$(cat <<'EOF'
feat(accounts): PUT /accounts/{id}/visibility + includeHidden filter

GET /api/accounts excludes hidden accounts by default; the new
?includeHidden=true param (used only by the /sync visibility tab)
returns everything. Toggling goes through the new dedicated
visibility endpoint, same pattern as /real-estate and /debt.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Exclude hidden accounts from net worth, budget allocation, and savings suggestions

**Files:**
- Modify: `backend/src/main/java/com/picsou/service/DashboardService.java`
- Modify: `backend/src/main/java/com/picsou/service/budget/AllocationService.java`
- Modify: `backend/src/main/java/com/picsou/service/SavingsService.java`
- Modify: `backend/src/test/java/com/picsou/service/DashboardServiceTest.java`
- Modify: `backend/src/test/java/com/picsou/service/DashboardServiceLiabilityTest.java`
- Modify: `backend/src/test/java/com/picsou/service/budget/AllocationServiceTest.java`

**Interfaces:**
- Consumes: `AccountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc` from Task 1.
- Produces: net worth, budget allocation, and savings suggestions that all exclude hidden accounts.
  Does NOT change `HistoryService` (see rationale below) or any export/scheduler/sync path.

**Why only these three, not every `findAllByMemberIdOrderByCreatedAtAsc` call site:**
`SchedulerService`, `TradeRepublicSyncService`, `FinaryImportService`, `FinaryApiSyncService`
(sync/matching/scheduled-snapshot paths) and every `*Exporter` (GDPR data export) must keep
including hidden accounts — hidden is a display preference, not a deletion, and the whole point of
this feature is that sync keeps working. `HistoryService` needs no change either: it already
operates purely on caller-supplied account ids with no independent listing (see the existing
comment at `HistoryService.java:127`, "sums whatever account ids it's given with no
parentAccountId filtering of its own") — the same precedent this plan follows for `hidden`. Once
`GET /api/accounts` (Task 2) excludes hidden accounts by default, the frontend's `/accounts` page
naturally never sends a hidden account's id into `/history`, so history/PnL charts are covered for
free.

- [ ] **Step 1: Switch `DashboardService.getDashboard` to the hidden-excluding query**

In `backend/src/main/java/com/picsou/service/DashboardService.java`, replace:
```java
        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId);
```
with:
```java
        List<Account> accounts = accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId);
```

- [ ] **Step 2: Switch `AllocationService.buildStock` to the hidden-excluding query**

In `backend/src/main/java/com/picsou/service/budget/AllocationService.java`, replace:
```java
        for (Account account : accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId)) {
```
with:
```java
        for (Account account : accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId)) {
```

- [ ] **Step 3: Switch `SavingsService.getSuggestions` to the hidden-excluding query**

In `backend/src/main/java/com/picsou/service/SavingsService.java`, replace:
```java
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
```
with:
```java
        return accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId).stream()
```

- [ ] **Step 4: Update the three services' existing tests to mock the new repository method**

Every existing test in `DashboardServiceTest.java`, `DashboardServiceLiabilityTest.java`, and
`AllocationServiceTest.java` that currently stubs
`when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(...))...` for these three services —
rename every such stub (in all three files) to
`findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc`, same arguments, same return values. This is a
rename of the mocked method only; it must not change any assertion or expected value in the
existing tests. (`SavingsService` doesn't have a dedicated test file yet in this codebase — no
existing stub to rename there; only the new test below applies to it.)

Also add one new regression test to `AllocationServiceTest.java`, next to
`compute_stockGroupsByAssetClass_excludesOther` — it reuses that same file's existing
`account(id, name, type, balance)` static helper and the `service`/`MEMBER_ID`/`TODAY` fixtures
already declared at the top of the class:
```java
    @Test
    void compute_queriesHiddenExcludingRepositoryMethod() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(
            account(1L, "Compte courant", AccountType.CHECKING, "1000.00")
        ));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        verify(accountRepository).findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID);
        verify(accountRepository, never()).findAllByMemberIdOrderByCreatedAtAsc(any());
    }
```

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/picsou/service/DashboardService.java \
        backend/src/main/java/com/picsou/service/budget/AllocationService.java \
        backend/src/main/java/com/picsou/service/SavingsService.java \
        backend/src/test/java/com/picsou/service/DashboardServiceLiabilityTest.java \
        backend/src/test/java/com/picsou/service/budget/AllocationServiceTest.java
git commit -m "$(cat <<'EOF'
feat(accounts): exclude hidden accounts from net worth, allocation, savings suggestions

Sync/export/scheduler paths are intentionally left untouched — a
hidden account keeps syncing exactly like a visible one.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Frontend — shared account-tree grouping hook, extracted from `AccountsPage.tsx`

**Files:**
- Modify: `frontend/src/features/accounts/hooks.ts`
- Modify: `frontend/src/pages/accounts/AccountsPage.tsx`
- Modify: `frontend/src/types/api.ts`

**Interfaces:**
- Consumes: `Account[]` (from `useAccounts()`).
- Produces: `useAccountTree(accounts: Account[] | undefined)` returning
  `{ walletGroups: Array<{ wallet: Account; pockets: Account[] }>, standaloneAccounts: Account[],
  nonPocketAccounts: Account[] }` — the exact same three values `AccountsPage.tsx` computes today,
  now shared. Task 6 (new `/sync` tab) consumes this hook directly.

This task is a pure refactor: it must not change `/accounts`' rendered output at all.

- [ ] **Step 1: Add `hidden` to the `Account` TypeScript type**

In `frontend/src/types/api.ts`, add to the `Account` interface (after `savingsConfig`):
```ts
  /** Display-only visibility flag; a hidden account still syncs normally. */
  hidden: boolean
```

- [ ] **Step 2: Extract `useAccountTree` into `features/accounts/hooks.ts`**

Add this export to `frontend/src/features/accounts/hooks.ts` (needs `useMemo` from `react` —
add that import if not already present):
```ts
export interface AccountTree {
  walletGroups: Array<{ wallet: Account; pockets: Account[] }>
  standaloneAccounts: Account[]
  nonPocketAccounts: Account[]
}

/**
 * Groups a flat account list into wallet -> pockets, matching Account.parentAccountId.
 * A pocket whose parent isn't present in `accounts` (e.g. the wallet was soft-deleted) falls
 * back into nonPocketAccounts/standaloneAccounts instead of being silently dropped.
 */
export function useAccountTree(accounts: Account[] | undefined): AccountTree {
  return useMemo(() => {
    const accountIds = new Set((accounts ?? []).map((a) => a.id))

    const pocketsByParent = new Map<number, Account[]>()
    for (const a of (accounts ?? [])) {
      if (a.parentAccountId != null && accountIds.has(a.parentAccountId)) {
        if (!pocketsByParent.has(a.parentAccountId)) pocketsByParent.set(a.parentAccountId, [])
        pocketsByParent.get(a.parentAccountId)!.push(a)
      }
    }

    const nonPocketAccounts = (accounts ?? []).filter(
      (a) => a.parentAccountId == null || !accountIds.has(a.parentAccountId),
    )

    const walletGroups = nonPocketAccounts
      .filter((a) => pocketsByParent.has(a.id))
      .map((wallet) => ({ wallet, pockets: pocketsByParent.get(wallet.id)! }))

    const standaloneAccounts = nonPocketAccounts.filter((a) => !pocketsByParent.has(a.id))

    return { walletGroups, standaloneAccounts, nonPocketAccounts }
  }, [accounts])
}
```

- [ ] **Step 3: Replace the inline grouping logic in `AccountsPage.tsx` with the shared hook**

Remove the `accountIds`, `pocketsByParent`, and `nonPocketAccounts` `useMemo` blocks (added by the
earlier orphaned-pocket fix, commit `88075bc`) together with the pre-existing `walletGroups` and
`standaloneAccounts` `useMemo` blocks — i.e. everything from the `// ── Pocket grouping` comment
through the `standaloneAccounts` `useMemo`, currently:

```tsx
  // ── Pocket grouping ──────────────────────────────────────────────────────────
  //
  // ... (full block from the earlier fix) ...

  const walletGroups = useMemo(
    () =>
      filteredNonPockets
        .filter((a) => pocketsByParent.has(a.id))
        .map((wallet) => ({ wallet, pockets: pocketsByParent.get(wallet.id)! })),
    [filteredNonPockets, pocketsByParent],
  )

  // Standalone accounts: non-pockets without any child pockets
  const standaloneAccounts = useMemo(
    () => filteredNonPockets.filter((a) => !pocketsByParent.has(a.id)),
    [filteredNonPockets, pocketsByParent],
  )
```

Replace with:
```tsx
  const { nonPocketAccounts } = useAccountTree(accounts)

  // Non-pocket accounts filtered by the active asset-type filter
  const filteredNonPockets = useMemo(() => {
    const types = ASSET_FILTER_MAP[filter]
    if (!types) return nonPocketAccounts
    return nonPocketAccounts.filter((a) => types.includes(a.type))
  }, [nonPocketAccounts, filter])

  // Wallet groups and standalone accounts, recomputed on the FILTERED list so the asset-type
  // filter still applies to what's shown (useAccountTree itself works on the unfiltered list).
  const { walletGroups, standaloneAccounts } = useAccountTree(filteredNonPockets)
```

Note: this changes `filteredNonPockets` to be defined from `nonPocketAccounts` (now sourced from
the hook) instead of `accounts`/`pocketsByParent` directly — check the surrounding existing
`filteredNonPockets` `useMemo` in the current file; if it's already defined roughly this way,
adjust only its dependency on `nonPocketAccounts` to come from the hook rather than redefining it.

Add the import:
```tsx
import { useAccountTree } from '@/features/accounts/hooks'
```

- [ ] **Step 4: Typecheck, lint, build**

Run: `cd frontend && bun run typecheck && bun run lint && bun run build`
Expected: all green.

- [ ] **Step 5: Manually verify no visual regression**

Run: `cd frontend && bun run dev`, open `/accounts` in a browser, confirm wallet/pocket grouping,
filters, and the summary card render identically to before this refactor (same accounts, same
grouping, same totals).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/accounts/hooks.ts \
        frontend/src/pages/accounts/AccountsPage.tsx \
        frontend/src/types/api.ts
git commit -m "$(cat <<'EOF'
refactor(accounts): extract useAccountTree shared grouping hook

Pure extraction, no behavior change on /accounts. The new /sync
visibility tab (next commit) reuses this hook so the two views
can't drift apart.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Frontend — visibility toggle API + mutation hook

**Files:**
- Modify: `frontend/src/features/accounts/api.ts`
- Modify: `frontend/src/features/accounts/hooks.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: `accountsApi.listAll()` (`GET /accounts?includeHidden=true`),
  `accountsApi.setVisibility(id, hidden)` (`PUT /accounts/{id}/visibility`),
  `useAllAccounts()` (query hook), `useToggleAccountVisibility()` (mutation hook). Task 6 (the new
  tab component) consumes all four.

- [ ] **Step 1: Add the two API functions**

In `frontend/src/features/accounts/api.ts`, add to the `accountsApi` object (next to `list`):
```ts
  listAll: () => api.get<Account[]>('/accounts', { params: { includeHidden: true } }).then(r => r.data),
  setVisibility: (id: number, hidden: boolean) =>
    api.put<Account>(`/accounts/${id}/visibility`, { hidden }).then(r => r.data),
```

- [ ] **Step 2: Add the two hooks**

In `frontend/src/features/accounts/hooks.ts`, add:
```ts
export function useAllAccounts() {
  return useQuery({
    queryKey: ['accounts', 'all'],
    queryFn: accountsApi.listAll,
    staleTime: QUERY_STALE_TIMES.accounts,
  })
}

export function useToggleAccountVisibility() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, hidden }: { id: number; hidden: boolean }) => accountsApi.setVisibility(id, hidden),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: ['history'] })
    },
  })
}
```
(`QUERY_STALE_TIMES` is already imported in this file for other hooks — reuse the existing
import; do not add a second one.)

- [ ] **Step 3: Typecheck**

Run: `cd frontend && bun run typecheck`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/accounts/api.ts frontend/src/features/accounts/hooks.ts
git commit -m "$(cat <<'EOF'
feat(accounts): useAllAccounts + useToggleAccountVisibility hooks

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Frontend — new "Comptes" tab on `/sync`

**Files:**
- Create: `frontend/src/pages/sync/AccountsVisibilityTab.tsx`
- Modify: `frontend/src/pages/sync/SyncPage.tsx`
- Modify: `frontend/src/i18n/locales/{fr,en,de,es}.json`

**Interfaces:**
- Consumes: `useAllAccounts()`, `useToggleAccountVisibility()` (Task 5), `useAccountTree` (Task 4).
- Produces: a working, manually-testable `/sync?tab=visibility` tab.

- [ ] **Step 1: Write `AccountsVisibilityTab.tsx`**

```tsx
import { useTranslation } from 'react-i18next'
import { useAllAccounts, useToggleAccountVisibility, useAccountTree } from '@/features/accounts/hooks'
import { Card, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { Account } from '@/types/api'

function VisibilityRow({ account, indent = false }: { account: Account; indent?: boolean }) {
  const toggle = useToggleAccountVisibility()
  return (
    <div className={`flex items-center justify-between gap-3 py-2 ${indent ? 'pl-6' : ''}`}>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{account.name}</p>
        <CurrencyDisplay value={account.currentBalanceEur} className="text-xs text-muted-foreground" />
      </div>
      <Switch
        checked={!account.hidden}
        disabled={toggle.isPending}
        onCheckedChange={(visible) => toggle.mutate({ id: account.id, hidden: !visible })}
        aria-label={account.name}
      />
    </div>
  )
}

export function AccountsVisibilityTab() {
  const { t } = useTranslation()
  const { data: accounts, isLoading } = useAllAccounts()
  const { walletGroups, standaloneAccounts } = useAccountTree(accounts)

  // Group standalone accounts and wallets by provider so the tree reads
  // provider -> wallet -> pockets, per the approved design.
  const byProvider = new Map<string, Account[]>()
  for (const a of standaloneAccounts) {
    const key = a.provider ?? t('sync.visibility.manualGroup')
    if (!byProvider.has(key)) byProvider.set(key, [])
    byProvider.get(key)!.push(a)
  }
  const walletsByProvider = new Map<string, typeof walletGroups>()
  for (const g of walletGroups) {
    const key = g.wallet.provider ?? t('sync.visibility.manualGroup')
    if (!walletsByProvider.has(key)) walletsByProvider.set(key, [])
    walletsByProvider.get(key)!.push(g)
  }
  const providers = [...new Set([...byProvider.keys(), ...walletsByProvider.keys()])].sort()

  if (isLoading) {
    return (
      <div className="space-y-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-14 w-full rounded-xl" />
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">{t('sync.visibility.description')}</p>
      {providers.map((provider) => (
        <Card key={provider}>
          <CardContent className="divide-y">
            <p className="pb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {provider}
            </p>
            {(walletsByProvider.get(provider) ?? []).map(({ wallet, pockets }) => (
              <div key={wallet.id}>
                <VisibilityRow account={wallet} />
                {pockets.map((pocket) => (
                  <VisibilityRow key={pocket.id} account={pocket} indent />
                ))}
              </div>
            ))}
            {(byProvider.get(provider) ?? []).map((account) => (
              <VisibilityRow key={account.id} account={account} />
            ))}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
```

- [ ] **Step 2: Wire the new tab into `SyncPage.tsx`**

Add the import:
```tsx
import { AccountsVisibilityTab } from './AccountsVisibilityTab'
```

Add a new trigger to `TabsList` (after the "finary" trigger):
```tsx
          <TabsTrigger value="visibility">{t('sync.visibility.title')}</TabsTrigger>
```

Add a new `TabsContent` (after the "finary" content):
```tsx
        <TabsContent value="visibility" className="mt-6">
          <AccountsVisibilityTab />
        </TabsContent>
```

- [ ] **Step 3: Add i18n keys**

In `frontend/src/i18n/locales/fr.json`, inside the `"sync"` object (next to the `"finary"` key),
add:
```json
    "visibility": {
      "title": "Comptes",
      "description": "Choisis quels comptes apparaissent dans l'application. Un compte masqué continue d'être synchronisé normalement — il est juste retiré de l'affichage.",
      "manualGroup": "Manuel"
    }
```

Add the equivalent block in `en.json`:
```json
    "visibility": {
      "title": "Accounts",
      "description": "Choose which accounts appear in the app. A hidden account keeps syncing normally — it's just removed from display.",
      "manualGroup": "Manual"
    }
```

In `de.json`:
```json
    "visibility": {
      "title": "Konten",
      "description": "Wähle, welche Konten in der App angezeigt werden. Ein ausgeblendetes Konto wird weiterhin normal synchronisiert — es wird nur aus der Anzeige entfernt.",
      "manualGroup": "Manuell"
    }
```

In `es.json`:
```json
    "visibility": {
      "title": "Cuentas",
      "description": "Elige qué cuentas aparecen en la aplicación. Una cuenta oculta sigue sincronizándose con normalidad — solo se retira de la pantalla.",
      "manualGroup": "Manual"
    }
```

- [ ] **Step 4: Typecheck, lint, build**

Run: `cd frontend && bun run typecheck && bun run lint && bun run build`
Expected: all green, no missing-i18n-key warnings.

- [ ] **Step 5: Manually verify in a browser**

Run: `cd frontend && bun run dev`, open `/sync?tab=visibility`. Confirm: accounts render grouped by
provider, wallets show their pockets indented underneath, every switch reflects the account's
current visibility, toggling a switch persists (reload the page, the state survives) and the
corresponding account disappears/reappears on `/accounts`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/sync/AccountsVisibilityTab.tsx \
        frontend/src/pages/sync/SyncPage.tsx \
        frontend/src/i18n/locales/fr.json \
        frontend/src/i18n/locales/en.json \
        frontend/src/i18n/locales/de.json \
        frontend/src/i18n/locales/es.json
git commit -m "$(cat <<'EOF'
feat(sync): add Comptes tab for per-account visibility toggling

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Documentation

**Files:**
- Create: `docs/features/account-visibility.md` (use `docs/templates/FEATURE.md` as the template)
- Modify: `docs/INDEX.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: Write the feature doc**

Follow `docs/templates/FEATURE.md`'s structure. Cover: the `hidden` column and why it's not part of
`@SQLRestriction` (link back to the `deleted_at`/sync-matching distinction), the
`GET /accounts?includeHidden=true` + `PUT /accounts/{id}/visibility` endpoints, which services
exclude hidden accounts (`AccountService.findAll` default, `DashboardService`, `AllocationService`,
`SavingsService`) and which intentionally do not (sync/export/scheduler — link the rationale from
Task 3), and the new `/sync` "Comptes" tab + `useAccountTree` shared hook.

- [ ] **Step 2: Add the row to `docs/INDEX.md`**

Add a row under "Feature notes" pointing to the new doc, matching the existing table format and
column order already used by neighboring rows.

- [ ] **Step 3: Commit**

```bash
git add docs/features/account-visibility.md docs/INDEX.md
git commit -m "$(cat <<'EOF'
docs: add account-visibility feature note

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
