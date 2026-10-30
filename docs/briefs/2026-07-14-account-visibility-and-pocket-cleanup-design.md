# Design: Account visibility toggle + removal of PSD2 pocket-guessing

> Date: 2026-07-14
> Branch: `1.1.0`
> Status: Approved by Chloé, pending implementation plan

## Context

Two related decisions, made in the same session, both touching how Revolut pocket sub-accounts
are modeled and displayed:

1. **`docs/features/revolut-pockets.md`** (shipped 2026-06-28) reconstructs pocket sub-accounts by
   pattern-matching `To <ccy> MB:<uuid>` transactions on the Enable Banking (PSD2) main wallet.
   Balance is inflow-only ("allocated"), never the real balance — PSD2 doesn't expose pocket data.
2. **`docs/features/revolut-sidecar.md`** (shipped 2026-07-08, Camoufox-based) logs into
   `app.revolut.com` directly and harvests real pockets/vaults with real names, real balances, real
   transactions — using the *same* `parentAccountId` sub-account modeling as (1).

(2) makes (1) obsolete: guessing pocket existence/balance from PSD2 transfer patterns is no longer
needed when a connector can read the real thing. Chloé: *"soit on sync les pockets via Revolut
sidecar soit rien, mais on cherche plus à deviner."*

Separately, an earlier debugging session in this same conversation (2026-07-14) found why Chloé's
Revolut accounts/pockets were invisible on `/accounts`: a wallet's soft-delete orphaned its
still-alive pocket children, which then fell through both the "grouped" and "standalone" rendering
paths (fixed in commit `88075bc`). While discussing that fix, Chloé asked for a way to hide an
account from display **without** stopping its sync — soft-delete (`deleted_at`) currently does
both, because the entity-level `@SQLRestriction("deleted_at IS NULL")` also gates the
sync-matching queries.

## Part 1 — Remove PSD2 pocket reconstruction

### Scope

Delete entirely:

- Backend: `RevolutPocketService.java`, `RevolutPocketController.java`
  (`/api/revolut-pockets/unnamed`, `/api/revolut-pockets/csv-naming`), `UnnamedPocketResponse`,
  `RevolutCsvNamingResponse`, the 4 `backfillForMember()` call sites in `SyncService`
  (`completeConnection`, `retrySync`, `resyncAll`, `resyncLatest`).
- Frontend: `features/pockets/*` (`api.ts`, `hooks.ts`, `PocketOnboardingModal.tsx`), the
  "unnamed pockets" banner + `showPocketModal` state in `AccountsPage.tsx`, `pockets.*` i18n keys
  tied to onboarding/CSV naming.
- The `"allocated"` label/tooltip (`pockets.allocatedLabel`, `pockets.allocatedTooltip`) in
  `PocketCard` (`AccountsPage.tsx`). A pocket now only ever comes from the sidecar (real balance),
  so `PocketCard` renders balance like any other account card — no more "this isn't the real
  balance" caveat.

Keep:

- The parent/child account modeling (`Account.parentAccountId`) and the wallet→pockets grouping UI
  (`pocketsByParent`, `walletGroups` in `AccountsPage.tsx`) — generic infrastructure, used by the
  sidecar connector too.
- The orphaned-pocket display fix from this session (commit `88075bc`) — it's a general
  parent/child correctness fix, applicable regardless of which connector created the pocket.

### Existing data cleanup

Every Revolut pocket account currently in `picsou_prod` (`provider = 'Revolut' AND
parent_account_id IS NOT NULL`) was created by the PSD2 guess — the sidecar has never completed a
live run against prod yet (per `revolut-sidecar.md`: *"live end-to-end coverage still depends on a
real Revolut approval session"*). These rows get soft-deleted (`deleted_at = now()`), same
mechanism as the existing trash-icon delete — reversible, not a hard `DELETE`.

This is a **data-mutating action on Chloé's production database**. It is not part of the Flyway
migration (irreversible if wrong, auto-applied at every boot) — it runs as an explicit, reviewed
`UPDATE` that Chloé approves before it's executed against `picsou_prod`, separate from shipping the
code change.

### Documentation

- `docs/decisions/2026-06-28-revolut-pockets-reconstruction.md` → marked **Superseded**, pointing
  to `revolut-sidecar.md`. Not deleted (historical record of why approach B was chosen at the time).
- `docs/features/revolut-pockets.md` → marked superseded/removed at the top, matching the ADR.

## Part 2 — Per-account visibility toggle

### Data model

`account.hidden boolean NOT NULL DEFAULT false` — new column, new Flyway migration on this branch
(next free local number; branch-migration-number divergence with `main`/prod is an existing,
already-documented pattern in this repo, reconciled at merge time).

`hidden` is intentionally **not** added to the entity's `@SQLRestriction("deleted_at IS NULL")`.
That restriction is global — it also gates every sync-matching query
(`findByExternalAccountIdAndMemberId`, `findByIbanAndMemberId`, `findRevolutWalletsByMemberId`,
`findPocketByParentAndUuid`, etc.). Baking `hidden` into it would silently stop a hidden account
from being matched/updated by sync — exactly the soft-delete behavior Chloé wants to avoid for this
feature ("masqué partout... malgré le fait qu'on les sync/get").

### Backend filtering (display-only, explicit per call site)

- `AccountService.findAll(memberId)` (→ `GET /api/accounts`, feeds `/accounts`) excludes `hidden`
  accounts by default.
- `GET /api/accounts?includeHidden=true` — new query param, returns every non-deleted account
  (hidden or not). Used only by the new `/sync` visibility tab.
- `DashboardService` (net worth), `HistoryService` (history/PnL charts), and budget/cashflow
  aggregation — audited and updated to exclude `hidden` accounts from every user-facing total,
  mirroring the existing pocket-child exclusion already present in `DashboardService`.
- Sync/matching paths (`SyncService`, `RevolutSyncService`, upsert-by-external-id/IBAN) are
  **untouched** — a hidden account keeps syncing exactly like a visible one.

### New endpoint

`PUT /api/accounts/{id}/visibility` — body `{ hidden: boolean }`. Same small-dedicated-endpoint
pattern as `/real-estate` and `/debt` on `AccountController`.

### Frontend — new `/sync` tab

New tab **"Comptes"** in `SyncPage.tsx` (after Finary), component `AccountsVisibilityTab.tsx`:

- Fetches `GET /api/accounts?includeHidden=true`.
- Renders an indented tree: **provider** (Revolut, BoursoBank, Trade Republic, Manuel, …) →
  **wallet** → **pockets** — reusing the same parent/child grouping logic as `AccountsPage.tsx`,
  extracted into a shared hook in `features/accounts/hooks.ts` so the two views can't drift apart.
- Each row: account name, small/muted balance, a `Switch` (shadcn) wired to
  `useToggleAccountVisibility()` → `PUT /accounts/{id}/visibility`. On success, invalidates
  `['accounts']`, `['dashboard']`, `['history']`.
- All pre-existing accounts default to `hidden = false` (migration default) — no visible behavior
  change until someone flips a toggle.

## Out of scope

- Any change to how `deleted_at` soft-delete works today (still stops sync-matching — unchanged).
- Per-member visibility (an account belongs to one member already; no multi-viewer visibility
  concept here).
- A guard preventing deletion of a wallet with live pocket children (flagged as a separate,
  unscoped follow-up during the earlier bugfix — not part of this design).
