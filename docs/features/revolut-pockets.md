# Feature: Revolut pockets in the budget

> **Removed (2026-07-14):** superseded by the Revolut sidecar connector, which syncs real
> pockets — see [`revolut-sidecar.md`](./revolut-sidecar.md) and
> [the superseding ADR note](../decisions/2026-06-28-revolut-pockets-reconstruction.md). This
> document describes a feature no longer present in the codebase; kept for historical context.

> Last updated: 2026-06-28
> Status: **Implemented** (shipped 2026-06-28).

## Context

Revolut "pockets" (sub-wallets / spaces the user moves money into) are **not exposed as separate accounts** by Enable Banking (PSD2). Only the main Revolut wallet is synced. As a result, money moved into a pocket appears on the main account as an opaque internal-transfer row such as `To EUR MB:76fe0dd0-c245-4d73-9df4-d4fcda89abfe` ("MB" = Revolut-internal money-beneficiary id; the UUID is a stable per-pocket identifier).

Two concrete problems follow:

1. **Cashflow pollution.** These transfer rows are never detected as transfers, stay uncategorized, and are therefore counted as real expenses (or income) in the cashflow/Sankey views.
2. **Pockets are invisible as accounts.** The user wants each pocket to show up as a (current-type) sub-account grouped under the Revolut wallet, so she can see where her money is allocated.

The user spends *directly from* the pockets. That spending lives only inside the pocket and is **structurally unavailable via Enable Banking** — PSD2 returns neither the pocket account nor its internal transactions. So this feature reconstructs what the synced data *does* contain (the inflows), and is explicitly designed to remain compatible with a later CSV-based enrichment that would add the real internal spending.

## Scope

**In scope (this feature, "approach B"):**
- Detect the `To <ccy> MB:<uuid>` internal-transfer pattern on the Revolut wallet.
- Reconstruct each pocket as a current-type sub-account linked to the parent Revolut wallet.
- Reclassify the main-account transfer row as `virement-interne` (removes cashflow pollution) and mirror a credit leg into the pocket sub-account (conserves net worth, gives the pocket a balance).
- An onboarding flow to **name** each detected pocket (manual, assisted) with an optional one-shot CSV bootstrap to pre-fill names.

**Out of scope (deferred):**
- **Real internal pocket spending** (would require a Revolut CSV/PDF statement import — "approach A"). The design stays compatible with adding this later.
- **PDF statement parsing** (CSV first; PDF is materially harder).
- **Other internal-transfer patterns** seen in the data but not pocket-specific: `To Robo portfolio`, `To investment portfolio by income sorter`, `Exchanged to EUR` / `To EUR` (FX), and transfers to the user's own external accounts. The user keeps categorizing these manually for now.

## How it works

### Data model (Flyway **V43**)

- New column `account.parent_account_id` — nullable FK → `account.id`. A pocket points to its parent Revolut wallet. `NULL` for normal accounts.
- Index on `account(parent_account_id)` for efficient child lookups.
- A **pocket is a regular `Account` row**, reusing existing infrastructure:
  - `type = CHECKING` (the user wants pockets to behave like current accounts; `CHECKING` maps to `AssetClass.CURRENT`, so pocket inflows are **not** miscounted as savings contributions by `AllocationService`).
  - `provider = 'Revolut'`, `external_account_id = <pocket uuid>`, `parent_account_id = <wallet id>`, `is_manual = false`.
  - `name` = user-given pocket name; until named, a placeholder like `Pocket ••89abfe` (last 6 of the uuid).
- `AccountResponse` now includes `parentAccountId` (nullable Long).

No separate mapping table: the uuid lives in `external_account_id`, the human name in `name`.

### Detection & reconstruction (`RevolutPocketService`)

`RevolutPocketService` plugs into the ingest path after Enable Banking transactions are upserted, plus a one-time **backfill** over existing Revolut-wallet transactions.

Detection regex (case-insensitive):
```
^to\s+[a-z]{3}\s+mb:(?<uuid>[0-9a-f-]{8,})$
```

For each matching transaction on a Revolut wallet:

