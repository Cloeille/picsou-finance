# Feature: GDPR-friendly data export (JSON + CSV)

> Last updated: 2026-07-20
> Status: ✅ Implemented

## Context

Picsou stores personal financial data: bank balances, holdings, transactions, savings goals, debts, on-chain wallet addresses, etc. To be GDPR-compliant (Art. 15 right of access, Art. 20 right to data portability), each user must be able to export *all data tied to their account* in a structured, commonly used, machine-readable format.

The export is **self-only**: a user exports their own data plus resources shared with them via `SharedResource`. It does not export data of other family members (admin-wide backups are out of scope).

## Goals

- Each authenticated user can download an export of their own data from `Settings → Security & Privacy`.
- Export is delivered as a single `picsou-export-<username>-YYYYMMDD-HHmmss.zip` (UTC timestamp; any username character outside `[A-Za-z0-9._-]` becomes `_` so the quoted header value stays valid) containing both a hierarchical `data.json` and a flat `csv/` directory (one file per entity).
- Export contains every domain field tied to the user, with explicit exclusion of secrets (passwords, MFA secrets, recovery codes, encrypted bank-session credentials, persistent-session tokens, requisition tokens).
- A toggle lets the user opt in to including their `BalanceSnapshot` history (off by default — keeps the ZIP small for the common case).
- The action is gated by re-authentication (TOTP if 2FA is enabled, otherwise password) and rate-limited to 5 exports/hour per user.
- Each export is logged server-side via SLF4J at `WARN` level (no dedicated audit table — same approach as MFA-sensitive ops).

## Non-goals (v1)

- Admin-wide / family-wide export (all members in one ZIP).
- Asynchronous job-based export (no `ExportJob` table, no on-disk staging, no email notification).
- Re-import (the export is one-way; restoring requires manual SQL or a future feature).
- PDF / human report export.
- Partial / filtered export (e.g. "only this account", "only this date range").
- Export of `PriceSnapshot` (market data, not user data, shared across users).
- Special handling for demo mode — demo data exports normally, since it is fake by design.

## How it works

### High-level flow

```
Settings → Security & Privacy
   │
   │  user clicks "Export my data"
   ▼
ExportDataDialog (frontend)
   ├─ checkbox "Include balance history"  (default OFF)
   ├─ password input  (if user.mfaEnabled === false)
   │  TOTP input      (if user.mfaEnabled === true)
   └─ submit
   │
   │  POST /api/me/export
   │     body: { reAuth: { password? | totpCode? }, includeBalanceSnapshots: boolean }
   ▼
MeExportController
   ├─ Bucket4j rate limit (5/h, keyed on userId)         → 429 if exceeded
   ├─ ReAuthService.verify(currentUser, body.reAuth)     → 401 if mismatch
   ├─ log.warn("export.requested userId={} username={} ip={} includeBalanceSnapshots={}")
   │    (username and ip through LogSanitizer.safe)
   └─ return ResponseEntity<StreamingResponseBody>
                 Content-Type: application/zip
                 Content-Disposition: attachment; filename="picsou-export-<username>-YYYYMMDD-HHmmss.zip"
   │
   ▼
DataExportService.streamExport(user, options, OutputStream out)
   │
   ├─ wraps `out` in a ZipOutputStream
   ├─ aggregates entries from each EntityExporter:
   │     ProfileExporter, FamilyMembersExporter,
   │     AccountsExporter, HoldingsExporter, TransactionsExporter,
   │     GoalsExporter (with contributors / manual contribs / month overrides),
   │     DebtsExporter, WalletAddressesExporter, SharedResourcesExporter,
   │     BankConnectionsMetadataExporter,
   │     BalanceSnapshotsExporter      (gated by options.includeBalanceSnapshots)
   ├─ writes data.json (hierarchical, assembled from all exporters)
   ├─ writes csv/<entity>.csv for each exporter
   ├─ writes README.txt explaining structure + exclusions
   └─ closes ZipOutputStream
```

### Data shape — `data.json`

Pretty-printed (2-space indent), camelCase, ISO-8601 UTC timestamps, decimal strings for money.

