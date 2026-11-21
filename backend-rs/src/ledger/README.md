# ledger

The model every domain track reads and writes: accounts, holdings, transactions, balance snapshots, ownership shares, FX reads and the ingestion primitives (track L). It serves no endpoint.

## Conventions

- Rows are in `model` (`Account`, `AccountHolding`, `Transaction`, `BalanceSnapshot`, ...); enums store their Java names (`AccountType::as_str`, `FromStr`, sqlx encode/decode as text). Queries on the native PostgreSQL enums bind with `$1::account_type` / `$1::category_kind`.
- Repository functions take `impl PgExecutor` (a pool, a connection or a transaction); a function that writes several rows takes `&mut PgConnection` so the caller owns the transaction (`&mut *tx`).
- Account reads never return soft-deleted rows (Java's `@SQLRestriction`). Member scoping is an argument: use `accounts::get_or_throw(db, id, member_id)` for an owned account, `access::require_readable` / `access::require_owner` when co-owners are involved. Errors are `AppError` with Java's bodies (`accounts::account_not_found()`).
- Time is a parameter: `today: NaiveDate` (`state.clock.today()`) and `now: DateTime<Utc>` (`state.clock.now()`). Nothing here reads the system clock.
- `decimal` holds the BigDecimal semantics: `set_scale` (pads like setScale), `divide`, `divide64` / `multiply64` (MathContext.DECIMAL64), `pow64`.

## Plug-in points (`state.ledger`)

| Field | Trait | Default (Java with the provider absent) | Owner |
|---|---|---|---|
| `prices` | `prices::PriceRead` (`quotes`, `fx_rate_eur`, provided `quote`, `to_eur`) | `RecordedPrices`: last `price_snapshot` row within 7 days, no FX, no crypto | T4 (providers, cache, refresh; reuse `prices::last_known_prices` for the fallback) |
| `dividends` | `transfers::DividendClassifier` | `NoDividendClassifier` | T4 (`DividendReceiptService.classifyRecent`) |
| `connection_locks` | `locks::ConnectionLocks` | `MemberRowLock` (`family_member` row lock, what CORUM and Louve do) | T5/T6 |
| `knowledge_base(&db)` | merchant knowledge base, loaded once | | |

## API by task

- **Accounts**: `accounts::{insert(NewAccount), update(&Account), set_balance, find_*, exists_soft_deleted_*, restore_soft_deleted_*}`; display order and collapse in `display_prefs`; members and the sync lock in `members::{lock, lock_or_throw}`.
- **Visibility and shares**: `access::{readable_accounts, readable_account_ids, share_for, shares_for, weigh, require_readable, require_owner, is_readable}`. Shares weight reads only; stored values stay at 100%.
- **Valuation**: `valuation::valuation(conn, prices, &account, today) -> Valuation { live_eur, invested_eur, all_priced, any_priced, any_stale }`, `live_balance_eur`, `signed_live_balance_eur` (LOAN negative), `invested_amount`, `cost_basis_of`, `provider_cost_basis_eur`, `quotes_for`. Loan math in `loans`, peer-loan math in `peer_loans`, provider classifications in `providers`.
- **Snapshots**: `valuation::upsert_snapshot(conn, &SnapshotContext, &account, balance_eur, date)` (invested = current cost basis) and `upsert_snapshot_from_native` (converts, refreshes a MANUAL row's native figure); `snapshots::upsert(conn, account_id, balance, invested, date, now)` when the caller has the invested amount; reads in `snapshots`.
- **Holdings**: `holdings::{upsert_holding, prune_holdings, replace_all, insert, update, find_*}`; merging duplicate lines: `holding_dedup::vwap_merge`.
- **Transactions**: `transactions::{insert(NewTransaction), update, find_*, sum_*, latest_synced_date}`.
- **Ingestion**:
  - Enable Banking: `bank_import::window_start`, fetch, then `bank_import::import_fetched` (EUR rows only). SimpleFIN: `bank_import::import_provided` (native currency kept). Neither edits an existing row.
  - Bank sidecars (Bnp, BoursoBank, Revolut): `sidecar_import::import(conn, &account, &rows, &ctx, prefix, now)` with `ctx = categorize::load_context(&db, kb, member_id, now)` loaded once per sync.
  - After the commit: `transfers::after_import(&db, &*state.ledger.dividends, member_id, today, now)` (dividends, then internal-transfer pairing; idempotent).
  - Categorization: `categorize::{auto_categorize, enrich, apply, categories_by_slug, ensure_seeded, find_or_create_default}`; it never overrides an assigned category.
MANUAL_SECTION
- **Sidecar shared types**: `sidecar::{SidecarBank, SidecarErrorCode, SidecarTransaction, SourceSyncResult, SyncStatus}`, `sync_values`, `instrument::resolve`.
