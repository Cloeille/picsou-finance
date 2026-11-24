//! Side-by-side dual-engine integration tests verifying that the exact same generic repository
//! functions execute identically on `Db::Postgres` and `Db::Sqlite` (both in-memory and WAL
//! file-backed), and that fresh SQLite seed data matches Flyway-migrated PostgreSQL 1:1.

use std::collections::{BTreeMap, BTreeSet};
use std::str::FromStr;

use chrono::{DateTime, NaiveDate, Utc};
use picsou::config::{Config, Env};
use picsou::db::sqlite::SqliteDb;
use picsou::db::sqlite::seed;
use picsou::db::{self, AsDbHandle, Db, DbEnum, DbExec, DbFromRow};
use picsou::testing::fresh_database;
use rust_decimal::Decimal;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq, DbFromRow)]
struct MemberMeta {
    display_name: String,
    #[sqlx(rename = "is_managed")]
    managed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, DbFromRow)]
struct MemberRow {
    id: i64,
    #[db(flatten)]
    meta: MemberMeta,
    #[sqlx(default)]
    missing_col_with_default: i64,
    #[db(default)]
    missing_opt_col: Option<String>,
    #[sqlx(skip)]
    computed_tag: String,
    #[db(skip)]
    computed_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, DbFromRow)]
struct AccountSummary {
    id: i64,
    name: String,
    #[db(rename = "type")]
    kind: String,
    current_balance: Decimal,
    is_manual: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, sqlx::Type, DbEnum)]
#[sqlx(type_name = "sharing_level", rename_all = "UPPERCASE")]
enum TestSharingLevel {
    All,
    None,
    Manual,
}

async fn insert_member<'e>(
    exec: impl DbExec<'e>,
    name: &str,
    managed: bool,
) -> Result<i64, sqlx::Error> {
    db::query_scalar::<i64>(
        "INSERT INTO family_member (display_name, is_managed, created_at) \
         VALUES ($1, $2, NOW()) RETURNING id",
    )
    .bind(name)
    .bind(managed)
    .fetch_one(exec)
    .await
}

async fn load_member<'e>(exec: impl DbExec<'e>, id: i64) -> Result<Option<MemberRow>, sqlx::Error> {
    db::query_as::<MemberRow>(
        "SELECT id, display_name, is_managed FROM family_member WHERE id = $1 FOR UPDATE",
    )
    .bind(id)
    .fetch_optional(exec)
    .await
}