1. **Find or create** the pocket sub-account keyed by `uuid` (placeholder name on first sight). The sub-account is created **immediately**, so the anti-pollution effect does not wait for naming.
2. **Reclassify** the main-wallet row to category `virement-interne`, resolved by **slug** via `CategorizationService.categoriesBySlug().get("virement-interne")` — never by a hardcoded id, since category ids are per-member Postgres `IDENTITY` values. Reclassification is an **unconditional override** (detection always wins, even over a prior manual category, because the row is genuinely a transfer).
3. **Mirror** the inflow: create a `+amount` transaction in the pocket sub-account, also `virement-interne`. The two legs net to zero in net worth; the pocket balance becomes the running sum of its inflows.

Auto-categorization is protected by the existing guard in `CategorizationService.autoCategorize()`: `if (tx.getCategoryRef() != null) return false`. Once a pocket leg carries `categoryRef = virement-interne`, keyword rules can never overwrite it — no extra column needed.

Reconstruction is **idempotent**: the mirror leg uses a deterministic `external_id` = `pocket-mirror:<source-external-id>`, so re-running the backfill or re-syncing never double-creates legs.

```
Enable Banking (main wallet only)
        │  transactions upserted
        ▼
SyncService.ingest ──► RevolutPocketService.process()
        │                     │
        │                     ├─ find/create pocket sub-account (parent = wallet)
        │                     ├─ reclassify main row → virement-interne (TRANSFER)
        │                     └─ mirror credit leg into pocket sub-account
        ▼
CashflowService / AllocationService  (transfer rows excluded; pocket = CURRENT)
```

### Sync wiring

`backfillForMember(memberId)` is wired into `SyncService` at **four points**, always after `detectRecurring`, inside an isolating try/catch (backfill failure never blocks the main sync):

- `completeConnection` — on new bank connection
- `retrySync` — on manual retry
- `resyncAll` — on full resync
- `resyncLatest` — on latest-only resync

The backfill is a fast no-op when the member has no Revolut wallet.

### API endpoints

| Method | Path | Response |
|--------|------|----------|
| `GET` | `/api/revolut-pockets/unnamed` | `UnnamedPocketResponse[]` |
| `POST` | `/api/revolut-pockets/csv-naming` (multipart) | `{ suggestions: [{accountId, suggestedName, uncertain}] }` |

**`UnnamedPocketResponse`** fields: `accountId`, `placeholderName`, `parentAccountId`, `transfers: [{date, amount}]`.

Renaming a pocket reuses the existing `PUT /accounts/{id}` endpoint — no dedicated rename route.

### Pocket identification (frontend UX)

There is **no automatic bridge** between the PSD2 uuid and the pocket's real name (the uuid is Revolut-internal and never shown in the app). Identification is therefore user-driven, via a polished onboarding modal shown when unnamed pockets exist:

- **Manual naming (primary).** For each detected pocket, show its transfers (amounts + dates) to help the user recognize it, then a single text field to name it. Naming renames the sub-account; future transfers with the same uuid attach automatically.
- **CSV bootstrap (optional, included in v1).** The user can drop one Revolut export; we reconcile pocket names to uuids by matching transfer **amount + date**, pre-filling the name fields — flagging uncertain matches. CSV pre-fills are suggestions and are never auto-applied. (Importing actual internal spending is the deferred "approach A".)

Pockets are **grouped under their parent Revolut wallet** client-side. Pocket balance is labeled **"allocated" / "alloué"** with a tooltip explaining it is total transferred-in, not the real balance (internal spending is not synced via PSD2).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Pocket = `Account` with `parent_account_id` | Reuses accounts list, balances, net worth, transactions UI for free | New dedicated `pocket` table + bespoke UI |
| `type = CHECKING` for pockets | User wants current-account behavior; `CURRENT` class avoids false savings-contribution counting | `SAVINGS` (would inflate allocation/savings metrics) |
| Reconstruct inflows from the main wallet's `To … MB` debits | Only inflows exist in PSD2; mirrors the user's "reconstruct from entry history" intent | Wait for an API that exposes pockets (none exists) |
| Create sub-account immediately with placeholder | Pollution fix is instant, independent of naming | Stage detections until named (delays the fix) |
| Reclassify to existing `virement-interne` (resolved by slug) | Cashflow already excludes `TRANSFER` rows | New transfer flag column on `transaction` |
| Detection by description regex (uuid pattern) | Unambiguous self-transfer marker; zero config | Paired-leg matching across accounts (no opposite leg exists here) |