```json
{
  "exportedAt": "2026-04-26T14:32:11Z",
  "exportVersion": "1",
  "options": { "includeBalanceSnapshots": false },
  "user": {
    "id": "...", "username": "...", "displayName": "...",
    "role": "ADMIN", "createdAt": "..."
  },
  "familyMembers": [ /* FamilyMember rows */ ],
  "accounts": [
    {
      "id": "...", "name": "...", "type": "BANK",
      "balance": "1234.56", "currency": "EUR",
      "ownerMemberId": "...", "createdAt": "...",
      "realEstateMetadata": { /* if RE */ } | null,
      "loanDetails":        { /* if LOAN */ } | null,
      "holdings":           [ /* AccountHolding inline */ ],
      "transactions":       [ /* Transaction inline */ ],
      "balanceSnapshots":   [ /* if toggle on */ ]
    }
  ],
  "goals": [
    {
      "id": "...", "name": "...", "targetAmount": "10000.00",
      "deadlineDate": "2027-12-31",
      "contributors":         [ /* GoalContributor */ ],
      "manualContributions":  [ /* GoalManualContribution */ ],
      "monthOverrides":       [ /* GoalMonthOverride */ ]
    }
  ],
  "debts":            [ /* Debt */ ],
  "walletAddresses":  [ /* WalletAddress */ ],
  "sharedResources":  [ /* SharedResource */ ],
  "bankConnections":  [ { "provider": "BoursoBank", "connectedAt": "...", "lastSyncAt": "...", "status": "ACTIVE" } ]
}
```

### Data shape — `csv/` directory

RFC 4180: UTF-8 (no BOM), comma-separated, CRLF line endings, fields containing comma/quote/newline are double-quoted with internal quotes doubled.

| File                           | Source entity                          | Notable columns                                        |
| ------------------------------ | -------------------------------------- | ------------------------------------------------------ |
| `profile.csv`                  | `AppUser` (single row)                 | id, username, displayName, role, createdAt             |
| `family_members.csv`           | `FamilyMember`                         | id, displayName, role, sharingLevel, ...               |
| `accounts.csv`                 | `Account` (+ flattened RE / loan)      | id, name, type, balance, currency, ownerMemberId, ...  |
| `account_holdings.csv`         | `AccountHolding`                       | id, **accountId**, ticker, isin, quantity, costBasis, currency |
| `transactions.csv`             | `Transaction`                          | id, **accountId**, date, amount, currency, type, description, category |
| `goals.csv`                    | `Goal`                                 | id, name, targetAmount, deadlineDate, ...              |
| `goal_contributors.csv`        | `GoalContributor`                      | **goalId**, **memberId**, weight                       |
| `goal_manual_contributions.csv`| `GoalManualContribution`               | id, **goalId**, date, amount                           |
| `goal_month_overrides.csv`     | `GoalMonthOverride`                    | id, **goalId**, month, amount                          |
| `debts.csv`                    | `Debt`                                 | id, lender, principal, interestRate, ...               |
| `wallet_addresses.csv`         | `WalletAddress`                        | id, chain, address, label                              |
| `shared_resources.csv`         | `SharedResource`                       | id, resourceType, resourceId, sharedWithMemberId       |
| `bank_connections.csv`         | aggregator (Bourso / TR / Finary / EB) | provider, connectedAt, lastSyncAt, status              |
| `balance_snapshots.csv`        | `BalanceSnapshot` (only if toggle on)  | date, **accountId**, balance, currency                 |

### Bonus: `README.txt` at ZIP root

Explains the structure, format conventions, and the exclusion list — both for end-users and for auditors.

### Fields explicitly EXCLUDED (security-critical)

Never serialized in any form:

- `AppUser.passwordHash`
- `UserMfa.*` — TOTP secrets, recovery code hashes
- `UserMfaRecoveryCode.*`
- `BoursoSession.*` / `TradeRepublicSession.*` / `FinarySession.*` / `CryptoExchangeSession.*` — all encrypted credentials
- `Requisition.accessToken` / `refreshToken` / any encrypted Enable Banking blobs
- `PersistentSession.tokenHash`
- `SetupAudit`, `AppSetting` — instance-wide, not user-specific

`bank_connections.csv` shows only the *fact* that a provider was connected, with timestamps and status — never the secrets backing the connection.

## Multi-account family edge case