async fn run_dual_engine_suite(db: &Db) {
    // 1. Insert + DbFromRow (rename, flatten, default, skip, NOW(), FOR UPDATE).
    let alice_id = insert_member(db, "Alice", false)
        .await
        .expect("insert Alice");
    let bob_id = insert_member(db, "Bob", true).await.expect("insert Bob");

    let alice = load_member(db, alice_id)
        .await
        .expect("load Alice")
        .expect("Alice exists");
    assert_eq!(alice.id, alice_id);
    assert_eq!(alice.meta.display_name, "Alice");
    assert!(!alice.meta.managed);
    assert_eq!(alice.missing_col_with_default, 0);
    assert_eq!(alice.missing_opt_col, None);
    assert_eq!(alice.computed_tag, "");
    assert_eq!(alice.computed_count, 0);

    // 2. Tuple DbFromRow + ANY($1) with Vec<i64> / &[i64] / Vec<String> / empty / NULL / != ALL.
    let ids = vec![alice_id, bob_id];
    let pairs: Vec<(i64, String, bool)> = db::query_as(
        "SELECT id, display_name, is_managed FROM family_member \
         WHERE id = ANY($1) ORDER BY id",
    )
    .bind(&ids)
    .fetch_all(db)
    .await
    .expect("ANY($1) i64");
    assert_eq!(
        pairs,
        vec![
            (alice_id, "Alice".to_owned(), false),
            (bob_id, "Bob".to_owned(), true),
        ]
    );

    let names = vec!["Alice".to_owned()];
    let by_name: Vec<i64> =
        db::query_scalar("SELECT id FROM family_member WHERE display_name = ANY($1) ORDER BY id")
            .bind(&names)
            .fetch_all(db)
            .await
            .expect("ANY($1) String");
    assert_eq!(by_name, vec![alice_id]);

    let excluded: Vec<String> = db::query_scalar(
        "SELECT display_name FROM family_member WHERE id = ANY($1) AND display_name != ALL($2) ORDER BY id",
    )
    .bind(&ids)
    .bind(&names)
    .fetch_all(db)
    .await
    .expect("!= ALL($2)");
    assert_eq!(excluded, vec!["Bob".to_owned()]);

    let not_any: Vec<String> = db::query_scalar(
        "SELECT display_name FROM family_member WHERE id = ANY($1) AND NOT (display_name = ANY($2)) ORDER BY id",
    )
    .bind(&ids)
    .bind(&names)
    .fetch_all(db)
    .await
    .expect("NOT (col = ANY($2))");
    assert_eq!(not_any, vec!["Bob".to_owned()]);

    let empty_ids: Vec<i64> = vec![];
    let empty_res: Vec<i64> = db::query_scalar("SELECT id FROM family_member WHERE id = ANY($1)")
        .bind(&empty_ids)
        .fetch_all(db)
        .await
        .expect("empty ANY($1)");
    assert!(empty_res.is_empty());

    let none_filter: Option<Vec<i64>> = None;
    let all_when_null: Vec<i64> = db::query_scalar(
        "SELECT id FROM family_member WHERE ($1::bigint[] IS NULL OR id = ANY($1)) ORDER BY id",
    )
    .bind(none_filter)
    .fetch_all(db)
    .await
    .expect("nullable ANY filter");
    assert!(all_when_null.contains(&alice_id));
    assert!(all_when_null.contains(&bob_id));

    // 3. Exact Decimal scale preservation + ON CONFLICT DO UPDATE RETURNING + typed dates/UUID/BLOB.
    let big_dec = Decimal::from_str("99999999999.99999999").unwrap();
    let acc_id: i64 = db::query_scalar(
        "INSERT INTO account (name, type, currency, current_balance, is_manual, member_id) \
         VALUES ($1, $2::account_type, 'EUR', $3, $4, $5) RETURNING id",
    )
    .bind("PEA Precision")
    .bind("PEA")
    .bind(big_dec)
    .bind(true)
    .bind(alice_id)
    .fetch_one(db)
    .await
    .expect("insert account");

    let summary: AccountSummary = db::query_as(
        "SELECT id, name, type::text AS type, current_balance, is_manual FROM account WHERE id = $1",
    )
    .bind(acc_id)
    .fetch_one(db)
    .await
    .expect("select account");
    assert_eq!(summary.id, acc_id);
    assert_eq!(summary.name, "PEA Precision");
    assert_eq!(summary.kind, "PEA");
    assert_eq!(summary.current_balance, big_dec);
    assert_eq!(summary.current_balance.scale(), 8);
    assert!(summary.is_manual);

    // Exact 0.10 + 0.20 == 0.30 sum test across both engines.
    let d1 = NaiveDate::from_ymd_opt(2026, 1, 10).unwrap();
    let d2 = NaiveDate::from_ymd_opt(2026, 1, 11).unwrap();
    for (date, desc, amt) in [
        (d1, "tx1", Decimal::from_str("0.10").unwrap()),
        (d2, "tx2", Decimal::from_str("0.20").unwrap()),
    ] {
        db::query(
            "INSERT INTO \"transaction\" (account_id, date, description, amount, is_manual) \
             VALUES ($1, $2, $3, $4, true)",
        )
        .bind(acc_id)
        .bind(date)
        .bind(desc)
        .bind(amt)
        .execute(db)
        .await
        .expect("insert tx");
    }
    let sum_sql = if db.is_sqlite() {
        "SELECT COALESCE(dec_sum(amount), 0) FROM \"transaction\" WHERE account_id = $1"
    } else {
        "SELECT COALESCE(SUM(amount), 0) FROM \"transaction\" WHERE account_id = $1"
    };
    let total: Decimal = db::query_scalar(sum_sql)
        .bind(acc_id)
        .fetch_one(db)
        .await
        .expect("sum transactions");
    assert_eq!(total, Decimal::from_str("0.30").unwrap());

    // ON CONFLICT DO UPDATE RETURNING on app_setting.
    let (k, v): (String, String) = db::query_as(
        "INSERT INTO app_setting (setting_key, value, updated_at) \
         VALUES ($1, $2, NOW()) \
         ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW() \
         RETURNING setting_key, value",
    )
    .bind("dual.test.key")
    .bind("v1")
    .fetch_one(db)
    .await
    .expect("upsert v1");
    assert_eq!((k, v), ("dual.test.key".to_owned(), "v1".to_owned()));

    let v2: String = db::query_scalar(
        "INSERT INTO app_setting (setting_key, value, updated_at) \
         VALUES ($1, $2, NOW()) \
         ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW() \
         RETURNING value",
    )
    .bind("dual.test.key")
    .bind("v2")
    .fetch_one(db)
    .await
    .expect("upsert v2");
    assert_eq!(v2, "v2");

    // DbEnum round-trip (sharing_level PG enum / SQLite CHECK text).
    db::query(
        "INSERT INTO sharing_settings (member_id, resource_type, sharing_level) \
         VALUES ($1, 'ACCOUNT', $2)",
    )
    .bind(alice_id)
    .bind(TestSharingLevel::Manual)
    .execute(db)
    .await
    .expect("insert sharing_settings with DbEnum");

    let loaded_level: TestSharingLevel = db::query_scalar(
        "SELECT sharing_level FROM sharing_settings WHERE member_id = $1 AND resource_type = 'ACCOUNT'",
    )
    .bind(alice_id)
    .fetch_one(db)
    .await
    .expect("select DbEnum");
    assert_eq!(loaded_level, TestSharingLevel::Manual);

    // DateTime<Utc>, Uuid, Vec<u8> round-trip.
    let now_dt: DateTime<Utc> = DateTime::parse_from_rfc3339("2026-06-15T12:34:56.789123Z")
        .unwrap()
        .with_timezone(&Utc);
    let user_id: i64 = db::query_scalar(
        "INSERT INTO app_user (username, password_hash, role, member_id, is_activated, created_at) \
         VALUES ($1, 'hash', 'ADMIN', $2, true, $3) RETURNING id",
    )
    .bind("alice_dual")
    .bind(alice_id)
    .bind(now_dt)
    .fetch_one(db)
    .await
    .expect("insert app_user");

    let series = Uuid::parse_str("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").unwrap();
    db::query(
        "INSERT INTO persistent_session (user_id, series_id, token_hash, expires_at, trusted_for_2fa) \
         VALUES ($1, $2, 'tok', $3, true)",
    )
    .bind(user_id)
    .bind(series)
    .bind(now_dt)
    .execute(db)
    .await
    .expect("insert persistent_session");

    let (got_series, got_exp): (Uuid, DateTime<Utc>) =
        db::query_as("SELECT series_id, expires_at FROM persistent_session WHERE user_id = $1")
            .bind(user_id)
            .fetch_one(db)
            .await
            .expect("select persistent_session");
    assert_eq!(got_series, series);
    assert_eq!(got_exp, now_dt);

    let png_bytes: Vec<u8> = vec![0x89, b'P', b'N', b'G', 1, 2, 3, 4];
    db::query(
        "INSERT INTO instrument_logo (ticker, status, image, content_type, fetched_at, attempted_at) \
         VALUES ('TEST.PA', 'STORED', $1, 'image/png', $2, $2)",
    )
    .bind(&png_bytes)
    .bind(now_dt)
    .execute(db)
    .await
    .expect("insert instrument_logo");
    let got_bytes: Vec<u8> =
        db::query_scalar("SELECT image FROM instrument_logo WHERE ticker = 'TEST.PA'")
            .fetch_one(db)
            .await
            .expect("select instrument_logo");
    assert_eq!(got_bytes, png_bytes);

    // AT TIME ZONE 'UTC' query pattern used by sync/exchanges & sync/wallets.
    let updated = db::query(
        "UPDATE account SET deleted_at = ($2::timestamptz AT TIME ZONE 'UTC'), updated_at = $2 WHERE id = $1",
    )
    .bind(acc_id)
    .bind(now_dt)
    .execute(db)
    .await
    .expect("AT TIME ZONE 'UTC' update");
    assert_eq!(updated.rows_affected(), 1);

    // 4. Constraint errors: UNIQUE violation & FOREIGN KEY violation identically detected.
    let dup_err = db::query(
        "INSERT INTO app_user (username, password_hash, role, member_id, is_activated) \
         VALUES ('alice_dual', 'hash2', 'MEMBER', $1, true)",
    )
    .bind(bob_id)
    .execute(db)
    .await
    .expect_err("duplicate username must fail");
    assert!(
        db::is_unique_violation(&dup_err),
        "expected unique violation, got: {dup_err}"
    );
    assert!(
        dup_err
            .as_database_error()
            .is_some_and(|e| e.is_unique_violation())
    );

    let fk_err = db::query(
        "INSERT INTO account (name, type, currency, current_balance, is_manual, member_id) \
         VALUES ('Orphan', 'CHECKING', 'EUR', '0', true, 999999999)",
    )
    .execute(db)
    .await
    .expect_err("dangling member_id FK must fail");
    assert!(
        db::is_foreign_key_violation(&fk_err),
        "expected FK violation, got: {fk_err}"
    );
    assert!(
        fk_err
            .as_database_error()
            .is_some_and(|e| e.is_foreign_key_violation())
    );

    // 5. Transactions: commit, explicit rollback, automatic Drop rollback, and nested SAVEPOINT.
    // 5a. Commit persists.
    let committed_id = {
        let mut tx = db.begin().await.expect("begin commit tx");
        let id = insert_member(&mut tx, "Committed", false)
            .await
            .expect("insert in tx");
        tx.commit().await.expect("commit tx");
        id
    };
    assert!(load_member(db, committed_id).await.unwrap().is_some());

    // 5b. Explicit rollback discards writes.
    let rolled_back_id = {
        let mut tx = db.begin().await.expect("begin rollback tx");
        let id = insert_member(&mut tx, "RolledBack", false)
            .await
            .expect("insert in rollback tx");
        tx.rollback().await.expect("rollback tx");
        id
    };
    assert!(load_member(db, rolled_back_id).await.unwrap().is_none());

    // 5c. Implicit Drop rollback discards writes cleanly without panicking or deadlocking.
    let dropped_id = {
        let mut tx = db.begin().await.expect("begin drop tx");
        let id = insert_member(&mut tx, "DroppedTx", false)
            .await
            .expect("insert in drop tx");
        // drop `tx` without calling commit()
        id
    };
    assert!(load_member(db, dropped_id).await.unwrap().is_none());

    // 5d. Nested SAVEPOINT: inner rollback keeps outer writes; inner commit persists with outer.
    let (outer_id, sp_kept_id, sp_rolled_id) = {
        let mut tx = db.begin().await.expect("begin outer tx");
        let outer_id = insert_member(&mut tx, "OuterTx", false)
            .await
            .expect("insert outer");

        let sp_kept_id = {
            let mut sp = tx.begin().await.expect("begin inner sp1");
            let id = insert_member(&mut sp, "SpKept", false)
                .await
                .expect("insert sp1");
            sp.commit().await.expect("commit sp1");
            id
        };

        let sp_rolled_id = {
            let mut sp = tx.begin().await.expect("begin inner sp2");
            let id = insert_member(&mut sp, "SpRolled", false)
                .await
                .expect("insert sp2");
            // Drop without commit -> rollback savepoint only!
            id
        };

        tx.commit().await.expect("commit outer tx");
        (outer_id, sp_kept_id, sp_rolled_id)
    };
    assert!(load_member(db, outer_id).await.unwrap().is_some());
    assert!(load_member(db, sp_kept_id).await.unwrap().is_some());
    assert!(load_member(db, sp_rolled_id).await.unwrap().is_none());

    // 6. Acquired connection handle (`DbConn`) + transaction on `DbConn`.
    {
        let mut conn = db.acquire().await.expect("acquire conn");
        let mut tx = conn.begin().await.expect("begin on conn");
        let id = insert_member(&mut tx, "FromConn", false)
            .await
            .expect("insert via conn tx");
        tx.commit().await.expect("commit conn tx");
        let row = load_member(&mut conn, id)
            .await
            .expect("read via conn")
            .expect("row exists");
        assert_eq!(row.meta.display_name, "FromConn");
    }
}

