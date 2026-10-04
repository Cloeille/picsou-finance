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
   pre-selects that account as the target and says its balance follows the imported history.
   Otherwise it pre-selects a same-name account, but never one listed in `actualAccountIds`
   (accounts any Actual import created): mapped from another source, it would not follow the
   file, and the old wizard pre-selected budget 1's account for budget 2's same-name account.
2. **Plan** (`POST /api/actual/import/plan`). A dry run of execute with the same request: it
   runs every validation and returns how many rows the import would add, delete and move, plus
   the warnings below and `largeDeletion`. It writes nothing and does not consume the token.
   The wizard calls it when the user clicks Import and shows the counts in the confirmation
   dialog. On a large deletion the dialog leads with a warning, turns destructive, and enables
   Confirm only once the user types the deletion count.
3. **Execute** (`POST /api/actual/import`). Every mapping and every row is validated, a large
   deletion without `acknowledgeLargeDeletion` is refused, then the
   token is consumed, then one transaction creates accounts and categories, deletes and moves
   rows per the re-import rules, and adds the new rows. Every account an Actual import created
   (`externalAccountId` starting `actual_`) that this import maps, or that a moved row left,
   then gets its balance from its full ledger and rebuilt snapshots, whether it is mapped as
   `CREATE_NEW` or `MAP_EXISTING`. Accounts the user created keep their balance.

### Re-import rules

A re-import synchronises with the file only the accounts it owns. A stored row is matched to
the file by its `actual_<transaction id>`, never by date.

A target is **synchronised** when its `externalAccountId` is `actual_<S>` and this request maps
source account `S` of this file onto it (`CREATE_NEW` reuses it, `MAP_EXISTING` may pick it).
An account `CREATE_NEW` is about to create is synchronised by construction. Every other target
is **append-only**: an account the user created, and also an `actual_*` account created for a
source that is not in this file (another budget) or mapped from a different source.

| Stored row | Account kind | Outcome |
|------------|--------------|---------|
| Same Actual id, same target account | any | unchanged (counted as skipped) |
| Actual id no longer emitted (deleted in Actual, or a parent since split into children) | synchronised target | **deleted** |
| | append-only target | kept, warning `KEPT_MISSING` |
| Actual id now in another Actual account | both synchronised (or the new one being created) | **moved** (`account_id` updated) |
| | otherwise, including a row on an account this request does not target | kept where it is, warning `KEPT_MOVED` |
| Row still on `actual_<S>`, its Actual account `S` now mapped elsewhere | | **refused** ("The Actual account 'X' was imported into 'Y' before; map it to that account to update it") |

Only accounts this import maps are scanned for deletions; a skipped account is never touched.
Warnings are `{reason, count}` in both the plan and the result.

**Large deletions.** A plan deleting more than 200 rows, or more than 20 % of a synchronised
account's `actual_` rows, sets `largeDeletion`. Execute refuses it unless the request carries
`acknowledgeLargeDeletion: true`, which the wizard sends only after the typed confirmation. It
is a safety net against a wrong mapping, independent of the ownership rule above.

