# Remove PSD2 Pocket-Guess Reconstruction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `RevolutPocketService`'s PSD2 transfer-pattern pocket reconstruction (backend +
frontend), now that the Revolut sidecar (Camoufox) syncs real pockets with real balances.

**Architecture:** Delete the guessing service, its controller, its DTOs, and its 5 wiring points
in `SyncService`. Delete the frontend onboarding/naming UI and the "allocated" balance label. Keep
the generic `parentAccountId` parent/child modeling and the wallet→pockets grouping UI — the
sidecar reuses both.

**Tech Stack:** Java 21 / Spring Boot (backend), React 19 / TypeScript / TanStack Query (frontend).

## Global Constraints

- Backend: `mvn test` must stay green after every task.
- Frontend: `bun run typecheck && bun run lint && bun run build` must stay green after every task.
- Never touch `Account.parentAccountId`, the `pocketsByParent`/`walletGroups` grouping logic in
  `AccountsPage.tsx`, or `findRevolutWalletsByMemberId` in `AccountRepository` — all three are
  reused by the sidecar connector (`RevolutSyncService`) and must survive this removal intact.
- Data cleanup (soft-deleting existing guessed pocket rows in `picsou_prod`) is a separate,
  explicitly-approved manual step — never bundled into an auto-applied Flyway migration.

---

### Task 1: Remove backend `RevolutPocketService` and its wiring

**Files:**
- Delete: `backend/src/main/java/com/picsou/service/RevolutPocketService.java`
- Delete: `backend/src/main/java/com/picsou/controller/RevolutPocketController.java`
- Delete: `backend/src/main/java/com/picsou/dto/UnnamedPocketResponse.java`
- Delete: `backend/src/main/java/com/picsou/dto/RevolutCsvNamingResponse.java`
- Delete: `backend/src/test/java/com/picsou/service/RevolutPocketServiceTest.java`
- Delete: `backend/src/test/java/com/picsou/service/RevolutCsvNamingTest.java`
- Delete: `backend/src/test/java/com/picsou/service/SyncServicePocketTest.java`
- Delete: `backend/src/test/java/com/picsou/service/SyncServiceRevolutBackfillTest.java`
- Modify: `backend/src/main/java/com/picsou/service/SyncService.java`
- Modify: `backend/src/main/java/com/picsou/repository/AccountRepository.java`
- Modify: `backend/src/test/java/com/picsou/service/SyncServiceTest.java`
- Modify: `backend/src/test/java/com/picsou/service/SyncServiceIbanMatchTest.java`
- Modify: `backend/src/test/java/com/picsou/service/RevolutSyncServiceTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SyncService` with no `RevolutPocketService` dependency, no pocket-backfill/detection
  behavior. `AccountRepository` without `findAllPocketsByMemberId`,
  `findPocketByParentAndUuid`, `existsSoftDeletedPocketByParentAndUuid`. Later tasks (frontend)
  rely on `GET /api/revolut-pockets/*` no longer existing (404).

- [ ] **Step 1: Delete the four files above (service, controller, 2 DTOs)**

```bash
git rm backend/src/main/java/com/picsou/service/RevolutPocketService.java
git rm backend/src/main/java/com/picsou/controller/RevolutPocketController.java
git rm backend/src/main/java/com/picsou/dto/UnnamedPocketResponse.java
git rm backend/src/main/java/com/picsou/dto/RevolutCsvNamingResponse.java
```

- [ ] **Step 2: Delete the four pocket-specific test files**

```bash
git rm backend/src/test/java/com/picsou/service/RevolutPocketServiceTest.java
git rm backend/src/test/java/com/picsou/service/RevolutCsvNamingTest.java
git rm backend/src/test/java/com/picsou/service/SyncServicePocketTest.java
git rm backend/src/test/java/com/picsou/service/SyncServiceRevolutBackfillTest.java
```

- [ ] **Step 3: Remove the `RevolutPocketService` field, constructor param, and `runPocketBackfill` helper from `SyncService.java`**

In `backend/src/main/java/com/picsou/service/SyncService.java`:

Remove the field (was line 38):
```java
    private final RevolutPocketService revolutPocketService;
```

Remove the constructor parameter (was line 52, note the trailing comma on the previous line — `RecurringDetectionService recurringDetectionService` becomes the last param) and its constructor body assignment (was line 62):
```java
        RevolutPocketService revolutPocketService
```
```java
        this.revolutPocketService = revolutPocketService;
```