#[tokio::test]
async fn dual_engine_queries_and_transactions_match_on_postgres_and_sqlite() {
    // Run against live PostgreSQL.
    let pg_db_handle = fresh_database().await;
    let pg_pool = sqlx::PgPool::connect(&pg_db_handle.url())
        .await
        .expect("connect test PostgreSQL");
    let pg = Db::Postgres(pg_pool);
    assert!(pg.is_postgres());
    assert!(!pg.is_sqlite());
    assert!(pg.as_pg().is_some());
    run_dual_engine_suite(&pg).await;
    pg.close().await;

    // Run against in-memory SQLite.
    let sq_mem_inner = SqliteDb::open_in_memory().expect("open in-memory SQLite");
    sq_mem_inner.migrate().expect("migrate in-memory SQLite");
    let sq_mem = Db::Sqlite(sq_mem_inner);
    assert!(sq_mem.is_sqlite());
    assert!(!sq_mem.is_postgres());
    assert!(sq_mem.as_pg().is_none());
    run_dual_engine_suite(&sq_mem).await;
    sq_mem.close().await;

    // Run against file-backed WAL SQLite (exercises read pool + WAL checkpoint close).
    let path = std::env::temp_dir().join(format!(
        "picsou_dual_engine_wal_{}_{}.db",
        std::process::id(),
        Uuid::new_v4().simple()
    ));
    let sq_file_inner = SqliteDb::open(&path).expect("open WAL SQLite");
    sq_file_inner.migrate().expect("migrate WAL SQLite");
    let sq_file = Db::Sqlite(sq_file_inner);
    run_dual_engine_suite(&sq_file).await;
    sq_file.close().await;

    let _ = std::fs::remove_file(&path);
    let _ = std::fs::remove_file(format!("{}-wal", path.display()));
    let _ = std::fs::remove_file(format!("{}-shm", path.display()));
}

