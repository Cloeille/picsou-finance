use std::collections::BTreeMap;
use std::str::FromStr;

use picsou::db::sqlite::SqliteDb;
use picsou::db::sqlite::decimal::{from_sqlite_text, to_sqlite_text};
use picsou::db::sqlite::export_pg::export_pg_to_sqlite;
use picsou::db::sqlite::migrate::{self, expected_schema_from_tsv, introspect_sqlite_schema};
use picsou::testing::{DB_HOST, DB_PORT, DB_USER, TestApp, fresh_database};
use rust_decimal::Decimal;
use sqlx::Row;
use uuid::Uuid;

fn temp_sqlite_path(tag: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!(
        "picsou_sqlite_test_{tag}_{}_{}.db",
        std::process::id(),
        Uuid::new_v4().simple()
    ))
}

fn cleanup_sqlite_path(path: &std::path::Path) {
    let _ = std::fs::remove_file(path);
    let mut wal = path.as_os_str().to_os_string();
    wal.push("-wal");
    let _ = std::fs::remove_file(&wal);
    let mut shm = path.as_os_str().to_os_string();
    shm.push("-shm");
    let _ = std::fs::remove_file(&shm);
}

#[test]
fn schema_parity_matches_postgresql_schema_tsv() {
    let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");

    // First migrate applies baseline; second migrate is a no-op (idempotent).
    assert_eq!(db.migrate().expect("initial migrate"), 1);
    assert_eq!(db.migrate().expect("idempotent migrate"), 0);

    let expected = expected_schema_from_tsv();
    assert!(
        !expected.is_empty(),
        "schema.tsv must contain application tables"
    );

    let actual = db
        .with_conn(|conn| Ok(introspect_sqlite_schema(conn)?))
        .expect("introspect sqlite schema");

    // 1. Exact table set parity (0 missing, 0 extra stray tables).
    let expected_tables: Vec<&String> = expected.keys().collect();
    let actual_tables: Vec<&String> = actual.keys().collect();
    assert_eq!(
        actual_tables, expected_tables,
        "SQLite tables must match PostgreSQL schema.tsv 1:1"
    );

    // 2. Exact column parity for every single table + zero REAL/FLOAT/DOUBLE columns.
    let mut total_columns = 0usize;
    for (table, expected_cols) in &expected {
        let actual_cols = &actual[table];
        let exp_names: Vec<&String> = expected_cols.keys().collect();
        let act_names: Vec<&String> = actual_cols.keys().collect();
        assert_eq!(
            act_names, exp_names,
            "SQLite columns for table [{table}] must match PostgreSQL schema.tsv 1:1"
        );
        for (col, decl_type) in actual_cols {
            let upper = decl_type.to_ascii_uppercase();
            assert!(
                !upper.contains("REAL") && !upper.contains("FLOAT") && !upper.contains("DOUBLE"),
                "table [{table}.{col}] must never use a binary float type, found [{decl_type}]"
            );
            if expected_cols[col] == "numeric" {
                assert_eq!(
                    upper, "TEXT",
                    "PostgreSQL NUMERIC column [{table}.{col}] must map to SQLite TEXT"
                );
            }
        }
        total_columns += expected_cols.len();
    }

    // 3. Built-in verifier & PRAGMA integrity checks pass.
    let report = db.verify_schema().expect("verify_schema");
    assert_eq!(report.tables_verified, expected.len());
    assert_eq!(report.columns_verified, total_columns);
    assert_eq!(report.migrations_applied, 1);

    let integrity = db.check_integrity().expect("check_integrity");
    assert!(integrity.integrity_ok, "{:?}", integrity.integrity_messages);
    assert!(integrity.foreign_key_violations.is_empty());

    // 4. Tampering with migration checksum fails validation cleanly.
    db.with_conn(|conn| {
        conn.execute(
            "UPDATE picsou_sqlite_migrations SET checksum = 'deadbeef' WHERE version = '1'",
            [],
        )?;
        Ok(())
    })
    .unwrap();
    let err = db.verify_schema().unwrap_err().to_string();
    assert!(err.contains("checksum mismatch"), "unexpected error: {err}");
}