Remove the whole `runPocketBackfill` method (was lines 77-92):
```java
    /**
     * Run the Revolut pocket backfill for a member after all accounts and their transactions
     * have been upserted. The backfill reconstructs pockets over the member's full history
     * (not just the 90-day sync window), so historical "To … MB" rows are cleaned up too.
     * Isolated from the enclosing sync transaction the same way {@link #detectRecurring} is —
     * a backfill failure must never roll back freshly-ingested balances.
     * <p>
     * No-op if the member has no Revolut wallet accounts.
     */
    private void runPocketBackfill(Long memberId) {
        try {
            revolutPocketService.backfillForMember(memberId);
        } catch (Exception ex) {
            log.warn("Revolut pocket backfill skipped for member {}: {}", memberId, ex.getMessage());
        }
    }
```

Remove the 4 call sites (each is a standalone line `runPocketBackfill(member.getId());` immediately
after a `detectRecurring(member.getId());` call, inside `completeConnection`, `retrySync`, the
`resyncAll` loop, and `resyncLatest` — search-and-delete each line, leaving the surrounding
`detectRecurring(...)` calls and everything else untouched):
```java
        runPocketBackfill(member.getId());
```

Remove the ingest hook call and its comment, inside `ingestTransactions` (in the `for` loop over
fetched transactions, right after `transactionRepository.save(tx);`):
```java
            // After persisting, detect and process Revolut pocket transfers.
            revolutPocketService.processTransaction(tx, member.getId());
```

- [ ] **Step 4: Remove the three pocket-only repository methods from `AccountRepository.java`**

Remove `findAllPocketsByMemberId`:
```java
    /**
     * All pocket sub-accounts member: parent_account_id IS NOT NULL.
     */
    @Query("""
        SELECT a FROM Account a
        WHERE a.member.id = :memberId AND a.parentAccountId IS NOT NULL
        """)
    List<Account> findAllPocketsByMemberId(@Param("memberId") Long memberId);
```

Remove `findPocketByParentAndUuid`:
```java
    /**
     * Find existing Revolut pocket sub-account by stable uuid (external_account_id)
     * and parent wallet id. Used for idempotent find-or-create.
     */
    @Query("""
        SELECT a FROM Account a
        WHERE a.member.id = :memberId
        AND a.parentAccountId = :parentAccountId
        AND a.externalAccountId = :pocketUuid
        """)
    Optional<Account> findPocketByParentAndUuid(
        @Param("memberId") Long memberId,
        @Param("parentAccountId") Long parentAccountId,
        @Param("pocketUuid") String pocketUuid
    );
```

Remove `existsSoftDeletedPocketByParentAndUuid`:
```java
    /**
     * True if a soft-deleted pocket sub-account exists for this parent + uuid.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Used by pocket reconstruction to refuse resurrecting pockets the user explicitly removed.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        " WHERE member_id = :memberId AND parent_account_id = :parentAccountId " +
        " AND external_account_id = :pocketUuid AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedPocketByParentAndUuid(
        @Param("memberId") Long memberId,
        @Param("parentAccountId") Long parentAccountId,
        @Param("pocketUuid") String pocketUuid
    );
```

**Keep** `findRevolutWalletsByMemberId` unchanged — `RevolutSyncService.java:344` still calls it.

- [ ] **Step 5: Remove the `@Mock RevolutPocketService revolutPocketService` line and any constructor-arg usage from the two remaining `SyncService` test files**