The export scope is **the data tied to the AppUser making the call**. If an admin is currently "managing" another member via `?memberId=X`, the export endpoint **ignores** that managed-profile context and always exports the AppUser's own perimeter (their `FamilyMember`, accounts they own, goals/debts/etc. they own, plus `SharedResource` rows where they are the recipient). This prevents a "switch profile then export" detour that would violate the self-only scope.

## Key files

Backend:

- `backend/src/main/java/com/picsou/controller/MeExportController.java` — REST endpoint at `/api/me/export`, re-auth gate, rate limit, audit log
- `backend/src/main/java/com/picsou/export/DataExportService.java` — orchestrator: opens ZIP, drives all exporters
- `backend/src/main/java/com/picsou/service/ReAuthService.java` — verifies password OR TOTP based on `user.mfaEnabled` (reusable for future sensitive operations)
- `backend/src/main/java/com/picsou/export/EntityExporter.java` — interface: `void writeJson(JsonGenerator g)` + `void writeCsv(Writer w)` + `String csvFileName()` + `String jsonKey()`
- `backend/src/main/java/com/picsou/export/{Profile,Accounts,Holdings,Transactions,Goals,Debts,WalletAddresses,SharedResources,BankConnections,BalanceSnapshots}Exporter.java` — one per entity root (family members are folded into `ProfileExporter`)
- `backend/src/main/java/com/picsou/dto/ExportRequest.java` — record `(ReAuthDto reAuth, boolean includeBalanceSnapshots)`
- `backend/src/main/java/com/picsou/dto/ReAuthDto.java` — record `(String password, String totpCode)` (XOR depending on user)
- `backend/src/main/java/com/picsou/config/RateLimitConfig.java` — extend with the export bucket (5/h per userId)

Frontend:

- `frontend/src/pages/settings/security/SecuritySection.tsx` — opens `ExportDataDialog` from the "Export my data" button (no separate section card)
- `frontend/src/pages/settings/security/ExportDataDialog.tsx` — modal with toggle + re-auth field + download trigger
- `frontend/src/features/export/api.ts` — `requestExport(opts)` returning a `Blob`
- `frontend/src/features/export/hooks.ts` — `useExportData()` mutation (TanStack Query) wrapping the fetch + blob download

## Technical choices

| Choice                                                | Why                                                                                               | Rejected alternative                                  |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| Synchronous `StreamingResponseBody`                   | Single-user self-hosted, bounded data, no proxy timeout in practice. Zero server state.           | Async job table + scheduler + on-disk staging + cleanup cron — massive infra for nil benefit |
| ZIP with both `data.json` + `csv/`                   | Covers both portability (JSON parsable) and human-readability (CSV opens in Excel) — Art. 20 spirit | JSON-only (Excel-unfriendly) or CSV-only (loses hierarchy) |
| `EntityExporter` interface, one class per root entity | Each exporter is small, testable in isolation, asserts no-secret-leak independently. New entity = new exporter, no reshuffling. | Single monolithic `DataExportService.exportEverything()` method |
| SLF4J WARN audit (no dedicated table)                 | Consistent with MFA feature decision (2026-04-26). Keeps schema lean. Logs are good enough for a self-hosted single-tenant app. | New `audit_log` table + Flyway migration              |
| Bucket4j keyed on `userId`                            | A user stays limited even if their IP changes. Existing pattern (`RateLimitConfig`).              | IP-based — easily bypassed by a logged-in user        |
| Re-auth required (TOTP > password)                    | Defense against stolen session cookie. RGPD export = high-impact action.                          | Trust the JWT alone — too risky for full data dump    |
| `BalanceSnapshot` toggle, off by default              | Keeps the common-case ZIP small (typical user: tens of MB → KB). Power users opt in.              | Always include → bloats the export                    |
| Secrets exclusion test asserted by raw byte-grep      | Cheap, catches accidental leak via any new field added to a JPA entity later                      | Field-by-field manual assertion — drifts as schema evolves |

## Gotchas / Pitfalls

