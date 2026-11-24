//! `picsou migrate`, `picsou verify`, and `picsou export-sqlite <output.db>`: database
//! maintenance subcommands. `migrate` and `verify` mirror the Java ReleaseMigrationCli used by
//! `docker/deploy.sh` and the Helm pre-upgrade job; `export-sqlite` exports a live PostgreSQL
//! database into a verified single-file SQLite database.

use std::path::Path;

use sqlx::{Connection, PgConnection};

use crate::config::{DatabaseConfig, Datasource, Env, SqliteTarget};
use crate::db::sqlite::SqliteDb;
use crate::db::sqlite::export_pg::{self, ExportError};
use crate::db::{connect, connect_options, flyway, schema};

const DEFAULT_DATASOURCE_USERNAME: &str = "picsou";
const DEFAULT_DATASOURCE_PASSWORD: &str = "picsou";

/// Runs `migrate` or `verify` against the configured database; returns the exit code.
pub async fn run(command: &str, env: &Env) -> i32 {
    run_with_args(command, &[], env).await
}

/// Runs `migrate`, `verify`, or `export-sqlite <output.db>` against the same database
/// configuration the server reads (`DATABASE_URL` or `SPRING_DATASOURCE_URL`,
/// `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` / `POSTGRES_PASSWORD` /
/// `PICSOU_SECRETS_DIR/postgres_password`); returns the exit code.
///
/// Only messages built from migration names, table names, and SQLSTATE codes are printed: a driver
/// error can carry the host, the user or SQL text.
pub async fn run_with_args(command: &str, args: &[String], env: &Env) -> i32 {
    if command != "migrate" && command != "verify" && command != "export-sqlite" {
        eprintln!("Command must be migrate, verify, or export-sqlite.");
        return 1;
    }
    if command == "export-sqlite" && args.len() != 1 {
        eprintln!("Usage: picsou export-sqlite <output.db>");
        return 1;
    }
    let database = match resolve_database(env) {
        Ok(database) => database,
        Err(message) => {
            eprintln!("Database command failed; database state was not accepted.");
            eprintln!("Command: {command}");
            eprintln!("Reason: {message}");
            return 1;
        }
    };
    if command == "export-sqlite" {
        return match database {
            DatabaseConfig::Postgres(datasource) => {
                run_export_sqlite(&datasource, Path::new(&args[0])).await
            }
            DatabaseConfig::Sqlite(_) => {
                eprintln!("SQLite export failed.");
                eprintln!("Reason: export-sqlite requires a PostgreSQL database URL.");
                1
            }
        };
    }
    let outcome = match database {
        DatabaseConfig::Postgres(datasource) => apply(command, &datasource).await,
        DatabaseConfig::Sqlite(target) => apply_sqlite(command, &target).await,
    };
    match outcome {
        Ok(()) => 0,
        Err(lines) => {
            eprintln!("Migration command failed; database state was not accepted.");
            eprintln!("Command: {command}");
            for line in lines {
                eprintln!("{line}");
            }
            1
        }
    }
}

fn resolve_database(env: &Env) -> Result<DatabaseConfig, String> {
    let var = |key: &str| env.get(key).map(str::trim).filter(|v| !v.is_empty());
    let Some(raw_url) = var("DATABASE_URL").or_else(|| var("SPRING_DATASOURCE_URL")) else {
        return Err("DATABASE_URL or SPRING_DATASOURCE_URL is required.".to_owned());
    };
    let username = var("SPRING_DATASOURCE_USERNAME").unwrap_or(DEFAULT_DATASOURCE_USERNAME);
    let password = resolve_datasource_password(env)?;
    DatabaseConfig::parse(raw_url, username, &password).map_err(|e| e.to_string())
}

