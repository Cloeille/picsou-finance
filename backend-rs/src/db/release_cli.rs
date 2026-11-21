//! `picsou migrate`, `picsou verify`, and `picsou export-sqlite <output.db>`: database
//! maintenance subcommands. `migrate` and `verify` mirror the Java ReleaseMigrationCli used by
//! `docker/deploy.sh` and the Helm pre-upgrade job; `export-sqlite` exports a live PostgreSQL
//! database into a verified single-file SQLite database.

use std::path::Path;

use sqlx::{Connection, PgConnection};

use crate::config::Datasource;
use crate::db::sqlite::export_pg::{self, ExportError};
use crate::db::{connect, connect_options, flyway, schema};

/// Runs `migrate` or `verify` against the `SPRING_DATASOURCE_*` database; returns the exit code.
pub async fn run(command: &str, env: &crate::config::Env) -> i32 {
    run_with_args(command, &[], env).await
}

/// Runs `migrate`, `verify`, or `export-sqlite <output.db>` against the `SPRING_DATASOURCE_*`
/// database; returns the exit code.
///
/// Only messages built from migration names, table names, and SQLSTATE codes are printed: a driver
/// error can carry the host, the user or SQL text.
pub async fn run_with_args(command: &str, args: &[String], env: &crate::config::Env) -> i32 {
    if command != "migrate" && command != "verify" && command != "export-sqlite" {
        eprintln!("Command must be migrate, verify, or export-sqlite.");
        return 1;
    }
    if command == "export-sqlite" && args.len() != 1 {
        eprintln!("Usage: picsou export-sqlite <output.db>");
        return 1;
    }
    let var = |key: &str| env.get(key).map(str::trim).filter(|v| !v.is_empty());
    let (Some(url), Some(username), Some(password)) = (
        var("SPRING_DATASOURCE_URL"),
        var("SPRING_DATASOURCE_USERNAME"),
        var("SPRING_DATASOURCE_PASSWORD"),
    ) else {
        eprintln!(
            "SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD are required."
        );
        return 1;
    };
    let datasource = match Datasource::parse_jdbc(url, username, password) {
        Ok(datasource) => datasource,
        Err(e) => {
            eprintln!("Database command failed; database state was not accepted.");
            eprintln!("Command: {command}");
            eprintln!("Reason: {e}");
            return 1;
        }
    };
    if command == "export-sqlite" {
        return run_export_sqlite(&datasource, Path::new(&args[0])).await;
    }
    match apply(command, &datasource).await {
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
