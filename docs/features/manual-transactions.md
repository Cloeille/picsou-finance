# Feature: Manual Transactions

> Last updated: 2026-09-02

## Context

Picsou syncs transactions from external sources such as Finary, bank connectors, Trade Republic,
and crypto exchanges. Users can also record transactions manually, including on synced accounts.
The local transaction ledger derives balances and holdings only for manual accounts.

## How it works

### DB schema (V24 migration)

Five new columns on the `transaction` table (all nullable or defaulted, backward-compatible):

```sql
ALTER TABLE transaction
  ADD COLUMN is_manual      BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN tx_type        VARCHAR(20)   NULL,  -- DEPOSIT | WITHDRAWAL | BUY | SELL | DIVIDEND | FEE
  ADD COLUMN ticker         VARCHAR(30)   NULL,
  ADD COLUMN quantity       NUMERIC(20,8) NULL,
  ADD COLUMN price_per_unit NUMERIC(20,8) NULL;

CREATE INDEX idx_transaction_account_manual ON transaction(account_id, is_manual);
```

Existing synced transactions have `is_manual = false`. The original `type` column (Finary raw category string) is untouched.

### Per-trade fees column (V53 migration)

```sql
ALTER TABLE transaction ADD COLUMN fees NUMERIC(20,8) NULL;  -- null = zero downstream
```

Optional broker/transaction fees on a BUY/SELL. Fees **fold into the PMP cost basis** (French PEA
convention: acquisition costs raise the cost basis) — see *Holdings derivation* below. The signed
`amount` also accounts for fees: BUY `= −(qty·price + fees)`, SELL `= +(qty·price − fees)`,
centralised in `TransactionAmountCalculator` and mirrored by the frontend modal. Added for the CSV
importer ([csv-transaction-import.md](csv-transaction-import.md)) and exposed as an optional field on
the manual add/edit form.

### Security name column (V36 migration)

A later migration adds a first-class label for derived positions:

```sql
ALTER TABLE transaction
  ADD COLUMN name VARCHAR(100) NULL;
```

`transaction.name` is the human-readable security name, distinct from the row `description`. It is auto-filled from OpenFIGI when an ISIN is entered, or from the user-supplied "Nom". `HoldingComputeService` uses it to label each position (see below). The width matches `account_holding.name` so the value copies across without truncation.

### Transaction types

`TransactionType` enum: `DEPOSIT`, `WITHDRAWAL`, `BUY`, `SELL`, `DIVIDEND`, `FEE`.

- Cash accounts (CHECKING, SAVINGS, LEP, OTHER): use DEPOSIT / WITHDRAWAL. The `amount` field is signed (positive = deposit, negative = withdrawal).
- Investment accounts (PEA, COMPTE_TITRES, CRYPTO): use BUY / SELL. The `amount` is signed (negative for BUY, positive for SELL, reflecting cash flow).

### Entering a position by ISIN

On an investment account, the instrument field accepts **either** a Yahoo ticker (e.g. `IWDA.AS`) **or** a 12-character ISIN (e.g. `IE00B4L5Y983`). `ManualTransactionService.applyInstrumentFields()` detects an ISIN via `OpenFigiIsinConverter.isIsin()` and, when matched, calls `resolve()` to convert it to a Yahoo ticker + display name. Resolution happens at **write time**, so:

- an ISIN entry and the equivalent ticker entry merge into a **single position** (the grouping key is the resolved ticker, not the raw input — this is what kills duplicate positions);
- Yahoo pricing works (`YahooFinancePriceProvider` rejects raw ISINs);
- the resolver persists the resolved name or canonical ticker as a language-neutral description.
  A successfully resolved ISIN is replaced by the Yahoo ticker and never appears in the row.
  `TransactionResponse.txType` lets the frontend add the localized transaction label (see Gotchas).

A user-supplied "Nom" always wins over the resolved name. The same logic runs for both add and edit (`addTransaction` and `updateTransaction` share `applyInstrumentFields`).

### Balance derivation (cash accounts)

When a manual transaction is added, edited, or deleted on a **manual** cash account (`account.isManual = true`), `ManualTransactionService` recomputes `account.currentBalance` as the sum of all transaction amounts via a single aggregate query (`sumAmountByAccountId`). It then calls `FinaryPersistenceHelper.reconstructSnapshotsFromDb()` to rebuild the balance history from scratch.

### Synced accounts