/// Resolves the datasource password with the same precedence as `crypto::secrets::bootstrap`,
/// without creating `PICSOU_SECRETS_DIR` or generating server secrets.
fn resolve_datasource_password(env: &Env) -> Result<String, String> {
    let non_empty = |key: &str| env.raw(key).filter(|v| !v.is_empty());
    if let Some(password) = non_empty("SPRING_DATASOURCE_PASSWORD") {
        return Ok(password.to_owned());
    }
    if let Some(password) = non_empty("POSTGRES_PASSWORD") {
        return Ok(password.to_owned());
    }
    if let Some(dir) = non_empty("PICSOU_SECRETS_DIR") {
        let file = Path::new(dir).join("postgres_password");
        if file.exists() {
            let content = std::fs::read_to_string(&file).map_err(|_| {
                format!(
                    "Cannot read secret file {} for POSTGRES_PASSWORD",
                    file.display()
                )
            })?;
            let trimmed = content.trim_end_matches('\n');
            if trimmed.is_empty() {
                return Err(format!(
                    "Secret file {} is empty: refusing to use a blank POSTGRES_PASSWORD",
                    file.display()
                ));
            }
            return Ok(trimmed.to_owned());
        }
    }
    Ok(DEFAULT_DATASOURCE_PASSWORD.to_owned())
}

async fn apply_sqlite(command: &str, target: &SqliteTarget) -> Result<(), Vec<String>> {
    let path = match target {
        SqliteTarget::File(path) => path.clone(),
        SqliteTarget::InMemory => {
            return Err(vec![
                "Reason: SQLite maintenance commands require a file-backed database URL".to_owned(),
            ]);
        }
    };
    if command == "verify" && !path.exists() {
        return Err(vec![
            "Reason: SQLite database file does not exist".to_owned(),
        ]);
    }
    let command = command.to_owned();
    let sq = tokio::task::spawn_blocking(move || SqliteDb::open(&path))
        .await
        .map_err(|e| vec![format!("Reason: blocking SQLite open failed: {e}")])?
        .map_err(|e| vec![format!("Reason: {e}")])?;
    let sq_for_task = sq.clone();
    let outcome = tokio::task::spawn_blocking(move || -> Result<(), Vec<String>> {
        if command == "migrate" {
            sq_for_task
                .migrate()
                .map_err(|e| vec![format!("Reason: {e}")])?;
        }
        sq_for_task
            .verify_schema()
            .map_err(|e| vec![format!("Schema validation: {e}")])?;
        Ok(())
    })
    .await
    .map_err(|e| vec![format!("Reason: blocking SQLite task failed: {e}")])?;
    sq.close().await;
    outcome
}

async fn run_export_sqlite(datasource: &Datasource, output: &Path) -> i32 {
    let pool = match connect(datasource).await {
        Ok(pool) => pool,
        Err(e) => {
            eprintln!("SQLite export failed.");
            for line in diagnose_sql(&e, "the database is unreachable") {
                eprintln!("{line}");
            }
            return 1;
        }
    };
    let outcome = export_pg::export_pg_to_sqlite(&pool, output).await;
    pool.close().await;
    match outcome {
        Ok(report) => {
            println!(
                "Exported {} tables ({} rows) to {} (integrity_ok={}, fk_violations={}).",
                report.tables_exported,
                report.total_rows,
                output.display(),
                report.integrity_ok,
                report.foreign_key_violations,
            );
            0
        }
        Err(err) => {
            eprintln!("SQLite export failed.");
            for line in diagnose_export(&err) {
                eprintln!("{line}");
            }
            1
        }
    }
}

fn diagnose_export(error: &ExportError) -> Vec<String> {
    match error {
        ExportError::Postgres { table, source } => diagnose_sql(
            source,
            &format!("PostgreSQL read failed on table [{table}]"),
        ),
        ExportError::OutputExists(_)
        | ExportError::Io(_)
        | ExportError::Sqlite(_)
        | ExportError::Rusqlite(_)
        | ExportError::Migration(_)
        | ExportError::InvalidDecimal { .. }
        | ExportError::UnsupportedType { .. }
        | ExportError::RowCountMismatch { .. }
        | ExportError::ForeignKeyViolations(_)
        | ExportError::IntegrityFailed(_) => vec![format!("Reason: {error}")],
    }
}