#[tokio::test]
async fn fresh_sqlite_seed_matches_flyway_migrated_postgresql_1_to_1() {
    let pg_handle = fresh_database().await;
    let pg_pool = sqlx::PgPool::connect(&pg_handle.url())
        .await
        .expect("connect test PostgreSQL");
    let pg = Db::Postgres(pg_pool);

    let sq_inner = SqliteDb::open_in_memory().expect("open in-memory SQLite");
    sq_inner.migrate().expect("migrate SQLite");
    seed::ensure_seeded(&sq_inner).expect("seed SQLite");
    // Re-running seed must be a strict idempotent no-op.
    seed::ensure_seeded(&sq_inner).expect("idempotent re-seed SQLite");
    let sq = Db::Sqlite(sq_inner);

    // 1. app_setting parity
    let pg_settings: BTreeMap<String, String> = db::query_as::<(String, String)>(
        "SELECT setting_key, value FROM app_setting ORDER BY setting_key",
    )
    .fetch_all(&pg)
    .await
    .unwrap()
    .into_iter()
    .collect();
    let sq_settings: BTreeMap<String, String> = db::query_as::<(String, String)>(
        "SELECT setting_key, value FROM app_setting ORDER BY setting_key",
    )
    .fetch_all(&sq)
    .await
    .unwrap()
    .into_iter()
    .collect();
    assert_eq!(sq_settings, pg_settings, "app_setting seed drift");

    // 2. crypto_asset parity
    let pg_crypto: Vec<(String, String, String, String)> =
        db::query_as("SELECT ticker, coingecko_id, name, source FROM crypto_asset ORDER BY ticker")
            .fetch_all(&pg)
            .await
            .unwrap();
    let sq_crypto: Vec<(String, String, String, String)> =
        db::query_as("SELECT ticker, coingecko_id, name, source FROM crypto_asset ORDER BY ticker")
            .fetch_all(&sq)
            .await
            .unwrap();
    assert_eq!(sq_crypto, pg_crypto, "crypto_asset seed drift");

    // 3. merchant_brand parity
    type BrandRow = (
        String,
        String,
        String,
        Option<String>,
        Option<String>,
        Option<String>,
    );
    let pg_brands: Vec<BrandRow> = db::query_as(
        "SELECT slug, display_name, default_category_slug, color, monogram, logo_domain \
             FROM merchant_brand ORDER BY slug",
    )
    .fetch_all(&pg)
    .await
    .unwrap();
    let sq_brands: Vec<BrandRow> = db::query_as(
        "SELECT slug, display_name, default_category_slug, color, monogram, logo_domain \
             FROM merchant_brand ORDER BY slug",
    )
    .fetch_all(&sq)
    .await
    .unwrap();
    assert_eq!(sq_brands, pg_brands, "merchant_brand seed drift");

    // 4. merchant_alias parity (joined via brand slug so autoincrement IDs don't matter)
    let pg_aliases: BTreeSet<(String, String, String)> = db::query_as::<(String, String, String)>(
        "SELECT b.slug, a.pattern, a.match_type \
         FROM merchant_alias a JOIN merchant_brand b ON b.id = a.brand_id",
    )
    .fetch_all(&pg)
    .await
    .unwrap()
    .into_iter()
    .collect();
    let sq_aliases: BTreeSet<(String, String, String)> = db::query_as::<(String, String, String)>(
        "SELECT b.slug, a.pattern, a.match_type \
         FROM merchant_alias a JOIN merchant_brand b ON b.id = a.brand_id",
    )
    .fetch_all(&sq)
    .await
    .unwrap()
    .into_iter()
    .collect();
    assert_eq!(sq_aliases, pg_aliases, "merchant_alias seed drift");
    pg.close().await;
    sq.close().await;
}

#[tokio::test]
async fn db_connect_opens_sqlite_from_config() {
    let env = Env::from_map([
        ("JWT_SECRET", "0123456789abcdef0123456789abcdef"),
        ("CRYPTO_ENCRYPTION_KEY", "0123456789abcdef0123456789abcdef"),
        ("DATABASE_URL", "sqlite::memory:"),
    ]);
    let config = Config::load(&env).expect("load sqlite config");
    let db = db::db_connect(&config).await.expect("connect sqlite");
    assert!(db.is_sqlite());
    let one: i64 = db::query_scalar("SELECT 1").fetch_one(&db).await.unwrap();
    assert_eq!(one, 1);
    db.close().await;
}

#[tokio::test]
async fn sqlite_tx_rollback_is_cancellation_safe_and_never_wedges_writer() {
    let sq = SqliteDb::open_in_memory().expect("open in-memory SQLite");
    sq.migrate().expect("migrate SQLite");
    let db = Db::Sqlite(sq);

    // Begin a transaction, insert a member, then construct `tx.rollback()` and drop the future
    // without awaiting it to completion (simulating client disconnect / tokio::select! cancel).
    {
        let mut tx = db.begin().await.expect("begin tx 1");
        db::query(
            "INSERT INTO family_member (display_name, is_managed) VALUES ('Cancelled', false)",
        )
        .execute(&mut tx)
        .await
        .expect("insert in tx 1");
        let rollback_fut = tx.rollback();
        drop(rollback_fut);
    }

    // The writer connection must NOT be stuck inside an uncommitted transaction, and the row must
    // have been rolled back by `Drop`.
    let count: i64 =
        db::query_scalar("SELECT count(*) FROM family_member WHERE display_name = 'Cancelled'")
            .fetch_one(&db)
            .await
            .expect("count after cancelled rollback");
    assert_eq!(count, 0);

    let mut tx2 = db
        .begin()
        .await
        .expect("subsequent begin() must succeed after cancelled rollback");
    db::query("INSERT INTO family_member (display_name, is_managed) VALUES ('Committed', false)")
        .execute(&mut tx2)
        .await
        .expect("insert in tx 2");
    tx2.commit().await.expect("commit tx 2");

    let committed: i64 =
        db::query_scalar("SELECT count(*) FROM family_member WHERE display_name = 'Committed'")
            .fetch_one(&db)
            .await
            .expect("count committed");
    assert_eq!(committed, 1);
    db.close().await;
}

