//! `picsou migrate` and `picsou verify`: the Java ReleaseMigrationCli, which docker/deploy.sh and
//! the Helm pre-upgrade job run with the new image before it replaces the running app.

use sqlx::{Connection, PgConnection};

use crate::config::Datasource;
use crate::db::{connect_options, flyway};

/// Runs `migrate` or `verify` against the SPRING_DATASOURCE_* database; the exit code.
///
/// Only messages built from migration names and SQLSTATE codes are printed: a driver error can
/// carry the host, the user or SQL text.
pub async fn run(command: &str, env: &crate::config::Env) -> i32 {
    if command != "migrate" && command != "verify" {
        eprintln!("Command must be migrate or verify.");
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
    let outcome = match Datasource::parse_jdbc(url, username, password) {
        Ok(datasource) => apply(command, &datasource).await,
        Err(e) => Err(vec![format!("Reason: {e}")]),
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

async fn apply(command: &str, datasource: &Datasource) -> Result<(), Vec<String>> {
    let mut conn = PgConnection::connect_with(&connect_options(datasource))
        .await
        .map_err(|e| diagnose_sql(&e, "the database is unreachable"))?;
    let outcome = if command == "migrate" {
        flyway::migrate(&mut conn).await.map(|_| ())
    } else {
        flyway::verify(&mut conn).await
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
