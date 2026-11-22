//! Side-by-side dual-engine integration tests verifying that the exact same generic repository
//! functions execute identically on `Db::Postgres` and `Db::Sqlite` (both in-memory and WAL
//! file-backed), and that fresh SQLite seed data matches Flyway-migrated PostgreSQL 1:1.

use std::collections::{BTreeMap, BTreeSet};
use std::str::FromStr;

use chrono::{DateTime, NaiveDate, Utc};
use picsou::config::{Config, Env};
use picsou::db::sqlite::SqliteDb;
use picsou::db::sqlite::seed;
use picsou::db::{self, Db, DbEnum, DbExec, DbFromRow};
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
