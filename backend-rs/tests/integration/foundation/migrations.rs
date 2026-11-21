use picsou::db::flyway;
use picsou::testing::{DB_USER, TestDatabase, fresh_database};
use sqlx::{Connection, PgConnection, Row};

/// flyway_schema_history of an empty database migrated by the Java 1.1.0 jar (Flyway 11).
const JAVA_HISTORY: &str = include_str!("../fixtures/java_flyway_history.tsv");

async fn connect(database: &TestDatabase) -> PgConnection {
    PgConnection::connect(&database.url()).await.unwrap()
}

async fn history(conn: &mut PgConnection) -> String {
    let rows = sqlx::query(
        "SELECT installed_rank, version, description, type, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank",
    )
    .fetch_all(conn)
    .await
    .unwrap();
    rows.iter()
        .map(|r| {
            format!(
                "{}\t{}\t{}\t{}\t{}\t{}\t{}",
                r.get::<i32, _>(0),
                r.get::<Option<String>, _>(1).unwrap_or_default(),
                r.get::<String, _>(2),
                r.get::<String, _>(3),
                r.get::<String, _>(4),
                r.get::<Option<i32>, _>(5)
                    .map(|c| c.to_string())
                    .unwrap_or_default(),
                if r.get::<bool, _>(6) { "t" } else { "f" }
            )
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[tokio::test]
async fn writes_the_history_flyway_writes() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    assert_eq!(history(&mut conn).await.trim(), JAVA_HISTORY.trim());
    let installed_by: String =
        sqlx::query_scalar("SELECT DISTINCT installed_by FROM flyway_schema_history")
            .fetch_one(&mut conn)
            .await
            .unwrap();
    assert_eq!(installed_by, DB_USER);
}

#[tokio::test]
async fn a_migrated_database_needs_nothing() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    assert_eq!(flyway::migrate(&mut conn).await.unwrap(), 0);
}

#[tokio::test]
async fn pending_lists_the_scripts_not_yet_applied() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    assert!(flyway::pending(&mut conn).await.unwrap().is_empty());

    let last: String = sqlx::query_scalar(
        "DELETE FROM flyway_schema_history WHERE installed_rank = (SELECT max(installed_rank) FROM flyway_schema_history) RETURNING script",
    )
    .fetch_one(&mut conn)
    .await
    .unwrap();
    assert_eq!(flyway::pending(&mut conn).await.unwrap(), [last]);

    sqlx::query("DROP TABLE flyway_schema_history")
        .execute(&mut conn)
        .await
        .unwrap();
    assert_eq!(
        flyway::pending(&mut conn).await.unwrap().len(),
        flyway::migrations().unwrap().len()
    );
}

fn release_cli(command: &str, database: &TestDatabase) -> std::process::Output {
    std::process::Command::new(env!("CARGO_BIN_EXE_picsou"))
        .arg(command)
        .env_clear()
        .env(
            "SPRING_DATASOURCE_URL",
            format!("jdbc:postgresql://127.0.0.1:55432/{}", database.name()),
        )
        .env("SPRING_DATASOURCE_USERNAME", DB_USER)
        .env("SPRING_DATASOURCE_PASSWORD", "trust-ignores-it")
        .output()
        .unwrap()
}

#[tokio::test]
async fn the_release_cli_migrates_and_verifies_like_the_java_one() {
    let db = fresh_database().await;
    let migrate = release_cli("migrate", &db);
    assert!(migrate.status.success(), "{migrate:?}");
    let verify = release_cli("verify", &db);
    assert!(verify.status.success(), "{verify:?}");

    let mut conn = connect(&db).await;
    let last: String = sqlx::query_scalar(
        "DELETE FROM flyway_schema_history WHERE installed_rank = (SELECT max(installed_rank) FROM flyway_schema_history) RETURNING script",
    )
    .fetch_one(&mut conn)
    .await
    .unwrap();
    let incomplete = release_cli("verify", &db);
    let stderr = String::from_utf8_lossy(&incomplete.stderr);
    assert_eq!(incomplete.status.code(), Some(1));
    assert!(
        stderr.contains("Migration inventory is incomplete") && stderr.contains(&last),
        "{stderr}"
    );

    let unknown = release_cli("rollback", &db);
    assert_eq!(unknown.status.code(), Some(1));
    let missing = std::process::Command::new(env!("CARGO_BIN_EXE_picsou"))
        .arg("verify")
        .env_clear()
        .output()
        .unwrap();
    assert_eq!(missing.status.code(), Some(1));
    assert!(String::from_utf8_lossy(&missing.stderr).contains("SPRING_DATASOURCE_URL"));
}

#[tokio::test]
async fn an_applied_version_this_binary_lacks_fails() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    sqlx::query(
        "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) \
         VALUES (1000, '9999', 'from the future', 'SQL', 'V9999__from_the_future.sql', 1, 'picsou', 1, true)",
    )
    .execute(&mut conn)
    .await
    .unwrap();
    let error = flyway::migrate(&mut conn).await.unwrap_err().to_string();
    assert!(
        error.contains("Detected applied migration not resolved locally: 9999"),
        "{error}"
    );
}

#[tokio::test]
async fn a_changed_checksum_fails() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    sqlx::query("UPDATE flyway_schema_history SET checksum = 42 WHERE version = '2'")
        .execute(&mut conn)
        .await
        .unwrap();
    let error = flyway::migrate(&mut conn).await.unwrap_err().to_string();
    assert!(
        error.contains("Migration checksum mismatch for migration version 2"),
        "{error}"
    );
}

#[tokio::test]
async fn pending_lower_versions_apply_out_of_order() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    let max_rank: i32 = sqlx::query_scalar("SELECT max(installed_rank) FROM flyway_schema_history")
        .fetch_one(&mut conn)
        .await
        .unwrap();
    sqlx::query("DELETE FROM flyway_schema_history WHERE version = '154'")
        .execute(&mut conn)
        .await
        .unwrap();
    assert_eq!(flyway::migrate(&mut conn).await.unwrap(), 1);
    let rank: i32 = sqlx::query_scalar(
        "SELECT installed_rank FROM flyway_schema_history WHERE version = '154'",
    )
    .fetch_one(&mut conn)
    .await
    .unwrap();
    assert_eq!(rank, max_rank + 1);
}

#[tokio::test]
async fn renumbers_legacy_history_rows() {
    let db = fresh_database().await;
    let mut conn = connect(&db).await;
    let rank: i32 =
        sqlx::query_scalar("SELECT installed_rank FROM flyway_schema_history WHERE version = '93'")
            .fetch_one(&mut conn)
            .await
            .unwrap();
    // The 1.1.0 branch recorded today's V93 as V80 before commit 2839c94 renamed it.
    sqlx::query("UPDATE flyway_schema_history SET version = '80', script = 'V80__fortuneo_session.sql' WHERE installed_rank = $1")
        .bind(rank)
        .execute(&mut conn)
        .await
        .unwrap();
    assert_eq!(flyway::migrate(&mut conn).await.unwrap(), 0);
    let script: String =
        sqlx::query_scalar("SELECT script FROM flyway_schema_history WHERE installed_rank = $1")
            .bind(rank)
            .fetch_one(&mut conn)
            .await
            .unwrap();
    assert_eq!(script, "V93__fortuneo_session.sql");
}