Manual transactions on a **synced** account (`account.isManual = false`) are recorded as
annotations. They never drive the account's balance, snapshot history, or investment positions.
This applies to accounts synced through Enable Banking, Trade Republic, wallets, and exchanges.
Provider sync owns their state, so a local entry cannot overwrite provider-written holdings,
balances, or snapshots. Finary-created accounts have `isManual = true`, so they keep the
transaction-derived path. The MCP `add_transaction` tool goes through the same service and
inherits the rule.

### Holdings derivation (investment accounts)

`HoldingComputeService.recomputeHoldings(account)` is called after every manual transaction add, edit, or delete on a **manual** PEA, COMPTE_TITRES, or CRYPTO account. Synced investment accounts preserve provider-owned positions when manual transaction annotations change:

1. Fetches all BUY/SELL transactions for the account, ordered by date ASC.
2. Groups by ticker: `net quantity = Σ(BUY qty) − Σ(SELL qty)`.
3. Computes `averageBuyIn` as VWAP across all BUY transactions for each ticker, **fees included**: `Σ(qty·price + fees) / Σ(qty)` (null fees treated as zero). SELL rows never affect the average.
4. Upserts `AccountHolding` for tickers where `qty > 0`.
5. Deletes `AccountHolding` for tickers where `qty ≤ 0`.
6. Labels each surviving position with the **most recent** transaction (date ASC, last write wins) that carries a non-blank `name`. The update is guarded by a null check, so a nameless manual transaction never erases a name already set by bank sync (OpenFIGI).

All existing holdings are loaded upfront (one query) to avoid N+1 lookups.

### Manual transactions survive re-syncs

All sync services (`FinaryPersistenceHelper`, `BoursoSyncService`) now call `transactionRepository.deleteByAccountIdAndIsManualFalse(accountId)` instead of `deleteByAccountId(accountId)`. Manual transactions are preserved across any sync.

### REST endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/accounts/{id}/transactions` | Add a manual transaction (returns 201) |
| `PUT` | `/api/accounts/{id}/transactions/{txId}` | Edit a manual transaction |
| `DELETE` | `/api/accounts/{id}/transactions/{txId}` | Delete a manual transaction (returns 204) |

`DELETE` validates that the transaction is manual (`isManual = true`). Synced transactions cannot be deleted via this endpoint.

### Category editing on synced transactions

Bank-synced transactions (`isManual = false`) are read-only for amount, date, and counterparty, but their **category can be changed inline**:

- The category chip in the transaction list opens the existing category picker (Dialog on desktop,
  bottom-sheet on mobile — the same component used by the manual-transaction edit flow).
- On confirm, the change is persisted via the existing `PUT /api/accounts/{id}/transactions/{txId}/category`
  endpoint, which stamps `transaction.category_manual = true` on the row.
- Because `category_manual = true`, the categorization engine will never overwrite this choice
  during re-sync or retro-apply (see `docs/features/budget-rules.md`).
- The full edit modal (amount, date, description) remains gated to manual-only transactions.

### Frontend

`AddTransactionModal` is account-type-aware:

**Cash accounts (CHECKING, SAVINGS, LEP, OTHER):**
- Date, DEPOSIT/WITHDRAWAL toggle, Description, Amount (always positive — toggle sets sign), Category (optional dropdown — pre-filled from existing category when editing)

**Investment accounts (PEA, COMPTE_TITRES, CRYPTO):**
- Date, BUY/SELL toggle, **Ticker ou ISIN** (a ticker like `IWDA.AS` or a 12-char ISIN like `IE00B4L5Y983`), Name (auto-filled from existing holdings when the ticker matches; otherwise resolved from the ISIN backend-side), Quantity, Price per unit, **Fees (optional, folded into the PMP)**, Total (read-only)

The Transactions list groups rows by booking date. Headings stay compact for a single-year list;
when the visible list spans multiple calendar years, every heading includes its year. It shows a
"Manuel" badge on manual entries and a delete button (only for manual entries). For manual
instrument rows without a name, it combines the `txType` enum with the canonical ticker through
frontend i18n. Provider descriptions on synced transactions remain unchanged. Tapping a row opens
a detail sheet/dialog; for synced transactions the **category chip there is tappable** and opens
the category picker, while all other fields remain display-only.

After submit, `useAddTransaction` / `useDeleteTransaction` hooks invalidate the `transactions`, `history`, `account`, and `dashboard` queries.

### Key files