**Soft-deleted accounts.** `Account`'s `@SQLRestriction("deleted_at IS NULL")` is folded into
the `JOIN` of both stored-row lookups (`t.account.member.id`, `t.account.id`), so a deleted
account's rows are invisible to a re-import: they neither block it nor count as already
imported. `TransactionRepositoryTest` pins it. Deletion goes through
`TransactionRepository`, like manual deletion (`ManualTransactionService.deleteTransaction`):
no table holds a hard reference to a transaction (`transaction` points at its category and
recurring series, `ai_call_log.transaction_id` is `ON DELETE SET NULL`), and the balances and
snapshots of every touched import-created account are recomputed in the same transaction.

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
  `MAP_EXISTING` (the member's ledger account in the same currency), or `SKIP`. Investment,
  property and **loan** accounts are refused as targets, whether created or mapped. Picsou
  stores a loan as the positive amount owed and negates it in totals (`signedLiveBalanceEur`,
  [loans.md](loans.md)), so an Actual mortgage at -150 000 would count as +150 000 of wealth.
  The wizard leaves `LOAN` out of the type list and the mapping targets.
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
plan ─► validate mappings + sync plan (add / delete / move / warnings) ─► confirmation dialog
        │
        ▼
execute ─► validate mappings + sync plan ─► consume token ─► @Transactional:
           accounts ─► categories ─► transfer category ─► delete ─► move ─► saveAll
           ─► balances + snapshots
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
| Import-created accounts follow the file: missing rows deleted, moved rows moved | Appending only double-counted a row split or deleted in Actual (a -100 parent split into -60 / -40 became -200), and a row moved in Actual blocked every later re-import | Append-only everywhere |
| Ownership is `actual_<source id>` of a source in this file, mapped from that source | An `actual_` prefix alone let budget 2, mapped by name onto budget 1's account, delete all of budget 1's rows there | Trusting any `actual_` account |
| User-created accounts stay append-only, with a warning | Their rows may have been edited or reconciled by the user; the import never removes what it cannot prove it owns | Deleting there too |
| Refuse only when the row sits on the account created for its own Actual account | That is the one case that proves the mapping changed; a row on an untargeted user account may have moved in Actual too | Refusing whenever the row's previous source is unknown (blocked legitimate re-imports) |
| Typed acknowledgement above 200 rows or 20 % of an account | A mapping mistake that survives the ownership rule still cannot wipe a history silently | A plain checkbox (too easy to click through) |
| A dry-run endpoint for the counts | Deletions and moves depend on the mappings, which the preview does not know | Estimating them in the preview from default mappings |
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
  and categories, currency/kind/investment-account/loan rejections, token scope and expiry.
  Re-import rules: split after import and deleted after import on an import-created account
  (row deleted, balance exact), the same on a user-created account (rows kept, `KEPT_MISSING`),
  a row moved between import-created accounts and into an account the re-import creates (both
  balances exact), a row moved out of or into a user-created account (`KEPT_MOVED`, import
  succeeds), and a plan that writes nothing and leaves the token usable. Ownership: a second
  budget mapped onto the first one's same-name account deletes nothing (`KEPT_MISSING × 7`),
  the account created for the same source still deletes when mapped explicitly, a row on a
  user account the request no longer targets is `KEPT_MOVED` instead of refused, and a
  soft-deleted import-created account's rows do not block a re-import. Large deletions (3 of 7
  rows, and 250 of 1 507) are refused without acknowledgement and applied with it.
- `TransactionRepositoryTest` (H2 `@DataJpaTest`) — both stored-row lookups skip the rows of a
  soft-deleted account.
- `features/actual/hooks.test.tsx` — a finished import invalidates accounts, categories,
  budget, dashboard, intraday net worth, history, P&L, analysis, goals and savings.
- `ActualBudgetImportControllerTest` — 400 ProblemDetail, 200 plan, 201 result, request
  validation.
- `ActualBudgetImportWiringTest` — Spring picks the production constructors (the service also
  has a test-only one taking a `Clock`).
- `ActualBudgetTab.test.tsx` — preview, dry run then confirmation with the same request, plan
  counts and warnings in the dialog and the result, a refused dry run opening no dialog, no
  `LOAN` type or target, currency choice, compatible targets, the previously imported account
  pre-selected over a same-name one, another source's imported account never pre-selected by
  name, the typed confirmation of a large deletion and its acknowledgement flag, validation
  gating, error display, demo-mode guard.
- `e2e/sync.spec.ts` — the tab is listed and opens on its upload step.

## Links

- Sibling importers: [finary-import.md](finary-import.md) · [csv-transaction-import.md](csv-transaction-import.md)
- Categories and kinds: [budget.md](budget.md)
- Ticket: [issue #173](https://github.com/Cloeille/picsou-finance/issues/173)