#[tokio::test]
async fn sqlite_interact_serializes_with_active_tx_and_extrema_and_defaults_match_pg() {
    let sq = SqliteDb::open_in_memory().expect("open in-memory SQLite");
    sq.migrate().expect("migrate SQLite");

    // Verify `interact()` waits for an active `SqliteTx` rather than interleaving into it.
    let tx = sq.begin().await.expect("begin tx");
    let sq_clone = sq.clone();
    let interact_task = tokio::spawn(async move {
        sq_clone
            .interact(|conn| {
                let n: i64 = conn.query_row("SELECT 42", [], |r| r.get(0))?;
                Ok(n)
            })
            .await
    });
    tokio::time::sleep(std::time::Duration::from_millis(25)).await;
    assert!(
        !interact_task.is_finished(),
        "interact() must block behind writer_gate while SqliteTx is active"
    );
    tx.commit().await.expect("commit tx");
    let val = interact_task.await.expect("join").expect("interact result");
    assert_eq!(val, 42);

    // Verify PostgreSQL-compatible LEAST / GREATEST (NULL-ignoring & exact decimal comparisons).
    let db = Db::Sqlite(sq);
    let least_null: i64 = db::query_scalar("SELECT LEAST(5, NULL)")
        .fetch_one(&db)
        .await
        .expect("LEAST(5, NULL)");
    assert_eq!(least_null, 5);
    let least_all_null: Option<i64> = db::query_scalar("SELECT LEAST(NULL, NULL)")
        .fetch_one(&db)
        .await
        .expect("LEAST(NULL, NULL)");
    assert_eq!(least_all_null, None);
    let least_num: String = db::query_scalar("SELECT LEAST('10.50', '2.50', NULL)")
        .fetch_one(&db)
        .await
        .expect("LEAST numeric text");
    assert_eq!(least_num, "2.50");
    let greatest_num: String = db::query_scalar("SELECT GREATEST('-10.00', '2.00', NULL)")
        .fetch_one(&db)
        .await
        .expect("GREATEST numeric text");
    assert_eq!(greatest_num, "2.00");
    let greatest_date: String =
        db::query_scalar("SELECT GREATEST('2026-01-01', '2026-02-01', NULL)")
            .fetch_one(&db)
            .await
            .expect("GREATEST dates");
    assert_eq!(greatest_date, "2026-02-01");

    // Verify default timestamps in schema.sql use 6 fractional digits (`...000Z`) matching now().
    db::query("INSERT INTO family_member (display_name, is_managed) VALUES ('TsCheck', false)")
        .execute(&db)
        .await
        .expect("insert member with default timestamps");
    let created_at: String =
        db::query_scalar("SELECT created_at FROM family_member WHERE display_name = 'TsCheck'")
            .fetch_one(&db)
            .await
            .expect("select created_at");
    assert!(
        created_at.ends_with("000Z") && created_at.len() == 27,
        "expected 6-digit fractional UTC RFC 3339 timestamp, got {created_at}"
    );

    // Verify `sources_by_ticker_on` works cleanly on an in-memory SQLite `DbConn` without deadlocking.
    let mut conn = db.acquire().await.expect("acquire in-memory conn");
    let sources = picsou::wealth::dividends::model::sources_by_ticker_on(
        &mut conn.as_handle(),
        &["AAPL".to_owned()],
    )
    .await
    .expect("sources_by_ticker_on must not deadlock in-memory SQLite");
    assert!(sources.is_empty());
    drop(conn);
    db.close().await;
}