async fn apply(command: &str, datasource: &Datasource) -> Result<(), Vec<String>> {
    let mut conn = PgConnection::connect_with(&connect_options(datasource))
        .await
        .map_err(|e| diagnose_sql(&e, "the database is unreachable"))?;
    let outcome = if command == "migrate" {
        flyway::migrate(&mut conn).await.map(|_| ())
    } else {
        flyway::verify(&mut conn).await
    };
    let outcome = match outcome {
        Ok(()) => schema::check(&mut conn).await,
        Err(e) => Err(e),
    };
    let _ = conn.close().await;
    outcome.map_err(|e| diagnose(&e))
}

fn diagnose(error: &flyway::MigrationError) -> Vec<String> {
    use flyway::MigrationError::*;
    match error {
        Failed { script, source } => {
            let mut lines = vec![format!("Failing migration: {script}")];
            lines.extend(sql_state(source));
            lines
        }
        Database(source) => diagnose_sql(source, "a database error"),
        Schema(_) => vec![format!("Schema validation: {error}")],
        BadName(_)
        | Validation(_)
        | HighBaseline(_)
        | DuplicateExternalAccounts(_)
        | Incomplete(_) => {
            vec![format!("Reason: {error}")]
        }
    }
}

fn diagnose_sql(error: &sqlx::Error, what: &str) -> Vec<String> {
    let mut lines = vec![format!("Reason: {what}")];
    lines.extend(sql_state(error));
    lines
}

fn sql_state(error: &sqlx::Error) -> Option<String> {
    error
        .as_database_error()
        .and_then(|e| e.code())
        .map(|code| format!("SQLState: {code}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_database_matches_server_precedence() {
        // DATABASE_URL takes precedence over SPRING_DATASOURCE_URL and preserves embedded creds.
        let env = Env::from_map([
            (
                "DATABASE_URL",
                "postgres://alice:secret@db.internal:5433/app",
            ),
            (
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://ignored:5432/other",
            ),
            ("SPRING_DATASOURCE_USERNAME", "bob"),
            ("SPRING_DATASOURCE_PASSWORD", "wrong"),
        ]);
        let DatabaseConfig::Postgres(ds) = resolve_database(&env).expect("parse DATABASE_URL")
        else {
            panic!("expected Postgres");
        };
        assert_eq!(ds.host, "db.internal");
        assert_eq!(ds.port, 5433);
        assert_eq!(ds.database, "app");
        assert_eq!(ds.username, "alice");
        assert_eq!(ds.password, "secret");

        // Default compose env: POSTGRES_PASSWORD used when SPRING_DATASOURCE_PASSWORD is absent/empty.
        let compose = Env::from_map([
            (
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://db:5432/picsou?sslmode=prefer",
            ),
            ("SPRING_DATASOURCE_USERNAME", "picsou"),
            ("SPRING_DATASOURCE_PASSWORD", ""),
            ("POSTGRES_PASSWORD", "compose-pass"),
        ]);
        let DatabaseConfig::Postgres(ds) = resolve_database(&compose).expect("parse compose env")
        else {
            panic!("expected Postgres");
        };
        assert_eq!(ds.password, "compose-pass");
        assert_eq!(ds.ssl_mode.as_deref(), Some("prefer"));

        // SPRING_DATASOURCE_PASSWORD wins over POSTGRES_PASSWORD when non-empty.
        let override_pw = Env::from_map([
            ("SPRING_DATASOURCE_URL", "jdbc:postgresql://db:5432/picsou"),
            ("SPRING_DATASOURCE_PASSWORD", "spring-pass"),
            ("POSTGRES_PASSWORD", "compose-pass"),
        ]);
        let DatabaseConfig::Postgres(ds) =
            resolve_database(&override_pw).expect("parse override env")
        else {
            panic!("expected Postgres");
        };
        assert_eq!(ds.username, "picsou");
        assert_eq!(ds.password, "spring-pass");

        // Missing both URLs errors with a message naming both variables.
        let err = resolve_database(&Env::default()).expect_err("missing URL must fail");
        assert!(err.contains("DATABASE_URL") && err.contains("SPRING_DATASOURCE_URL"));
    }
}