| File | Role |
|------|------|
| `backend/src/main/resources/db/migration/V24__manual_transactions.sql` | Schema extension (is_manual, tx_type, ticker, quantity, price_per_unit) |
| `backend/src/main/resources/db/migration/V36__transaction_security_name.sql` | Adds `transaction.name` (position label) |
| `backend/src/main/java/com/picsou/model/TransactionType.java` | Enum (DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND, FEE) |
| `backend/src/main/java/com/picsou/service/HoldingComputeService.java` | Derives holdings (qty, VWAP, **name**) from BUY/SELL transactions |
| `backend/src/main/java/com/picsou/service/ManualTransactionService.java` | Orchestrates add/edit/delete + re-derivation; persists `fees`; delegates ISIN/ticker/description to `InstrumentFieldResolver` |
| `backend/src/main/java/com/picsou/service/InstrumentFieldResolver.java` | Shared ISIN→ticker/name resolver with language-neutral persisted descriptions (reused by the CSV importer) |
| `backend/src/main/java/com/picsou/imports/TransactionAmountCalculator.java` | Single source of truth for the signed `amount` incl. fees |
| `backend/src/main/resources/db/migration/V53__transaction_fees.sql` | Adds `transaction.fees` (folds into the PMP) |
| `backend/src/main/java/com/picsou/adapter/OpenFigiIsinConverter.java` | `isIsin()` detection + `resolve()` ISIN→ticker+name (shared with bank sync) |
| `backend/src/main/java/com/picsou/controller/AccountController.java` | POST/DELETE `/accounts/{id}/transactions` |
| `backend/src/main/java/com/picsou/repository/TransactionRepository.java` | `deleteByAccountIdAndIsManualFalse`, `sumAmountByAccountId`, `findByAccountIdAndTxTypeInOrderByDateAsc` |
| `frontend/src/components/shared/AddTransactionModal.tsx` | Account-type-aware form modal |
| `frontend/src/components/shared/TransactionsList.tsx` | Localized transaction-type fallbacks, date grouping with unambiguous historical years, manual badge, and delete button |
| `frontend/src/features/accounts/hooks.ts` | `useAddTransaction`, `useDeleteTransaction` |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Derive balance from transactions | Keeps single source of truth; avoids divergence between manual entries and computed balance | Letting users set balance directly (leads to inconsistency with transactions) |
| `is_manual` flag on transaction | Minimal schema change; syncs can cleanly skip manual rows | Separate `manual_transaction` table (more joins, more complex) |
| Delete-synced-rows-except-manual on re-sync | Manual data survives any number of re-syncs | Timestamped merge (more complex, edge cases with overlapping date ranges) |
| VWAP for averageBuyIn | Standard financial convention for cost basis | FIFO (more complex, requires ordered lot tracking) |

## Gotchas / Pitfalls

- **Investment account balance is NOT recomputed** from manual transactions. Only the holdings (positions) are derived. The account's `currentBalance` is set by the price scheduler (qty × live price). This is intentional for investment accounts.
- **Synced transactions cannot be deleted**: The DELETE endpoint checks `isManual`. Attempting to delete a synced transaction returns 403.
- **Category is the only editable field on synced transactions.** Amount, date, counterparty, and description are read-only for bank-synced rows. Only the category can be changed via the inline chip picker. Stamping `category_manual = true` makes the choice durable across re-syncs and retro-apply runs.
- **Holdings recomputation is full**: Every add/delete triggers a full re-derivation for that account (all tickers). This is fast in practice since investment accounts rarely have hundreds of tickers.
- **The backend never localizes ticker-based descriptions.** `ManualTransactionService` stores
  the effective name, or the canonical ticker when no name exists. The API already exposes
  `txType`; `TransactionsList` translates that enum for manual ticker rows with no name.
  Cash transactions with no ticker and synced provider descriptions keep their supplied text.

## Tests

- `HoldingComputeServiceTest` — 11 unit tests: BUY-only, multi-BUY VWAP, BUY+SELL, fully-sold position, null ticker/quantity skipping, multiple tickers, existing holding update, plus position name = newest transaction's name and name-preserved-when-transactions-have-none.
- `ManualTransactionServiceTest` — 11 unit tests: manual cash add (balance + snapshots recomputed), synced cash add (transaction saved, balance/snapshots untouched), investment add (holdings recomputed, for both manual and synced accounts), non-owned account rejection, manual delete, synced-account delete (no reconstruct), synced-transaction delete rejection, not-found rejection, plus ISIN input → resolved ticker/name/description and plain-ticker uppercased with the user "Nom" winning.
- `TransactionsList.test.tsx` — localized BUY, SELL, DIVIDEND, and FEE fallbacks, provider-description preservation, localized search, and date-heading behavior.
- `OpenFigiIsinConverterTest` — 4 unit tests for the `isIsin()` detector: valid ISINs, case/whitespace normalization, rejects tickers/non-ISIN strings, rejects null/blank.