In `backend/src/test/java/com/picsou/service/SyncServiceTest.java`, remove:
```java
    @Mock RevolutPocketService revolutPocketService;
```
And remove `revolutPocketService` from every `new SyncService(...)` constructor call in that file
(it's currently the last constructor argument — delete the argument and its preceding comma).

Do the same in `backend/src/test/java/com/picsou/service/SyncServiceIbanMatchTest.java`.

- [ ] **Step 6: Fix the two stale doc-comments in `RevolutSyncServiceTest.java` that reference the now-deleted `RevolutPocketService`**

The test logic itself (`sync_rememberFalse_clearsCredentialsButKeepsSessionMarkerRow`,
`sync_rememberFalse_firstEverSync_stillCreatesSessionMarkerRow`) is still correct and must NOT
change — the `RevolutSession` marker row is still needed to show `lastSyncedAt` status in
`RevolutTab.tsx` regardless of whether credentials are remembered. Only the comments are stale.

Replace (around line 330-334):
```java
    /**
     * remember=false never persists credentials and clears any previously-remembered ones -- but
     * the row itself is kept (not deleted): it doubles as the "sidecar has synced this member"
     * marker that {@code RevolutPocketService} relies on to stand down its PSD2 heuristic
     * reconstruction, regardless of whether credentials were remembered.
     */
```
with:
```java
    /**
     * remember=false never persists credentials and clears any previously-remembered ones -- but
     * the row itself is kept (not deleted): RevolutTab shows lastSyncedAt status from it
     * regardless of whether credentials were remembered.
     */
```

Replace (around line 357-362):
```java
    /**
     * Regression guard for the RevolutPocketService guard fix: even a member who NEVER remembers
     * credentials must get a RevolutSession row with lastSyncedAt set on their very first sync, or
     * RevolutPocketService cannot tell "the sidecar connector has synced this member" apart from
     * "no on-demand connector used at all" and would wrongly keep running its PSD2 heuristic
     * reconstruction alongside the real synced pockets.
     */
```
with:
```java
    /**
     * Even a member who NEVER remembers credentials must get a RevolutSession row with
     * lastSyncedAt set on their very first sync, so RevolutTab can show sync status immediately.
     */
```

- [ ] **Step 7: Compile and run the full backend test suite**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`, no reference-to-deleted-class compile errors, no failing tests.

- [ ] **Step 8: Commit**

```bash
cd backend
git add -A
git commit -m "$(cat <<'EOF'
fix(revolut): remove PSD2 pocket-guess reconstruction

RevolutPocketService reconstructed pockets by pattern-matching PSD2
transfer descriptions on the Enable Banking wallet, with an
inflow-only "allocated" balance. The Revolut sidecar (Camoufox) now
syncs real pockets with real balances via the same parentAccountId
modeling, making the PSD2 heuristic obsolete.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Remove frontend pocket-guess onboarding UI and "allocated" label

**Files:**
- Delete: `frontend/src/features/pockets/api.ts`
- Delete: `frontend/src/features/pockets/hooks.ts`
- Delete: `frontend/src/features/pockets/PocketOnboardingModal.tsx`
- Delete: `frontend/src/types/pockets.ts`
- Modify: `frontend/src/pages/accounts/AccountsPage.tsx`
- Modify: `frontend/src/pages/accounts/AccountDetailPage.tsx`
- Modify: `frontend/src/demo/index.ts`
- Modify: `frontend/src/demo/data/accounts.ts`
- Modify: `frontend/src/i18n/locales/{fr,en,de,es}.json`

**Interfaces:**
- Consumes: nothing new (this task only removes code).
- Produces: `AccountsPage.tsx` and `AccountDetailPage.tsx` with no references to
  `unnamedPockets`, `PocketOnboardingModal`, `useUnnamedPockets`, `pockets.allocatedLabel`,
  `pockets.allocatedTooltip`, `pockets.unnamedPocketsBanner`, `pockets.nameYourPockets`.
  `PocketCard` in `AccountsPage.tsx` still exists and still renders the wallet→pockets tree — it
  just shows a plain balance like any other account card.

- [ ] **Step 1: Delete the pockets feature directory and standalone type file**

```bash
git rm -r frontend/src/features/pockets
git rm frontend/src/types/pockets.ts
```

- [ ] **Step 2: Remove the onboarding banner, modal, and hook usage from `AccountsPage.tsx`**

Remove the import lines:
```tsx
import { useUnnamedPockets } from '@/features/pockets/hooks'
import { PocketOnboardingModal } from '@/features/pockets/PocketOnboardingModal'
```

Remove the hook call and derived flag:
```tsx
  const { data: unnamedPockets } = useUnnamedPockets()
```
```tsx
  const hasUnnamedPockets = (unnamedPockets?.length ?? 0) > 0
```

Remove the `showPocketModal` state (it's only used by the banner button and the modal below):
```tsx
  const [showPocketModal, setShowPocketModal] = useState(false)
```

Remove the entire "Unnamed pockets banner" block:
```tsx
      {/* Unnamed pockets banner */}
      {hasUnnamedPockets && unnamedPockets && (
        <div className="flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 dark:border-amber-900/50 dark:bg-amber-950/30">
          <p className="text-sm text-amber-800 dark:text-amber-300">
            {t('pockets.unnamedPocketsBanner', { count: unnamedPockets.length })}
          </p>
          <Button
            size="sm"
            variant="outline"
            className="ml-4 shrink-0"
            onClick={() => setShowPocketModal(true)}
          >
            {t('pockets.nameYourPockets')}
          </Button>
        </div>
      )}
```

Remove the modal render block at the bottom of the component:
```tsx
      {/* Pocket onboarding modal — shown when unnamed pockets exist */}
      {unnamedPockets && unnamedPockets.length > 0 && accounts && (
        <PocketOnboardingModal
          open={showPocketModal}
          onOpenChange={setShowPocketModal}
          pockets={unnamedPockets}
          accounts={accounts}
        />
      )}
```

- [ ] **Step 3: Remove the "allocated" tooltip from `PocketCard` in `AccountsPage.tsx`**

Replace the `PocketCard` component body:
```tsx
function PocketCard({ account, onClick }: { account: Account; onClick?: () => void }) {
  const { t } = useTranslation()
  return (
    <Card
      className="cursor-pointer transition-shadow hover:shadow-md"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-3 sm:p-4">
        <div
          className="mt-1 h-8 w-1 shrink-0 rounded-full"
          style={{ backgroundColor: account.color }}
        />
        <div className="min-w-0 flex-1">
          <span className="truncate text-sm font-medium">{account.name}</span>
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            <CurrencyDisplay value={account.currentBalanceEur} className="text-base font-semibold" />
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <span className="flex cursor-help items-center gap-0.5 text-xs text-muted-foreground">
                    {t('pockets.allocatedLabel')}
                    <Info className="size-3" />
                  </span>
                </TooltipTrigger>
                <TooltipContent className="max-w-56 text-center text-xs">
                  {t('pockets.allocatedTooltip')}
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
```
with:
```tsx
function PocketCard({ account, onClick }: { account: Account; onClick?: () => void }) {
  return (
    <Card
      className="cursor-pointer transition-shadow hover:shadow-md"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-3 sm:p-4">
        <div
          className="mt-1 h-8 w-1 shrink-0 rounded-full"
          style={{ backgroundColor: account.color }}
        />
        <div className="min-w-0 flex-1">
          <span className="truncate text-sm font-medium">{account.name}</span>
          <div className="mt-1">
            <CurrencyDisplay value={account.currentBalanceEur} className="text-base font-semibold" />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
```

Remove the now-unused imports at the top of the file if no other symbol in the file uses them:
`Tooltip, TooltipContent, TooltipProvider, TooltipTrigger` (from `@/components/ui/tooltip`) and
`Info` (from `lucide-react`) — check with a search first (`grep -n "Tooltip\|<Info" frontend/src/pages/accounts/AccountsPage.tsx`) since `useTranslation` is still used elsewhere in the file; only drop what's actually unused.

- [ ] **Step 4: Remove the "allocated" label from `AccountDetailPage.tsx`**

Around line 111, `const isPocket = account?.parentAccountId != null` and the block at lines
301-313 that conditionally renders `pockets.allocatedLabel`/`pockets.allocatedTooltip` instead of
the normal balance label — replace the conditional with the normal (non-pocket) balance label
unconditionally, and remove the now-dead `isPocket` variable if nothing else in the file uses it
(check with `grep -n isPocket frontend/src/pages/accounts/AccountDetailPage.tsx` first).

- [ ] **Step 5: Remove pocket-onboarding mock data and handlers from the demo layer**

In `frontend/src/demo/index.ts` and `frontend/src/demo/data/accounts.ts`, remove any mock
`GET /revolut-pockets/unnamed` / `POST /revolut-pockets/csv-naming` handlers and any
demo-mode-only "unnamed pocket" seed accounts (search `grep -n "revolut-pockets\|unnamed" frontend/src/demo/index.ts frontend/src/demo/data/accounts.ts` first to find the exact blocks — the
demo mock accounts for the wallet→pockets *grouping itself* should stay, since the sidecar's real
pockets use the same parent/child shape and the demo should keep exercising that UI).

- [ ] **Step 6: Remove now-unused i18n keys**

In each of `frontend/src/i18n/locales/{fr,en,de,es}.json`, under the `pockets` namespace, remove:
`allocatedLabel`, `allocatedTooltip`, `unnamedPocketsBanner`, `nameYourPockets`, and any
onboarding-modal-specific keys (naming form labels, CSV upload strings — search
`grep -n '"pockets"' -A 40 frontend/src/i18n/locales/fr.json` to see the full block first). Keep
`pockets.subAccountsCount` (used by the generic wallet→pockets grouping header in
`AccountsPage.tsx`, unrelated to the guess mechanism).

- [ ] **Step 7: Typecheck, lint, build**

Run: `cd frontend && bun run typecheck && bun run lint && bun run build`
Expected: all three succeed, no unused-import warnings, no missing i18n key warnings.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add -A
git commit -m "$(cat <<'EOF'
fix(revolut): remove pocket-guess onboarding UI and allocated label

Companion to the backend RevolutPocketService removal. Pockets now
only ever come from the Revolut sidecar with a real balance, so
PocketCard and AccountDetailPage no longer show the "allocated,
not the real balance" caveat, and the unnamed-pockets naming
onboarding modal is gone.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Update documentation

**Files:**
- Modify: `docs/decisions/2026-06-28-revolut-pockets-reconstruction.md`
- Modify: `docs/features/revolut-pockets.md`
- Modify: `docs/INDEX.md`

**Interfaces:** none (docs only).

- [ ] **Step 1: Mark the ADR as superseded**

At the top of `docs/decisions/2026-06-28-revolut-pockets-reconstruction.md`, immediately after the
title, insert:
```markdown
> **Superseded (2026-07-14):** the Revolut sidecar connector (Camoufox) now syncs real pockets
> with real balances directly from `app.revolut.com` — see
> [`revolut-sidecar.md`](../features/revolut-sidecar.md). This PSD2 heuristic reconstruction has
> been removed from the codebase; this document is kept as a historical record of the original
> decision and its trade-offs.
```
Leave the rest of the document unchanged (historical record).

- [ ] **Step 2: Mark the feature doc as removed**

At the top of `docs/features/revolut-pockets.md`, immediately after the title, insert:
```markdown
> **Removed (2026-07-14):** superseded by the Revolut sidecar connector, which syncs real
> pockets — see [`revolut-sidecar.md`](./revolut-sidecar.md) and
> [the superseding ADR note](../decisions/2026-06-28-revolut-pockets-reconstruction.md). This
> document describes a feature no longer present in the codebase; kept for historical context.
```
Leave the rest of the document unchanged.

- [ ] **Step 3: Update `docs/INDEX.md`**

Find the row referencing `revolut-pockets.md` under "Feature notes" and append " (removed,
2026-07-14)" to its description cell, matching the style already used elsewhere in this index for
retired features (check the file for an existing precedent of that phrasing before assuming the
exact wording — grep `-i "removed\|deprecated\|superseded"` in `docs/INDEX.md` first).

- [ ] **Step 4: Commit**

```bash
git add docs/decisions/2026-06-28-revolut-pockets-reconstruction.md docs/features/revolut-pockets.md docs/INDEX.md
git commit -m "$(cat <<'EOF'
docs: mark PSD2 pocket reconstruction as superseded by Revolut sidecar

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Produce (but do not auto-run) the production data-cleanup script

**Files:**
- Create: `docs/briefs/2026-07-14-revolut-pocket-cleanup.sql`

**Interfaces:** none — this is a standalone reviewed artifact, not wired into the application or
into Flyway.

- [ ] **Step 1: Write the reviewed cleanup script**

```sql
-- Revolut pocket-guess data cleanup — run manually against picsou_prod after Chloé's review.
-- Soft-deletes every Revolut pocket sub-account: these were all created by the now-removed
-- PSD2 transfer-pattern heuristic (the sidecar connector has never completed a live sync
-- against this database yet, per docs/features/revolut-sidecar.md).
--
-- Step 1 — PREVIEW (run first, read the output, confirm row count/names look right):
SELECT id, name, provider, type, parent_account_id, current_balance, deleted_at
FROM account
WHERE provider = 'Revolut' AND parent_account_id IS NOT NULL AND deleted_at IS NULL
ORDER BY id;

-- Step 2 — APPLY (only after the preview above has been reviewed and approved):
-- UPDATE account
-- SET deleted_at = now()
-- WHERE provider = 'Revolut' AND parent_account_id IS NOT NULL AND deleted_at IS NULL;
```

- [ ] **Step 2: Commit the script (uncommented UPDATE — never runs on its own)**

```bash
git add docs/briefs/2026-07-14-revolut-pocket-cleanup.sql
git commit -m "$(cat <<'EOF'
docs: reviewed SQL script to soft-delete guessed Revolut pockets

Preview query + commented-out UPDATE, for Chloé to run manually
against picsou_prod after reviewing the affected rows. Not wired
into Flyway — a migration can't be reviewed before it applies.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Stop — do not execute this script against `picsou_prod`**

Executing it is a separate, explicit action: run the Step 1 preview against `picsou_prod` first,
show Chloé the affected rows, and only run the Step 2 `UPDATE` after she confirms in that later
session/conversation.