## Gotchas / Pitfalls

- **Pocket balance = inflows only.** Because internal spending is invisible via PSD2, a pocket's reconstructed balance reflects total money *moved in*, not the real current balance. The UI labels this "allocated" / "alloué" with an explanatory tooltip — it must not be presented as an authoritative balance. Adding "approach A" (CSV spending import) is what makes the balance accurate.
- **Expense totals drop after rollout.** Transfers previously counted as expenses (including rows the user had manually tagged, e.g. `To EUR MB:3874abbf…` tagged *Factures*) become `virement-interne` and leave the cashflow. This is intended (de-pollution) but is a visible change to historical numbers — backfill should be communicated to the user.
- **Idempotency is mandatory.** The 90-day lookback re-ingests recent transactions; the mirror leg must be keyed deterministically off the source row so re-sync/backfill never duplicates.
- **Detection overrides any prior category.** If the user had manually set a category on a `To … MB` row, detection overrides it to `virement-interne`. This is intentional (the row is genuinely a transfer) and is explicitly documented, not silent. The existing `autoCategorize()` guard (`if (tx.getCategoryRef() != null) return false`) then protects the reclassified leg from later being overwritten by learned keyword rules.
- **Currency.** Observed rows are EUR (`To EUR MB:`); the regex tolerates any 3-letter currency, but multi-currency pockets are not otherwise special-cased in v1.
- **CSV match is heuristic.** Amount + date matching against a Revolut export is good enough for naming but can produce false positives when two transfers share the same amount and date. Uncertain suggestions are flagged; the user always confirms.
- **`Array.isArray()` guard is mandatory on the `/history` response.** `AccountsPage` (PnL memos) and `NetWorthChart.filterByRange` consume the aggregated history response. After a pocket rename, the mutation invalidates `['accounts']` and `/history`; during the refetch window TanStack Query can briefly return a non-array (e.g. `{}`). A `?? []` fallback is **not** sufficient — `{}` is truthy and silently passes the guard. Use `Array.isArray(value) ? value : []`. Hardened in `frontend/src/pages/accounts/AccountsPage.tsx` (pnl/chartPnlData memos) and `frontend/src/components/shared/NetWorthChart.tsx` (filterByRange).
- **Demo-mode rename must return a new array reference.** The demo `PUT /accounts/{id}` handler must reassign the mock accounts array via `.map(…)` rather than mutating in place. TanStack Query's `replaceEqualDeep` short-circuits re-render when the reference is unchanged, so an in-place mutation leaves the renamed pocket stale in the UI. Separately, the demo layer was missing an aggregated `GET /history` handler entirely (a pre-existing gap the feature surfaced); without it, `/accounts` and the dashboard crashed in demo mode. Both fixed in `frontend/src/demo/index.ts`.
- **Smoke-tested in demo mode (2026-06-28).** Validated: pockets grouped under the Revolut wallet, "allocated" balance + tooltip displayed correctly, onboarding modal shows transfers + CSV drop, rename updates the UI live, zero console errors.

## Tests

- `RevolutPocketServiceTest` — regex matching (positive/negative cases incl. the real `To EUR MB:<uuid>` strings and near-misses like `To EUR` / `To Robo portfolio`), uuid extraction, idempotent backfill (no duplicate legs on re-run), placeholder naming.
- `SyncServicePocketTest` / `SyncServiceRevolutBackfillTest` — a `To … MB` row produces: reclassified main leg (`virement-interne`), one pocket sub-account with correct `parent_account_id`, one mirror credit leg; backfill is wired at all four sync points.
- `CashflowServiceTest` (extension) — reclassified rows are excluded from spending/income; mirror legs do not appear as expenses.
- `AllocationServiceTest` (extension) — pocket inflows (CURRENT class) are not counted as savings/investment contributions.

All 600 backend tests green; `bun run build` green.

## Links

- ADR: [`docs/decisions/2026-06-28-revolut-pockets-reconstruction.md`](../decisions/2026-06-28-revolut-pockets-reconstruction.md)
- Related: `docs/features/bank-sync.md`, `docs/decisions/2026-06-02-budget-cycle-and-categorization.md`.
- Future enrichment ("approach A"): Revolut CSV/PDF statement import to recover real internal pocket spending.
