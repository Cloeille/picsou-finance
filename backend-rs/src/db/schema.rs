//! The physical-schema check of the release CLI, as Hibernate's `validate` and the named-enum
//! check of the Java ReleaseMigrationCli did it: every table and column the migrations create
//! must exist with its type, and every enum label. Recorded history alone is not proof.

use std::collections::{BTreeMap, BTreeSet};

use sqlx::{PgConnection, Row};

use crate::db::flyway::MigrationError;

/// The schema an empty database has after every migration; a test keeps it current.
pub const EXPECTED: &str = include_str!("schema.tsv");

/// One line per column (`column`, table, column, type) and enum label (`enum`, type, label).
pub async fn snapshot(conn: &mut PgConnection) -> Result<Vec<String>, sqlx::Error> {
    let columns = sqlx::query(
        "SELECT table_name::text, column_name::text, udt_name::text FROM information_schema.columns \
         WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' ORDER BY 1, 2",
    )
    .fetch_all(&mut *conn)
    .await?;
    let labels = sqlx::query(
        "SELECT t.typname::text, e.enumlabel::text FROM pg_type t \
         JOIN pg_enum e ON e.enumtypid = t.oid JOIN pg_namespace n ON n.oid = t.typnamespace \
         WHERE n.nspname = 'public' ORDER BY 1, 2",
    )
    .fetch_all(&mut *conn)
    .await?;
    let mut lines: Vec<String> = columns
        .iter()
        .map(|r| {
            format!(
                "column\t{}\t{}\t{}",
                r.get::<String, _>(0),
                r.get::<String, _>(1),
                r.get::<String, _>(2)
            )
        })
        .collect();
    lines.extend(
        labels
            .iter()
            .map(|r| format!("enum\t{}\t{}", r.get::<String, _>(0), r.get::<String, _>(1))),
    );
    Ok(lines)
}

/// Fails on the first table, column, type or enum label the migrations create that is missing.
/// A database with a BASELINE row was not built by these scripts, so there is nothing to compare.
pub async fn check(conn: &mut PgConnection) -> Result<(), MigrationError> {
    let baselined: bool = sqlx::query_scalar(
        "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE type = 'BASELINE')",
    )
    .fetch_one(&mut *conn)
    .await?;
    if baselined {
        return Ok(());
    }
    let live = snapshot(conn).await?;
    let mut live_types: BTreeMap<(&str, &str), &str> = BTreeMap::new();
    let mut live_labels: BTreeSet<(&str, &str)> = BTreeSet::new();
    for line in &live {
        match line.split('\t').collect::<Vec<_>>()[..] {
            ["column", table, column, kind] => {
                live_types.insert((table, column), kind);
            }
            ["enum", kind, label] => {
                live_labels.insert((kind, label));
            }
            _ => {}
        }
    }
    let mut missing_labels: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for line in EXPECTED.lines() {
        match line.split('\t').collect::<Vec<_>>()[..] {
            ["column", table, column, kind] => match live_types.get(&(table, column)) {
                None => {
                    return Err(MigrationError::Schema(format!(
                        "missing column [{column}] in table [{table}]"
                    )));
                }
                Some(found) if *found != kind => {
                    return Err(MigrationError::Schema(format!(
                        "wrong column type encountered in column [{column}] in table [{table}]; found [{found}], but expecting [{kind}]"
                    )));
                }
                Some(_) => {}
            },
            ["enum", kind, label] if !live_labels.contains(&(kind, label)) => {
                missing_labels.entry(kind).or_default().push(label);
            }
            _ => {}
        }
    }
    match missing_labels.into_iter().next() {
        Some((kind, labels)) => Err(MigrationError::Schema(format!(
            "PostgreSQL enum {kind} is missing labels {labels:?}"
        ))),
        None => Ok(()),
    }
}