#[tokio::test]
async fn exact_decimal_precision_and_aggregate_functions() {
    let db = SqliteDb::open_in_memory().expect("open in-memory SQLite");
    db.migrate().expect("migrate");

    // Verify async interact works on Tokio blocking pool and PRAGMA foreign_keys = 1.
    let fk_on: i64 = db
        .interact(|conn| {
            let v: i64 = conn.pragma_query_value(None, "foreign_keys", |r| r.get(0))?;
            Ok(v)
        })
        .await
        .expect("pragma foreign_keys");
    assert_eq!(fk_on, 1);

    db.with_conn(|conn| {
        // Classic IEEE-754 trap: 0.1 + 0.2 MUST equal exact "0.3".
        let sum: String = conn.query_row("SELECT dec_add('0.1', '0.2')", [], |r| r.get(0))?;
        assert_eq!(sum, "0.3");
        assert_eq!(
            from_sqlite_text(&sum).unwrap(),
            Decimal::from_str("0.3").unwrap()
        );

        // 19,8 precision & trailing-zero scale preservation.
        let scale8 = Decimal::from_str("99999999999.99999999").unwrap();
        let encoded = to_sqlite_text(scale8);
        assert_eq!(encoded, "99999999999.99999999");
        let round_trip = from_sqlite_text(&encoded).unwrap();
        assert_eq!(round_trip, scale8);
        assert_eq!(round_trip.scale(), 8);

        let large_sub: String = conn.query_row(
            "SELECT dec_sub('99999999999.99999999', '0.00000001')",
            [],
            |r| r.get(0),
        )?;
        assert_eq!(large_sub, "99999999999.99999998");

        let neg_mul: String =
            conn.query_row("SELECT dec_mul('-12345678901.12345678', '2')", [], |r| {
                r.get(0)
            })?;
        assert_eq!(neg_mul, "-24691357802.24691356");

        let div_rounded: String =
            conn.query_row("SELECT dec_div('1', '3', 8)", [], |r| r.get(0))?;
        assert_eq!(div_rounded, "0.33333333");

        let abs_val: String =
            conn.query_row("SELECT dec_abs('-99999999999.99999999')", [], |r| r.get(0))?;
        assert_eq!(abs_val, "99999999999.99999999");

        let neg_val: String = conn.query_row("SELECT dec_neg('-0.00000001')", [], |r| r.get(0))?;
        assert_eq!(neg_val, "0.00000001");

        // Exact dec_sum aggregate over 1,000 rows (positive, negative, 8-dp fractions, plus NULLs).
        conn.execute_batch("CREATE TEMP TABLE dec_bench (id INTEGER PRIMARY KEY, amt TEXT);")?;
        let mut expected_sum = Decimal::ZERO;
        {
            let mut stmt = conn.prepare("INSERT INTO dec_bench (id, amt) VALUES (?1, ?2)")?;
            for i in 1..=1_000i64 {
                if i % 100 == 0 {
                    stmt.execute(rusqlite::params![i, Option::<String>::None])?;
                    continue;
                }
                // Repeating decimal-unfriendly step 0.00000007 * i + alternating signs.
                let base = Decimal::new(10_000_000_007 + i * 7, 8);
                let val = if i % 3 == 0 { -base } else { base };
                expected_sum += val;
                stmt.execute(rusqlite::params![i, to_sqlite_text(val)])?;
            }
        }
        let sql_sum: String =
            conn.query_row("SELECT dec_sum(amt) FROM dec_bench", [], |r| r.get(0))?;
        let parsed_sum = from_sqlite_text(&sql_sum).unwrap();
        assert_eq!(parsed_sum, expected_sum);
        assert_eq!(sql_sum, to_sqlite_text(expected_sum));

        // Empty aggregate returns NULL (matching PostgreSQL SUM).
        let empty_sum: Option<String> =
            conn.query_row("SELECT dec_sum(amt) FROM dec_bench WHERE id < 0", [], |r| {
                r.get(0)
            })?;
        assert_eq!(empty_sum, None);

        // Passing SQLite REAL float into decimal functions MUST fail.
        assert!(
            conn.query_row("SELECT dec_add(0.1, '0.2')", [], |r| r.get::<_, String>(0))
                .is_err()
        );

        // Verify schema CHECK constraints using dec_add / dec_cmp work with exact decimal precision.
        // 14.1 + 14.2 + 14.3 + 57.4 == 100 exactly in Decimal (whereas binary float64 gives 100.00000000000001).
        conn.execute(
            "INSERT INTO family_member (id, display_name) VALUES (1, 'Alice')",
            [],
        )?;
        conn.execute(
            "INSERT INTO member_allocation_profile \
             (member_id, real_estate_pct, equity_pct, crypto_pct, alternative_pct) \
             VALUES (1, '14.10', '14.20', '14.30', '57.40')",
            [],
        )?;
        // Sum != 100 MUST be rejected by ck_member_allocation_profile_sum.
        let bad_sum = conn.execute(
            "INSERT INTO member_allocation_profile \
             (member_id, real_estate_pct, equity_pct, crypto_pct, alternative_pct) \
             VALUES (1, '14.10', '14.20', '14.30', '57.41')",
            [],
        );
        assert!(
            bad_sum.is_err(),
            "CHECK constraint must reject allocation sum != 100"
        );

        Ok(())
    })
    .unwrap();
}