#[tokio::test]
async fn sqlite_close_drains_in_flight_readers_and_migrate_tightens_permissions() {
    let dir = std::env::temp_dir().join(format!("picsou-sqlite-polish-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&dir).expect("mkdir");
    let db_path = dir.join("picsou.db");

    let sq = SqliteDb::open(&db_path).expect("open file-backed SQLite");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        // Simulate a pre-existing DB file with overly permissive mode before migrate().
        std::fs::set_permissions(&db_path, std::fs::Permissions::from_mode(0o644)).expect("chmod");
    }
    sq.migrate().expect("migrate");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(&db_path)
            .expect("stat")
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(
            mode, 0o600,
            "migrate() must enforce 0o600 on file-backed DB"
        );
    }

    let db = Db::Sqlite(sq);
    // Launch a read on the WAL read pool and then close() so drain_async drains all readers
    // cleanly before running PRAGMA wal_checkpoint(TRUNCATE).
    let reader_db = db.clone();
    let read_task = tokio::spawn(async move {
        let _: Result<i64, _> = db::query_scalar("SELECT count(*) FROM family_member")
            .fetch_one(&reader_db)
            .await;
    });
    let _ = read_task.await;
    db.close().await;
    // Subsequent read on the closed read pool returns PoolClosed instead of reopening readers.
    let after_close: Result<i64, _> = db::query_scalar("SELECT count(*) FROM family_member")
        .fetch_one(&db)
        .await;
    assert!(
        after_close.is_err(),
        "read pool should be closed after close()"
    );
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn sqlite_wal_pool_handle_reads_do_not_block_behind_active_write_tx() {
    use std::time::Duration;

    let dir = std::env::temp_dir().join(format!("picsou-sqlite-pool-handle-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&dir).expect("mkdir");
    let db_path = dir.join("picsou.db");

    let sq = SqliteDb::open(&db_path).expect("open file-backed WAL SQLite");
    sq.migrate().expect("migrate SQLite");
    let db = Db::Sqlite(sq);

    db::query("INSERT INTO family_member (display_name, is_managed) VALUES ('Alice', false)")
        .execute(&db)
        .await
        .expect("seed member");

    // Hold an active write transaction (which owns SQLite's writer_gate).
    let mut tx = db.begin().await.expect("begin write tx");
    db::query("INSERT INTO family_member (display_name, is_managed) VALUES ('Uncommitted', false)")
        .execute(&mut tx)
        .await
        .expect("insert uncommitted row");

    // A read through `db.pool_handle()` MUST dispatch to the WAL read pool and complete
    // immediately without waiting for `tx` (whereas `db.acquire()` would block on writer_gate).
    let mut handle = db.pool_handle();
    let count: i64 = tokio::time::timeout(
        Duration::from_secs(2),
        db::query_scalar("SELECT COUNT(*) FROM family_member").fetch_one(&mut handle),
    )
    .await
    .expect("pool_handle read must not block behind active write tx")
    .expect("pool_handle read query");
    assert_eq!(count, 1, "WAL reader sees only committed snapshot");

    tx.commit().await.expect("commit tx");
    let after_commit: i64 = db::query_scalar("SELECT COUNT(*) FROM family_member")
        .fetch_one(&mut handle)
        .await
        .expect("count after commit");
    assert_eq!(after_commit, 2);

    db.close().await;
    let _ = std::fs::remove_dir_all(&dir);
}

/// On the engine the suite runs on: PostgreSQL, or SQLite with `PICSOU_TEST_DB=sqlite`.
#[tokio::test]
async fn a_read_snapshot_keeps_its_view_takes_no_writer_and_refuses_to_write() {
    use std::time::Duration;

    let app = picsou::testing::TestApp::spawn().await;
    let db = &app.state.db;
    let members = "SELECT COUNT(*) FROM family_member";
    db::query("INSERT INTO family_member (display_name, is_managed) VALUES ('Alice', false)")
        .execute(db)
        .await
        .expect("seed member");

    let mut snapshot = db.begin_read().await.expect("begin read snapshot");
    let first: i64 = db::query_scalar(members)
        .fetch_one(&mut snapshot)
        .await
        .expect("first read");
    assert_eq!(first, 1);

    // The snapshot holds no writer: a write commits while it is open.
    tokio::time::timeout(Duration::from_secs(2), async {
        let mut tx = db.begin().await?;
        db::query("INSERT INTO family_member (display_name, is_managed) VALUES ('Bob', false)")
            .execute(&mut tx)
            .await?;
        tx.commit().await
    })
    .await
    .expect("a write must not wait for an open read snapshot")
    .expect("write");

    // It still sees the database as it first read it; a read outside it sees the write.
    let again: i64 = db::query_scalar(members)
        .fetch_one(&mut snapshot)
        .await
        .expect("second read");
    assert_eq!(again, 1);
    let outside: i64 = db::query_scalar(members)
        .fetch_one(db)
        .await
        .expect("read outside");
    assert_eq!(outside, 2);
    let written = db::query("DELETE FROM family_member")
        .execute(&mut snapshot)
        .await;
    assert!(written.is_err(), "a read snapshot is read-only");
}

#[tokio::test]
async fn sqlite_writer_gate_stays_free_during_external_http_calls() {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    use picsou::clock::{Clock, FixedClock};
    use picsou::ledger::accounts::NewAccount;
    use picsou::ledger::model::AccountType;
    use picsou::testing::epoch;
    use serde_json::json;
    use tokio::sync::Notify;
    use wiremock::matchers::{body_string_contains, method, path};
    use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

    struct DelayedNotify {
        in_flight: Arc<Notify>,
        hits: Arc<AtomicUsize>,
        delay: Duration,
        body: serde_json::Value,
    }

    impl Respond for DelayedNotify {
        fn respond(&self, _request: &Request) -> ResponseTemplate {
            self.hits.fetch_add(1, Ordering::SeqCst);
            self.in_flight.notify_waiters();
            ResponseTemplate::new(200)
                .set_delay(self.delay)
                .set_body_json(self.body.clone())
        }
    }

    async fn assert_sqlite_write_succeeds_while_http_in_flight(
        db: &Db,
        in_flight: &Notify,
        label: &str,
    ) {
        tokio::time::timeout(Duration::from_secs(3), in_flight.notified())
            .await
            .unwrap_or_else(|_| panic!("{label}: expected mock HTTP call to start"));

        // Must acquire writer_gate and commit strictly BEFORE the 300ms HTTP delay elapses!
        let write_result = tokio::time::timeout(Duration::from_millis(150), async {
            let mut tx = db.begin().await?;
            db::query(
                "INSERT INTO app_setting (setting_key, value, updated_at) \
                 VALUES ($1, 'ok', NOW()) \
                 ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
            )
            .bind(format!("concurrency.probe.{label}"))
            .execute(&mut tx)
            .await?;
            tx.commit().await?;
            Ok::<_, sqlx::Error>(())
        })
        .await;

        assert!(
            matches!(write_result, Ok(Ok(()))),
            "{label}: SQLite write tx was blocked while external HTTP call was in flight: {write_result:?}"
        );
    }

    let degiro_server = MockServer::start().await;
    let louve_server = MockServer::start().await;
    let geo_server = MockServer::start().await;
    let cerema_server = MockServer::start().await;
    let openfigi_server = MockServer::start().await;
    let yahoo_server = MockServer::start().await;

    let delay = Duration::from_millis(300);

    // 1. DEGIRO mocks (/initiate fast, /portfolio delayed 300ms)
    let degiro_in_flight = Arc::new(Notify::new());
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "processId": "p1",
            "totpRequired": false,
            "sessionBlob": "synthetic-blob"
        })))
        .mount(&degiro_server)
        .await;
    Mock::given(method("POST"))
        .and(path("/portfolio"))
        .respond_with(DelayedNotify {
            in_flight: Arc::clone(&degiro_in_flight),
            hits: Arc::new(AtomicUsize::new(0)),
            delay,
            body: json!({ "cashEur": 250.0, "positions": [] }),
        })
        .mount(&degiro_server)
        .await;

    // 2. Louve mocks (/mcp/oauth/token delayed 300ms, /mcp fast)
    let louve_in_flight = Arc::new(Notify::new());
    let louve_refresh_hits = Arc::new(AtomicUsize::new(0));
    Mock::given(method("POST"))
        .and(path("/mcp/oauth/token"))
        .and(body_string_contains("grant_type=refresh_token"))
        .respond_with(DelayedNotify {
            in_flight: Arc::clone(&louve_in_flight),
            hits: Arc::clone(&louve_refresh_hits),
            delay,
            body: json!({
                "access_token": "access-2",
                "refresh_token": "refresh-2",
                "token_type": "Bearer",
                "expires_in": 3600
            }),
        })
        .mount(&louve_server)
        .await;
    struct SimpleLouveMcp;
    impl Respond for SimpleLouveMcp {
        fn respond(&self, request: &Request) -> ResponseTemplate {
            let body: serde_json::Value = serde_json::from_slice(&request.body).expect("rpc");
            let id = body["id"].clone();
            let result = match body["method"].as_str() {
                Some("initialize") => {
                    json!({"protocolVersion": "2025-06-18", "capabilities": {}})
                }
                Some("notifications/initialized") => return ResponseTemplate::new(202),
                Some("tools/call") => {
                    json!({"structuredContent": {"scpi": {"sellingPrice": 0, "positions": []}}})
                }
                _ => return ResponseTemplate::new(400),
            };
            ResponseTemplate::new(200)
                .insert_header("Mcp-Session-Id", "sess-1")
                .set_body_json(json!({"jsonrpc": "2.0", "id": id, "result": result}))
        }
    }
    Mock::given(method("POST"))
        .and(path("/mcp"))
        .respond_with(SimpleLouveMcp)
        .mount(&louve_server)
        .await;

    // 3. Real estate geocoding mock (delayed 300ms) + Cerema mock
    let geo_in_flight = Arc::new(Notify::new());
    Mock::given(method("GET"))
        .and(path("/search"))
        .respond_with(DelayedNotify {
            in_flight: Arc::clone(&geo_in_flight),
            hits: Arc::new(AtomicUsize::new(0)),
            delay,
            body: json!({
                "features": [{
                    "geometry": {"coordinates": [2.3522, 48.8566]},
                    "properties": {
                        "id": "75101_0001",
                        "label": "1 Rue de Rivoli 75001 Paris",
                        "score": 0.95,
                        "citycode": "75101",
                        "postcode": "75001",
                        "city": "Paris"
                    }
                }]
            }),
        })
        .mount(&geo_server)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "results": []
        })))
        .mount(&cerema_server)
        .await;

    // 4. OpenFIGI mock (delayed 300ms) + Yahoo chart mock
    let openfigi_in_flight = Arc::new(Notify::new());
    Mock::given(method("POST"))
        .and(path("/v3/mapping"))
        .respond_with(DelayedNotify {
            in_flight: Arc::clone(&openfigi_in_flight),
            hits: Arc::new(AtomicUsize::new(0)),
            delay,
            body: json!([{"data": [{"ticker": "TTE", "exchCode": "FP", "name": "TOTALENERGIES SE"}]}]),
        })
        .mount(&openfigi_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v8/finance/chart/TTE.PA"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "chart": {"result": [{"meta": {"currency": "EUR", "regularMarketPrice": 55.0}}]}
        })))
        .mount(&yahoo_server)
        .await;

    // Spin up a dedicated file-backed WAL SQLite AppState + Axum server.
    let dir = std::env::temp_dir().join(format!("picsou-sqlite-http-tx-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&dir).expect("mkdir");
    let db_path = dir.join("picsou.db");
    let path_str = db_path.to_string_lossy().replace('\\', "/");
    let env = Env::from_map([
        ("JWT_SECRET", "0123456789abcdef0123456789abcdef"),
        ("CRYPTO_ENCRYPTION_KEY", "0123456789abcdef0123456789abcdef"),
        (
            "DATABASE_URL",
            &format!("sqlite:///{}", path_str.trim_start_matches('/')),
        ),
        ("SECURE_COOKIES", "false"),
        ("APP_STARTUP_SYNC_ENABLED", "false"),
        ("APP_UPDATE_CHECK_ENABLED", "false"),
        ("INSTRUMENT_LOGOS_ENABLED", "false"),
        ("DEGIRO_AUTH_URL", &degiro_server.uri()),
        ("LOUVE_ISSUER_URL", &louve_server.uri()),
        ("ALLOWED_ORIGINS", "https://picsou.example"),
        ("GEOCODING_API_URL", &geo_server.uri()),
        ("CEREMA_API_URL", &cerema_server.uri()),
        ("INSEE_API_URL", "http://127.0.0.1:1"),
        ("OPENFIGI_BASE_URL", &openfigi_server.uri()),
        ("YAHOO_FINANCE_BASE_URL", &yahoo_server.uri()),
    ]);
    let config = Config::load(&env).expect("load config");
    let clock = Arc::new(FixedClock::new(epoch()));
    let state = picsou::start(config, clock.clone())
        .await
        .expect("start sqlite app");
    assert!(state.db.is_sqlite());

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let base_url = format!("http://{}", listener.local_addr().unwrap());
    let router = picsou::app(state.clone());
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    tokio::spawn(async move {
        let _ = axum::serve(
            listener,
            router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .with_graceful_shutdown(async {
            let _ = stop_rx.await;
        })
        .await;
    });

    // Complete setup and log in as admin.
    let mut default_headers = reqwest::header::HeaderMap::new();
    default_headers.insert(
        reqwest::header::ORIGIN,
        reqwest::header::HeaderValue::from_str(&base_url).unwrap(),
    );
    let http = reqwest::Client::builder()
        .cookie_store(true)
        .default_headers(default_headers)
        .build()
        .expect("client");
    let setup = http
        .post(format!("{base_url}/api/setup/admin"))
        .json(&json!({
            "username": "admin",
            "password": "StrongPassword123!"
        }))
        .send()
        .await
        .expect("setup admin");
    assert_eq!(setup.status().as_u16(), 200);
    let complete = http
        .post(format!("{base_url}/api/setup/complete"))
        .send()
        .await
        .expect("setup complete");
    assert_eq!(complete.status().as_u16(), 204);
    let login = http
        .post(format!("{base_url}/api/auth/login"))
        .json(&json!({ "username": "admin", "password": "StrongPassword123!" }))
        .send()
        .await
        .expect("login");
    assert_eq!(login.status().as_u16(), 200);
    let member_id: i64 =
        db::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
            .fetch_one(&state.db)
            .await
            .expect("admin member_id");

    // --- Case 1: DEGIRO store_session_and_sync (/portfolio HTTP outside tx) ---
    let degiro_http = http.clone();
    let degiro_url = format!("{base_url}/api/degiro/auth/initiate");
    let degiro_task = tokio::spawn(async move {
        degiro_http
            .post(degiro_url)
            .json(&json!({ "username": "u", "password": "p" }))
            .send()
            .await
            .expect("degiro initiate")
    });
    assert_sqlite_write_succeeds_while_http_in_flight(&state.db, &degiro_in_flight, "degiro").await;
    let degiro_resp = degiro_task.await.expect("join degiro");
    assert_eq!(degiro_resp.status().as_u16(), 200);

    // --- Case 2: Louve refresh (HTTP outside tx + per-member mutex prevents double refresh) ---
    let expiring_token = json!({
        "v": 1,
        "accessToken": "access-1",
        "refreshToken": "refresh-1",
        "expiresAt": clock.now().timestamp() + 30,
        "clientId": "cid-1",
        "redirectUri": "https://picsou.example/sync/louve/callback",
    })
    .to_string();
    let encrypted = state.crypto.encrypt(&expiring_token).expect("encrypt");
    db::query(
        "INSERT INTO louve_connection (member_id, token_state, status, created_at, updated_at) \
         VALUES ($1, $2, 'CONNECTED', NOW(), NOW())",
    )
    .bind(member_id)
    .bind(encrypted)
    .execute(&state.db)
    .await
    .expect("seed louve connection");

    // Launch TWO concurrent syncs for the same member: per-member mutex must serialize them so
    // /mcp/oauth/token is hit EXACTLY ONCE while SQLite writes stay unblocked!
    let louve_http_1 = http.clone();
    let louve_http_2 = http.clone();
    let louve_url = format!("{base_url}/api/louve/sync");
    let louve_url_2 = louve_url.clone();
    let louve_task_1 =
        tokio::spawn(async move { louve_http_1.post(louve_url).send().await.expect("louve 1") });
    let louve_task_2 = tokio::spawn(async move {
        louve_http_2
            .post(louve_url_2)
            .send()
            .await
            .expect("louve 2")
    });
    assert_sqlite_write_succeeds_while_http_in_flight(&state.db, &louve_in_flight, "louve").await;
    let (r1, r2) = tokio::join!(louve_task_1, louve_task_2);
    assert_eq!(r1.expect("join louve 1").status().as_u16(), 200);
    assert_eq!(r2.expect("join louve 2").status().as_u16(), 200);
    assert_eq!(
        louve_refresh_hits.load(Ordering::SeqCst),
        1,
        "per-member refresh mutex must serialize concurrent Louve refreshes to a single token rotation"
    );

    // --- Case 3: Real estate valuation (BAN geocoding + Cerema HTTP outside tx) ---
    let mut property = NewAccount::new(member_id, "Appartement", AccountType::RealEstate);
    property.is_manual = true;
    property.current_balance = Decimal::from(300_000);
    let prop_account = picsou::ledger::accounts::insert(&state.db, &property, clock.now())
        .await
        .expect("insert real estate account");
    db::query(
        "INSERT INTO real_estate_metadata \
         (account_id, member_id, purchase_price, property_type, address, postal_code, city, country, surface_area, valuation_mode) \
         VALUES ($1, $2, 300000, 'APARTMENT', '1 Rue de Rivoli', '75001', 'Paris', 'FR', 50, 'ESTIMATED')",
    )
    .bind(prop_account.id)
    .bind(member_id)
    .execute(&state.db)
    .await
    .expect("insert real estate metadata");

    let val_state = state.clone();
    let prop_id = prop_account.id;
    let val_task = tokio::spawn(async move {
        picsou::wealth::real_estate::valuation::estimate(&val_state, prop_id, member_id)
            .await
            .expect("valuation estimate")
    });
    assert_sqlite_write_succeeds_while_http_in_flight(&state.db, &geo_in_flight, "real_estate")
        .await;
    let _val_resp = val_task.await.expect("join valuation");
    // Verify geocoded insee_code was persisted cleanly in its own short tx!
    let saved_insee: Option<String> =
        db::query_scalar("SELECT insee_code FROM real_estate_metadata WHERE account_id = $1")
            .bind(prop_id)
            .fetch_one(&state.db)
            .await
            .expect("select insee_code");
    assert_eq!(saved_insee.as_deref(), Some("75101"));

    // --- Case 4: Manual add_transaction with ISIN (OpenFIGI HTTP outside tx) ---
    let mut pea = NewAccount::new(member_id, "PEA", AccountType::Pea);
    pea.is_manual = true;
    let pea_account = picsou::ledger::accounts::insert(&state.db, &pea, clock.now())
        .await
        .expect("insert pea account");

    let tx_http = http.clone();
    let tx_url = format!("{base_url}/api/accounts/{}/transactions", pea_account.id);
    let tx_task = tokio::spawn(async move {
        tx_http
            .post(tx_url)
            .json(&json!({
                "date": "2026-01-15",
                "description": "Buy TotalEnergies",
                "amount": "-550.00",
                "txType": "BUY",
                "ticker": "FR0000120271",
                "quantity": "10",
                "pricePerUnit": "55.00"
            }))
            .send()
            .await
            .expect("add_transaction")
    });
    assert_sqlite_write_succeeds_while_http_in_flight(&state.db, &openfigi_in_flight, "openfigi")
        .await;
    let tx_resp = tx_task.await.expect("join add_transaction");
    assert_eq!(tx_resp.status().as_u16(), 201);
    let tx_body: serde_json::Value = tx_resp.json().await.expect("tx json");
    assert_eq!(tx_body["ticker"], "TTE.PA");

    let _ = stop_tx.send(());
    state.db.close().await;
    let _ = std::fs::remove_dir_all(&dir);
}