- **Headers already sent.** If a DB error occurs **after** Spring has flushed the response headers (i.e. mid-stream), we cannot return a JSON 500. Strategy: write a `__EXPORT_FAILED__.txt` entry into the partial ZIP, log SLF4J error, close the stream. The user gets a partial ZIP with a clear failure note.
- **Client disconnect mid-stream** must be caught (`ClientAbortException`) and not propagated as an error — log debug, close the `ZipOutputStream` quietly.
- **No `BeanSerializer` shortcut.** Do **not** serialize `AppUser` directly with Jackson — that risks leaking `passwordHash` or future secret fields. Each exporter explicitly enumerates the fields it writes.
- **Currency separation.** Money is always written as `(decimal-string, currency-code)` — never as a localized string like `"1 234,56 €"` and never as a `Double`.
- **`SharedResource` direction.** Only export rows where the AppUser's member is the **recipient** (data shared *with* me); rows where they share *outwards* are also relevant since they describe how my data is shared — both directions are exported, but always anchored on "my member id" so we never include third-party data we shouldn't.
- **Managed-profile detour.** The export endpoint ignores any `?memberId=` query/cookie context — always anchors on the authenticated `AppUser`.
- **`BalanceSnapshotsExporter` streams per account.** `writeCsv`/`writeJson` each iterate the member's accounts and, per account, query `balanceSnapshotRepository.findByAccountIdOrderByDateAsc` — instead of collecting the member's *entire* snapshot history into one list before writing anything. This is the table the class's own Javadoc calls out as able to "dwarf the rest of the archive" (years × many accounts), so heap must stay bounded to one account's history at a time rather than growing with total history size. Cost: each of the 2 passes (CSV, JSON) re-runs the same per-account queries — 2 passes total, unchanged from before. Accepted: the goal is bounded heap, not minimal query count. Row order (account insertion order, then date within account) is unchanged.

## Tests

Backend:

- `*ExporterTest` (one per `EntityExporter`) — fixtures → expected JSON node + CSV rows. Each includes a *negative* assertion: the produced bytes do not contain known-secret tokens.
- `DataExportServiceTest` — verifies ZIP file list given options, presence/absence of `balance_snapshots.csv` based on toggle, presence of `README.txt`. Wires **all** `EntityExporter` beans (matching production Spring injection, not a subset) with one fixture per entity carrying a unique tripwire literal in every sensitive, non-exported field (e.g. `Requisition.authLink`); asserts none of the tripwires appear anywhere in the archive bytes. **New exporter ⇒ new wiring + new tripwire(s) in this test** — the net only protects what it exercises, and a partial exporter list (as this test shipped with for a while) silently blinds it to whichever exporters are missing.
- `BalanceSnapshotsExporterTest` — drives the exporter directly (2 accounts × 3 snapshots): asserts CSV/JSON row order (account, then date) and, via `Mockito.verify`, exactly one `balanceSnapshotRepository.findByAccountIdOrderByDateAsc` call per account per pass (2 passes) — never a whole-member collecting call.
- `MeExportControllerTest` (`@WebMvcTest`) — happy path, re-auth fail (password), re-auth fail (TOTP), missing body, rate-limit exceeded.
- `DataExportIntegrationTest` (`@SpringBootTest` + H2) — seed an `AppUser` with **all** entity types populated **and** every secret-bearing entity (MFA secret, recovery codes, BoursoSession ciphertext, requisition tokens, persistent session). Hit the endpoint, parse the ZIP in memory, assert:
  - all expected files present
  - row counts match seeded entity counts
  - **raw byte grep**: no occurrence of the seeded `passwordHash`, MFA secret bytes, requisition token bytes, BoursoSession ciphertext bytes, persistent-session token hash bytes
  - This is the principal GDPR safety net.

Frontend:

- `ExportDataDialog.test.tsx` — renders TOTP field if `user.mfaEnabled`, else password; submit calls API with right payload; shows loading state; surfaces 401/429 via `extractErrorMessage`.
- `useExportData.test.ts` — mutation triggers blob download via `<a download>` (DOM stub).

## Documentation

- This file (`docs/features/data-export.md`).
- Update `docs/INDEX.md` to add the entry under "Feature notes".
- No ADR — synchronous streaming + per-entity exporter is a routine pattern, no new architectural decision worth recording.

## Links

- Related: [`docs/features/encryption-at-rest.md`](./encryption-at-rest.md) — the data we exclude is what `encryption-at-rest` protects.
- Related: [`docs/features/mfa-and-remember-me.md`](./mfa-and-remember-me.md) — re-auth pattern reused.
- Related: [`docs/features/multi-account-family.md`](./multi-account-family.md) — sharing model that defines the self-only scope.