#[tokio::test]
async fn live_pg_to_sqlite_round_trip_export() {
    let app = TestApp::with_admin().await;
    let pg = &app.state.db;

    // Seed rich multi-table data across members, accounts, holdings, transactions, budgets,
    // categories, goals, peer loans, snapshots, profiles, binary logos, sessions, and settings.
    let (_user2_id, member2_id) = app.create_member("claire", "strong-pass-123").await;
    let managed_id = app.create_managed_member("Leo").await;

    let admin_member_id: i64 =
        sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
            .fetch_one(pg)
            .await
            .unwrap();
    let admin_user_id: i64 = sqlx::query_scalar("SELECT id FROM app_user WHERE username = 'admin'")
        .fetch_one(pg)
        .await
        .unwrap();

    // Accounts (checking, PEA, savings, peer loan)
    let checking_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (name, type, currency, current_balance, cash_balance, is_manual, member_id) \
         VALUES ('Compte Courant', 'CHECKING', 'EUR', 12345.67, 12345.67, true, $1) RETURNING id",
    )
    .bind(admin_member_id)
    .fetch_one(pg)
    .await
    .unwrap();

    let pea_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (name, type, currency, current_balance, cash_balance, is_manual, member_id) \
         VALUES ('PEA Bourse', 'PEA', 'EUR', 99999999999.99999999, 500.25000000, true, $1) RETURNING id",
    )
    .bind(admin_member_id)
    .fetch_one(pg)
    .await
    .unwrap();

    let peer_account_id: i64 = sqlx::query_scalar(
        "INSERT INTO account (name, type, currency, current_balance, is_manual, member_id) \
         VALUES ('Pret Ami', 'PEER_LOAN', 'EUR', 1500.00000000, true, $1) RETURNING id",
    )
    .bind(member2_id)
    .fetch_one(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO account_display_pref (member_id, account_id, list_position, list_collapsed) \
         VALUES ($1, $2, 1, false)",
    )
    .bind(admin_member_id)
    .bind(checking_id)
    .execute(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO account_ownership (account_id, member_id, share_percent) VALUES ($1, $2, 60.00), ($1, $3, 40.00)",
    )
    .bind(checking_id)
    .bind(admin_member_id)
    .bind(managed_id)
    .execute(pg)
    .await
    .unwrap();

    // Holdings (high precision 8-dp quantity and price)
    sqlx::query(
        "INSERT INTO account_holding (account_id, ticker, name, quantity, current_price, average_buy_in, quote_currency) \
         VALUES ($1, 'CW8.PA', 'Amundi MSCI World', 123.45678901, 512.34567890, 480.12345678, 'EUR')",
    )
    .bind(pea_id)
    .execute(pg)
    .await
    .unwrap();

    // Categories, rules, budget, budget_settings
    let cat_id: i64 = sqlx::query_scalar(
        "INSERT INTO category (member_id, name, slug, kind, color, icon) \
         VALUES ($1, 'Alimentation', 'groceries-custom', 'EXPENSE', '#22c55e', 'shopping-cart') RETURNING id",
    )
    .bind(admin_member_id)
    .fetch_one(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO categorization_rule (member_id, category_id, pattern, match_type, priority, source) \
         VALUES ($1, $2, 'BIOCOOP', 'CONTAINS', 10, 'USER')",
    )
    .bind(admin_member_id)
    .bind(cat_id)
    .execute(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO budget (member_id, category_id, monthly_limit) VALUES ($1, $2, 650.50)",
    )
    .bind(admin_member_id)
    .bind(cat_id)
    .execute(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO budget_settings (member_id, cycle_start_day, ai_categorization_enabled) \
         VALUES ($1, 1, false) ON CONFLICT (member_id) DO NOTHING",
    )
    .bind(admin_member_id)
    .execute(pg)
    .await
    .unwrap();

    // Transactions with exact decimals (including 0.1 + 0.2 trap values)
    let tx_lending_id: i64 = sqlx::query_scalar(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, fees, quantity, price_per_unit, category_id, is_manual) \
         VALUES ($1, DATE '2026-01-10', 'Courses Biocoop', -0.10, 0.00, NULL, NULL, $2, true) RETURNING id",
    )
    .bind(checking_id)
    .bind(cat_id)
    .fetch_one(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO \"transaction\" (account_id, date, description, amount, fees, quantity, price_per_unit, is_manual) \
         VALUES ($1, DATE '2026-01-11', 'Achat ETF', -0.20, 1.99, 0.12345678, 512.34567890, true)",
    )
    .bind(pea_id)
    .execute(pg)
    .await
    .unwrap();

    // Goals + allocations + contributors + manual contribution
    let goal_id: i64 = sqlx::query_scalar(
        "INSERT INTO goal (name, target_amount, deadline, member_id, type) \
         VALUES ('Apport Immo', 50000.00, DATE '2028-06-01', $1, 'SAVINGS_TARGET') RETURNING id",
    )
    .bind(admin_member_id)
    .fetch_one(pg)
    .await
    .unwrap();

    sqlx::query("INSERT INTO goal_account (goal_id, account_id) VALUES ($1, $2)")
        .bind(goal_id)
        .bind(pea_id)
        .execute(pg)
        .await
        .unwrap();

    sqlx::query("INSERT INTO goal_contributor (goal_id, member_id) VALUES ($1, $2)")
        .bind(goal_id)
        .bind(admin_member_id)
        .execute(pg)
        .await
        .unwrap();

    sqlx::query(
        "INSERT INTO goal_manual_contribution (goal_id, member_id, year_month, amount) \
         VALUES ($1, $2, '2026-01', 1250.75)",
    )
    .bind(goal_id)
    .bind(admin_member_id)
    .execute(pg)
    .await
    .unwrap();

    // Peer loan counterparty, terms, repayment
    let counterparty_id: i64 = sqlx::query_scalar(
        "INSERT INTO peer_counterparty (member_id, name, note) VALUES ($1, 'Marc', 'Ami') RETURNING id",
    )
    .bind(member2_id)
    .fetch_one(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO peer_loan_terms (account_id, counterparty_id, principal, lent_at, annual_rate, rate_period, lending_transaction_id) \
         VALUES ($1, $2, 2000.00000000, DATE '2025-12-01', 2.5000, 'ANNUAL', $3)",
    )
    .bind(peer_account_id)
    .bind(counterparty_id)
    .bind(tx_lending_id)
    .execute(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO peer_loan_repayment (account_id, paid_on, amount, kind) \
         VALUES ($1, DATE '2026-01-05', 500.00000000, 'PRINCIPAL')",
    )
    .bind(peer_account_id)
    .execute(pg)
    .await
    .unwrap();

    // Balance & price snapshots
    sqlx::query(
        "INSERT INTO balance_snapshot (account_id, date, balance, native_balance, invested_amount, source) \
         VALUES ($1, DATE '2026-01-15', 12345.67, 12345.67, 10000.00, 'DERIVED')",
    )
    .bind(checking_id)
    .execute(pg)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('CW8.PA', DATE '2026-01-15', 512.34567890)",
    )
    .execute(pg)
    .await
    .unwrap();

    // Member allocation profile (exact decimal sum = 100)
    sqlx::query(
        "INSERT INTO member_allocation_profile (member_id, real_estate_pct, equity_pct, crypto_pct, alternative_pct, safety_net_months) \
         VALUES ($1, 14.10, 14.20, 14.30, 57.40, 6)",
    )
    .bind(admin_member_id)
    .execute(pg)
    .await
    .unwrap();

    // Binary bytea payload in instrument_logo
    let png_bytes: Vec<u8> = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4];
    sqlx::query(
        "INSERT INTO instrument_logo (ticker, status, image, content_type, fetched_at, attempted_at) \
         VALUES ('CW8.PA', 'STORED', $1, 'image/png', NOW(), NOW())",
    )
    .bind(&png_bytes)
    .execute(pg)
    .await
    .unwrap();

    // UUID payload in persistent_session
    let series_uuid = Uuid::parse_str("11111111-2222-4333-8444-555555555555").unwrap();
    sqlx::query(
        "INSERT INTO persistent_session (user_id, series_id, token_hash, expires_at, trusted_for_2fa) \
         VALUES ($1, $2, 'abc123hash', NOW(), true)",
    )
    .bind(admin_user_id)
    .bind(series_uuid)
    .execute(pg)
    .await
    .unwrap();

    // Custom app setting
    sqlx::query(
        "INSERT INTO app_setting (setting_key, value) VALUES ('sqlite_roundtrip_key', 'exact-value') \
         ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
    )
    .execute(pg)
    .await
    .unwrap();

    // Run live PostgreSQL -> SQLite export.
    let sqlite_path = temp_sqlite_path("roundtrip");
    cleanup_sqlite_path(&sqlite_path);

    let report = export_pg_to_sqlite(pg, &sqlite_path)
        .await
        .expect("export_pg_to_sqlite");

    let expected_schema = expected_schema_from_tsv();
    assert_eq!(report.tables_exported, expected_schema.len());
    assert!(report.integrity_ok);
    assert_eq!(report.foreign_key_violations, 0);
    assert!(report.total_rows > 0);

    // Re-open the exported SQLite database and verify every single invariant independently.
    let sqlite = SqliteDb::open(&sqlite_path).expect("open exported SQLite");
    let integrity = sqlite.check_integrity().expect("integrity check");
    assert!(integrity.integrity_ok, "{:?}", integrity.integrity_messages);
    assert!(integrity.foreign_key_violations.is_empty());

    let verified = sqlite.verify_schema().expect("verify exported schema");
    assert_eq!(verified.tables_verified, expected_schema.len());

    // 1. Assert 1:1 row count across EVERY SINGLE ONE of the 67 tables.
    let mut pg_counts = BTreeMap::new();
    for table in expected_schema.keys() {
        let escaped = format!("\"{}\"", table.replace('"', "\"\""));
        let count: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM \"public\".{escaped}"))
            .fetch_one(pg)
            .await
            .unwrap();
        pg_counts.insert(table.clone(), u64::try_from(count).unwrap());
    }
    sqlite
        .with_conn(|conn| {
            for (table, pg_count) in &pg_counts {
                let escaped = format!("\"{}\"", table.replace('"', "\"\""));
                let sq_count: i64 =
                    conn.query_row(&format!("SELECT COUNT(*) FROM {escaped}"), [], |r| r.get(0))?;
                assert_eq!(
                    u64::try_from(sq_count).unwrap(),
                    *pg_count,
                    "row count mismatch on table [{table}]"
                );
            }

            // 2. Verify exact Decimal round-trip across accounts, holdings, transactions, budgets,
            //    goals, peer loans, snapshots, and allocation profiles.
            let pea_bal: String = conn.query_row(
                "SELECT current_balance FROM account WHERE id = ?1",
                [pea_id],
                |r| r.get(0),
            )?;
            assert_eq!(pea_bal, "99999999999.99999999");

            let (qty, price, avg): (String, String, String) = conn.query_row(
                "SELECT quantity, current_price, average_buy_in FROM account_holding WHERE account_id = ?1",
                [pea_id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )?;
            assert_eq!(qty, "123.45678901");
            assert_eq!(price, "512.34567890");
            assert_eq!(avg, "480.12345678");

            // Exact transaction sum via SQLite dec_sum == -0.30 (zero float drift!).
            let tx_sum: String =
                conn.query_row("SELECT dec_sum(amount) FROM \"transaction\"", [], |r| r.get(0))?;
            assert_eq!(
                from_sqlite_text(&tx_sum).unwrap(),
                Decimal::from_str("-0.30").unwrap()
            );

            let budget_limit: String = conn.query_row(
                "SELECT monthly_limit FROM budget WHERE category_id = ?1",
                [cat_id],
                |r| r.get(0),
            )?;
            assert_eq!(
                from_sqlite_text(&budget_limit).unwrap(),
                Decimal::from_str("650.50").unwrap()
            );

            let principal: String = conn.query_row(
                "SELECT principal FROM peer_loan_terms WHERE account_id = ?1",
                [peer_account_id],
                |r| r.get(0),
            )?;
            assert_eq!(
                from_sqlite_text(&principal).unwrap(),
                Decimal::from_str("2000.00000000").unwrap()
            );

            // 3. Verify BLOB (bytea), UUID, and app_setting round-trip cleanly.
            let blob: Vec<u8> = conn.query_row(
                "SELECT image FROM instrument_logo WHERE ticker = 'CW8.PA'",
                [],
                |r| r.get(0),
            )?;
            assert_eq!(blob, png_bytes);

            let uuid_str: String = conn.query_row(
                "SELECT series_id FROM persistent_session WHERE user_id = ?1",
                [admin_user_id],
                |r| r.get(0),
            )?;
            assert_eq!(uuid_str, series_uuid.hyphenated().to_string());

            let setting_val: String = conn.query_row(
                "SELECT value FROM app_setting WHERE setting_key = 'sqlite_roundtrip_key'",
                [],
                |r| r.get(0),
            )?;
            assert_eq!(setting_val, "exact-value");

            Ok(())
        })
        .unwrap();

    // Cross-check PostgreSQL SUM(amount) directly against SQLite dec_sum(amount).
    let pg_tx_sum: String =
        sqlx::query("SELECT SUM(amount)::text AS s FROM \"public\".\"transaction\"")
            .fetch_one(pg)
            .await
            .unwrap()
            .get("s");
    let sq_tx_sum: String = sqlite
        .with_conn(|conn| {
            Ok(
                conn.query_row("SELECT dec_sum(amount) FROM \"transaction\"", [], |r| {
                    r.get(0)
                })?,
            )
        })
        .unwrap();
    assert_eq!(
        from_sqlite_text(&sq_tx_sum).unwrap(),
        from_sqlite_text(&pg_tx_sum).unwrap()
    );

    drop(sqlite);
    cleanup_sqlite_path(&sqlite_path);
}

