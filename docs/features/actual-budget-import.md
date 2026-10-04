# Feature: Actual Budget import

> Last updated: 2026-10-04

## Context

People moving from [Actual Budget](https://actualbudget.org) want their history in Picsou in one
pass, as Finary users already can ([issue #173](https://github.com/Cloeille/picsou-finance/issues/173)).
This imports an Actual export (accounts, transactions with payee and notes, categories) after a
preview where the user maps every source account and category. HomeBank, the other half of the
issue, is a separate importer.

## How it works

Two phases, the same shape as the Finary XLSX and CSV importers:

1. **Preview** (`POST /api/actual/import/preview`). The upload is recognised by signature: a
   zip (`PK\3\4`) must hold a root `db.sqlite`, otherwise the bytes must start with the SQLite
   header. The database is copied into a private temporary directory, opened read-only, read
   into `ParsedActualBudget`, and the directory is deleted. The parsed budget is cached under a
   member-bound token (30 minutes, one live preview per member). The response lists accounts
   with their balance, categories with their group, the newest 20 rows, and the member's
   existing accounts and categories for mapping. Each source account also carries
   `importedAccountId`, the Picsou account an earlier import created for it; the wizard
   pre-selects that account as the target (ahead of a same-name account) and says its balance
   follows the imported history.
2. **Execute** (`POST /api/actual/import`). Every mapping and every row is validated, then the
   token is consumed, then one transaction creates accounts, categories and transactions.
   Every target account an Actual import created (`externalAccountId` starting `actual_`) then
   gets its balance from its full ledger and rebuilt snapshots, whether this import maps it as
   `CREATE_NEW` or `MAP_EXISTING`. Accounts the user created keep their balance.

### Reading Actual's tables

The reader queries the raw tables, not the `v_*` views (the Actual client creates those at
runtime; an export may not carry them). What it relies on, from Actual's `loot-core` schema:

| Actual | Meaning | Picsou |
|--------|---------|--------|
| `transactions.amount` | signed integer, hundredths of the unit; outflow negative | `BigDecimal.valueOf(amount, 2)`, sign kept |
| `transactions.date` | integer `YYYYMMDD`, no time or zone | `LocalDate.of(y, m, d)` |
| `transactions.description` | the **payee id** (not text) | payee name via `payees` / `payee_mapping` |
| `transactions.notes` | free text | `description` (payee when empty); payee goes to `counterparty` |
| `tombstone = 1` | deleted row | skipped (accounts, categories, groups, transactions) |
| `isParent` / `isChild` + `parent_id` | split | children imported, parent skipped |
| `transferred_id`, `payees.transfer_acct` | transfer leg | `TRANSFER` category |
| `starting_balance_flag = 1` | opening balance | `TRANSFER` category |
| `category_mapping`, `payee_mapping` | merged categories / payees | followed to the surviving row |
| `preferences.defaultCurrencyCode` | budget currency (newer budgets only) | request currency must match |

A budget is single-currency. When the file records no currency, the wizard asks for it.

### Mapping

- **Accounts**: `CREATE_NEW` (manual account, `externalAccountId = actual_<id>`),
  `MAP_EXISTING` (the member's ledger account in the same currency), or `SKIP`.
- **Categories**: `CREATE_NEW` (under a parent created from the Actual group,
  slug `actual_group_<id>`; the category's slug is `actual_<id>`), `MAP_EXISTING` (an active
  category of the same income/expense kind), or `UNCATEGORIZED` (left for the categorizer).
- **Transfers and starting balances** go to the member's default `virement-interne` category,
  or to an `actual-transfer` category created once.

### Key files

- `backend/src/main/java/com/picsou/imports/actual/ActualBudgetFileParser.java` — signature
  check, bounded zip extraction, private temp directory and its cleanup.
- `backend/src/main/java/com/picsou/imports/actual/ActualBudgetDatabaseReader.java` — read-only
  SQLite access and Actual's visibility rules (tombstones, splits, transfers, merges).
- `backend/src/main/java/com/picsou/imports/actual/ParsedActualBudget.java` — the parsed shape.
- `backend/src/main/java/com/picsou/service/ActualBudgetImportService.java` — preview cache,
  mapping validation, dedup, persistence.
- `backend/src/main/java/com/picsou/controller/ActualBudgetImportController.java` — endpoints,
  member scope, `syncBuckets` throttle.
- `frontend/src/features/actual/{api,hooks,types}.ts` and
  `frontend/src/pages/sync/ActualBudgetTab.tsx` — the wizard on the sync page.

### Flow

```
upload .zip / db.sqlite ─► signature ─► extract db.sqlite (bounded) ─► temp dir (0700)
        ─► open read-only ─► ParsedActualBudget ─► delete temp dir ─► cache @token(member)
                                                                            │
user maps accounts / categories / currency ◄────────────────────────────────┘
        │
        ▼
execute ─► validate mappings + dedup ─► consume token ─► @Transactional:
           accounts ─► categories ─► transfer category ─► saveAll ─► balances + snapshots
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `org.xerial:sqlite-jdbc` (Apache-2.0, version from the Spring Boot BOM) | The export is a SQLite file; the driver bundles native builds for glibc and musl (both Docker images) | Hand-parsing the SQLite file format |
| Split children, not the parent | Children carry the categories, sum to the parent, and Actual's own balances use non-parent rows | Importing the parent (loses the category breakdown) |
| Both transfer legs imported, both `TRANSFER` | Each leg is a real movement of its own account; the kind keeps it out of income and spending | Importing one leg (the other account's balance would be wrong) |
| Starting balance as `TRANSFER` | Actual files it under an income category; counting it as income would inflate the first month | Keeping Actual's category |
| Dedup by `actual_<transaction id>` | Stable across exports; the `(account_id, external_id)` unique index already exists | Date/amount/payee fingerprint (collides on identical same-day rows) |
| Stored rows looked up by external id (member-scoped, `IN` batches of 1 000) | The user can move an imported row to another date; a date-range lookup missed it, re-inserted it, hit the unique index and rolled back every later re-import | Searching the file's date range |
| Reject a row already imported into another account | Moving or duplicating it would silently change two ledgers | Skipping it (the user's mapping is inconsistent and should be fixed) |
| Accounts an Actual import created follow their ledger on every import | Their balance only ever came from the imported rows, so a re-import that adds rows must update it, whatever the mapping mode | Recomputing only for `CREATE_NEW` (a re-import mapped onto the created account left a stale balance) |
| Accounts the user created keep their balance | It belongs to the user or another connector | Overwriting it with the Actual ledger sum |
| Self-contained preview cache | Keeps this importer independent of the shared preview store proposed alongside the HomeBank importer | Depending on an unmerged abstraction |

## Gotchas / Pitfalls

- **Zip safety.** At most 32 entries and 256 MiB inflated across the whole archive, counted
  while inflating (headers are not trusted). An entry name that is absolute, contains `..`,
  a backslash, a colon or a NUL rejects the whole archive. Entry names are never used as
  paths; the only file written is `budget.sqlite` in the temp directory.
- **Upload limit** is the app-wide 10 MB multipart limit. A large bare `db.sqlite` must be
  uploaded as the zip export, which compresses well.
- **Hostile databases.** The 256 MiB cap bounds the file on disk, not what reading it costs, so
  every read is bounded before it reaches the heap:
  - The connection is read-only with `query_only` and `trusted_schema=OFF`; queries time out
    after 60 s.
  - `SQLITE_LIMIT_LENGTH` is 1 MiB: SQLite itself refuses any larger value (`SQLITE_TOOBIG`,
    reported as "holds a value larger than 1 MiB") before the driver copies it out.
  - Every table read must be a plain table (`pragma_table_list`), and no column read may be
    generated (`pragma_table_xinfo`), since a view or a computed column can synthesise data the
    file never held.
  - Rows are counted before a table is read. Stored rows (deleted ones included): 10 000
    accounts, category groups and categories; 50 000 payees, `payee_mapping` and
    `category_mapping` rows; 300 000 transactions. A heavy decade-long budget holds tens of
    thousands of transactions, a few thousand payees and some hundred categories, so the caps
    leave an order of magnitude of headroom; the transaction cap leaves room for tombstones and
    split parents above the 200 000 live rows imported.
  - Text is cut in SQL with `substr` to the width Picsou stores (names and notes 255). Ids are
    read one character past their cap and rejected when longer (accounts 80, categories and
    groups 40, payees 80, transactions 200; they become external ids and slugs). A text value in
    a numeric column is cut to 33 characters.
  - Live accounts and categories are capped at 100 / 500 and imported rows at 200 000.
    Duplicate transaction ids, impossible dates and fractional amounts reject the file.
- **WAL mode.** Actual keeps its database in WAL mode. Opening the copy works because SQLite may
  create its `-wal`/`-shm` files in the temp directory, which is deleted afterwards.
- **Legacy splits** without `parent_id` encode the parent in the child id (`parent/child`).
- **A parent whose children were all deleted** is imported as a plain row.
- **Re-importing after deleting a created account** is refused (the account id is
  soft-deleted); restore the account or map the Actual account elsewhere.
- **Concurrent imports are not serialised by a lock.** A member holds one live preview (a new
  upload evicts the previous token) and a token is consumed atomically, so two imports only
  overlap if a second upload lands while the first execute is running. Rows into the same
  account then hit the `(account_id, external_id)` unique index and roll back; two
  `CREATE_NEW` accounts would not, which is the gap a member-level lock would close.

## Tests

- `ActualBudgetFileParserTest` — real SQLite and zip fixtures built by `ActualBudgetFixture`:
  live rows only, exact amounts and dates, splits, transfers, starting balance, merged payees and
  categories, WAL mode, missing currency, legacy split ids; rejections for non-Actual files,
  empty files, missing tables, invalid dates, fractional amounts, a value over 1 MiB, a table
  over its row cap, a generated column, a view in place of a table, an overlong id, zip without
  database, zip slip, inflation cap, entry count cap, non-SQLite entry, truncated zip; long
  notes cut to 255; temp cleanup.
- `ActualBudgetImportServiceTest` — preview summary and `importedAccountId`, exact persisted
  rows, neutral transfers, re-import idempotency, a re-import mapped onto the created account
  recomputing its balance and snapshots, a row moved to another date not re-imported, batched
  external-id lookups, cross-account refusal, skipped accounts, mapping onto existing accounts
  and categories, currency/kind/investment-account rejections, token scope and expiry.
- `features/actual/hooks.test.tsx` — a finished import invalidates accounts, categories,
  budget, dashboard, history and analysis.
- `ActualBudgetImportControllerTest` — 400 ProblemDetail, 201 result, request validation.
- `ActualBudgetImportWiringTest` — Spring picks the production constructors (the service also
  has a test-only one taking a `Clock`).
- `ActualBudgetTab.test.tsx` — preview, confirmation, request payload, currency choice,
  compatible targets, the previously imported account pre-selected over a same-name one,
  validation gating, error display, demo-mode guard.
- `e2e/sync.spec.ts` — the tab is listed and opens on its upload step.

## Links

- Sibling importers: [finary-import.md](finary-import.md) · [csv-transaction-import.md](csv-transaction-import.md)
- Categories and kinds: [budget.md](budget.md)
- Ticket: [issue #173](https://github.com/Cloeille/picsou-finance/issues/173)
