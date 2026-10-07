# ADR: Reconstruct Revolut pockets from PSD2 internal-transfer rows

> **Superseded (2026-07-14):** the Revolut sidecar connector (Camoufox) now syncs real pockets
> with real balances directly from `app.revolut.com` — see
> [`revolut-sidecar.md`](../features/revolut-sidecar.md). This PSD2 heuristic reconstruction has
> been removed from the codebase; this document is kept as a historical record of the original
> decision and its trade-offs.

> Date: 2026-06-28
> Status: ⚠️ Superseded (2026-07-14)

## Context

Revolut "pockets" (user-created sub-wallets) are not exposed as separate accounts by Enable Banking (PSD2). Only the main Revolut wallet is synced. When the user moves money into a pocket, it appears on the main wallet as a debit row with a description matching `To <CCY> MB:<uuid>` (MB = Revolut-internal money-beneficiary id; the UUID is a stable, per-pocket identifier).

This creates two concrete problems:

1. **Cashflow pollution** — transfer rows are not detected as transfers and are counted as real expenses in the cashflow/Sankey views.
2. **Pocket balances invisible** — the user cannot see per-pocket allocation inside Picsou.

The user also spends directly from pockets. That spending is structurally unavailable via PSD2 (neither the pocket account nor its internal transactions are returned). Any realistic solution must therefore be honest about the inflows-only limitation and remain compatible with a future CSV-based spending import.

## Decision

**Approach B — reconstruct pockets from existing PSD2 rows.**

- A pocket is modelled as a regular `Account` row (`type = CHECKING`, `provider = 'Revolut'`, `is_manual = false`) with a new nullable FK column `account.parent_account_id` pointing at the parent Revolut wallet (Flyway **V43**).
- Detection regex on the wallet's transaction descriptions (case-insensitive): `^to\s+[a-z]{3}\s+mb:(?<uuid>[0-9a-f-]{8,})$`.
- For each match, `RevolutPocketService` (a) finds or creates the pocket sub-account keyed by uuid, (b) reclassifies the main-wallet debit to `virement-interne` (resolved by slug via `CategorizationService.categoriesBySlug()`, unconditional override), and (c) mirrors a `+amount` credit leg into the pocket with a deterministic `external_id = pocket-mirror:<source>` for idempotency.
- Pockets are grouped under their parent wallet in the frontend; their balance is labelled **"allocated"** with a tooltip explaining it represents total transferred-in, not the real current balance.
- Backfill (`backfillForMember`) is wired into `SyncService` at four points: `completeConnection`, `retrySync`, `resyncAll`, `resyncLatest`.

## Alternatives considered

### Wait for an API that exposes pockets natively

- **Pros**: accurate balances, real internal spending available.
- **Cons**: no such API exists in Enable Banking today; timeline unknown; blocks an immediate user need.

### Dedicated `pocket` table

- **Pros**: cleaner separation between "real accounts" and "reconstructed pockets".
- **Cons**: requires bespoke repository, service, and UI layers; pockets could not reuse the existing account list, balance history, transaction view, or net-worth computation without bridging code. The `Account` abstraction already covers everything needed.

### `SAVINGS` account type for pockets

- **Pros**: visually distinguishes pockets from current accounts.
- **Cons**: `AllocationService` counts `SAVINGS`-class inflows as savings contributions, which would artificially inflate the savings metric every time the user moves money into a pocket. `CHECKING` (→ `AssetClass.CURRENT`) avoids this.

### New `transfer_flag` column on `transaction`

- **Pros**: explicit marker, no dependency on category slug.
- **Cons**: `CashflowService.isTransfer()` already gates on `categoryRef != null && kind == TRANSFER`; a second column adds schema complexity for no gain. The slug-based reclassification reuses the existing transfer exclusion path without any schema change to `transaction`.

## Reasoning

Approach B delivers an immediate fix — cashflow de-pollution and per-pocket visibility — using only PSD2 data that already flows into Picsou. It reuses the `Account` abstraction end-to-end (repository, balance history, net-worth, frontend), needs only one new nullable FK column, and keeps the door open for a future CSV spending import (approach A) without requiring any schema migration at that point.

## Trade-offs accepted

- **Pocket balance = inflows only.** Total transferred-in is displayed as the balance, not the real pocket balance (which would require internal spending data). The UI is explicit about this limitation ("allocated" label + tooltip).
- **Detection overrides manual categories.** If the user had manually tagged a `To … MB` row with a custom category, reclassification to `virement-interne` overrides it. This is intentional — the row is a transfer, not an expense — and is documented, not silent. The `CategorizationService.autoCategorize()` guard (`if (tx.getCategoryRef() != null) return false`) then protects the reclassified leg from further overwrite by keyword rules.
- **CSV pocket-naming match is heuristic.** Amount + date reconciliation against a Revolut export can yield false positives when two transfers share the same amount and date. Uncertain matches are flagged; the user always confirms before a name is applied.
- **Expense totals drop after backfill.** Historical pocket-transfer rows leave cashflow spending. This is the correct behaviour but is a visible change to historical numbers.

## Consequences

- Flyway V43 adds `account.parent_account_id` (nullable FK + index). No other schema change to `transaction`.
- `AccountResponse` exposes `parentAccountId` (nullable Long).
- New endpoints: `GET /api/revolut-pockets/unnamed` → `UnnamedPocketResponse[]`; `POST /api/revolut-pockets/csv-naming` (multipart) → suggestions list. Renaming reuses `PUT /accounts/{id}`.
- `SyncService` gains four wiring points for `RevolutPocketService.backfillForMember`, each wrapped in an isolating try/catch.
- Frontend: onboarding modal for unnamed pockets; pockets grouped under parent wallet; "allocated" balance label.
- Feature note: [`docs/features/revolut-pockets.md`](../features/revolut-pockets.md).