#[tokio::test]
async fn cli_export_sqlite_subcommand_works() {
    let db = fresh_database().await;
    let out_path = temp_sqlite_path("cli_export");
    cleanup_sqlite_path(&out_path);

    let output = std::process::Command::new(env!("CARGO_BIN_EXE_picsou"))
        .arg("export-sqlite")
        .arg(&out_path)
        .env_clear()
        .env(
            "SPRING_DATASOURCE_URL",
            format!("jdbc:postgresql://{DB_HOST}:{DB_PORT}/{}", db.name()),
        )
        .env("SPRING_DATASOURCE_USERNAME", DB_USER)
        .env("SPRING_DATASOURCE_PASSWORD", "trust-ignores-it")
        .output()
        .expect("run picsou export-sqlite");

    assert!(
        output.status.success(),
        "export-sqlite failed: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("integrity_ok=true"), "{stdout}");
    assert!(stdout.contains("fk_violations=0"), "{stdout}");

    // Exported database passes schema verification and integrity checks.
    let sqlite = SqliteDb::open(&out_path).expect("open CLI-exported SQLite");
    let verified = sqlite.verify_schema().expect("verify CLI-exported SQLite");
    assert_eq!(
        verified.tables_verified,
        migrate::expected_schema_from_tsv().len()
    );
    let integrity = sqlite.check_integrity().expect("integrity check");
    assert!(integrity.integrity_ok);
    assert!(integrity.foreign_key_violations.is_empty());

    // Running again against an existing file must refuse to clobber it.
    let clobber = std::process::Command::new(env!("CARGO_BIN_EXE_picsou"))
        .arg("export-sqlite")
        .arg(&out_path)
        .env_clear()
        .env(
            "SPRING_DATASOURCE_URL",
            format!("jdbc:postgresql://{DB_HOST}:{DB_PORT}/{}", db.name()),
        )
        .env("SPRING_DATASOURCE_USERNAME", DB_USER)
        .env("SPRING_DATASOURCE_PASSWORD", "trust-ignores-it")
        .output()
        .expect("run picsou export-sqlite second time");
    assert_eq!(clobber.status.code(), Some(1));
    assert!(
        String::from_utf8_lossy(&clobber.stderr).contains("already exists"),
        "{}",
        String::from_utf8_lossy(&clobber.stderr)
    );

    // Missing output argument must exit 1 with usage message.
    let missing_arg = std::process::Command::new(env!("CARGO_BIN_EXE_picsou"))
        .arg("export-sqlite")
        .env_clear()
        .output()
        .expect("run picsou export-sqlite without arg");
    assert_eq!(missing_arg.status.code(), Some(1));
    assert!(String::from_utf8_lossy(&missing_arg.stderr).contains("Usage: picsou export-sqlite"));

    drop(sqlite);
    cleanup_sqlite_path(&out_path);
}
