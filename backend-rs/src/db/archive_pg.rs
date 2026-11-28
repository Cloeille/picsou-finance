//! Loads the SQLite database of an archive into PostgreSQL (`picsou import-archive` on a
//! PostgreSQL target, issue #865 slice 3): the reverse of `export_pg_to_sqlite`, with the same
//! `ColKind` mapping and `schema.tsv` as the contract.
//!
//! Everything happens in ONE transaction: truncate every application table, insert in foreign-key
//! order, move every sequence past the highest id, compare row counts. Any failure drops the
//! transaction, so the previous rows are untouched. No foreign key of the schema is deferrable and
//! `session_replication_role` needs a superuser, so rows go in parents first; the two
//! self-referencing columns (`account.parent_account_id`, `category.parent_id`) are inserted as
//! NULL and set once the whole table is in.
//!
//! Messages carry table names and counts only, never a value.

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use chrono::{DateTime, NaiveDate, NaiveDateTime};
use rusqlite::types::Value as SqliteValue;
use rusqlite::{Connection as SqliteConnection, OpenFlags};
use rust_decimal::Decimal;
use sqlx::{Connection, PgConnection, Postgres, QueryBuilder};
use uuid::Uuid;

use super::archive::{ArchiveError, Manifest, pg_err, quote_ident};
use super::sqlite::decimal;
use super::sqlite::export_pg::ColKind;
use super::sqlite::migrate::expected_schema_from_tsv;

const MAX_BATCH_ROWS: usize = 500;
/// PostgreSQL accepts 65 535 bind parameters per statement.
const MAX_BIND_PARAMS: usize = 65_000;

struct Column {
    name: String,
    udt: String,
    kind: ColKind,
}

struct Table {
    name: String,
    columns: Vec<Column>,
    rows: Vec<Vec<SqliteValue>>,
}

impl Table {
    fn position(&self, column: &str) -> Option<usize> {
        self.columns.iter().position(|c| c.name == column)
    }
}

fn verification(reason: impl Into<String>) -> ArchiveError {
    ArchiveError::Verification(reason.into())
}

/// Whether the target holds an `app_user` row. A database that was never migrated holds none.
pub(super) async fn holds_data(conn: &mut PgConnection) -> Result<bool, ArchiveError> {
    let table: bool = sqlx::query_scalar("SELECT to_regclass('public.app_user') IS NOT NULL")
        .fetch_one(&mut *conn)
        .await
        .map_err(pg_err("app_user"))?;
    if !table {
        return Ok(false);
    }
    sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM app_user)")
        .fetch_one(&mut *conn)
        .await
        .map_err(pg_err("app_user"))
}

/// Loads `database` (a verified SQLite file at this binary's schema) into the migrated target and
/// returns the number of rows loaded. Commits only when every count matches.
pub(super) async fn load(
    conn: &mut PgConnection,
    database: &Path,
    manifest: &Manifest,
) -> Result<u64, ArchiveError> {
    let path = database.to_path_buf();
    let tables = tokio::task::spawn_blocking(move || read_tables(&path))
        .await
        .map_err(|e| ArchiveError::Io {
            context: "cannot read the archived database",
            source: std::io::Error::other(e.to_string()),
        })??;
    let names: BTreeSet<String> = tables.iter().map(|t| t.name.clone()).collect();
    if let Some(unknown) = manifest.table_counts.keys().find(|t| !names.contains(*t)) {
        return Err(verification(format!(
            "the archive lists table [{unknown}], which this schema does not have"
        )));
    }
    let keys = foreign_keys(conn, &names).await?;
    let order = insertion_order(&names, &keys.edges)?;
    let by_name: BTreeMap<&str, &Table> = tables.iter().map(|t| (t.name.as_str(), t)).collect();

    let mut tx = conn.begin().await.map_err(pg_err("<transaction>"))?;
    let loaded = async {
        let all = names
            .iter()
            .map(|t| format!("\"public\".{}", quote_ident(t)))
            .collect::<Vec<_>>()
            .join(", ");
        sqlx::query(sqlx::AssertSqlSafe(format!(
            "TRUNCATE TABLE {all} RESTART IDENTITY CASCADE"
        )))
        .execute(&mut *tx)
        .await
        .map_err(pg_err("<truncate>"))?;
        for name in &order {
            let table = by_name[name.as_str()];
            insert_table(&mut tx, table, keys.self_refs.get(name)).await?;
        }
        reset_sequences(&mut tx, &names).await?;
        check_counts(&mut tx, &tables, manifest).await
    }
    .await;
    match loaded {
        Ok(rows) => {
            tx.commit().await.map_err(pg_err("<transaction>"))?;
            Ok(rows)
        }
        Err(err) => {
            let _ = tx.rollback().await;
            Err(err)
        }
    }
}

fn read_tables(path: &Path) -> Result<Vec<Table>, ArchiveError> {
    let conn = SqliteConnection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut tables = Vec::new();
    for (name, columns) in expected_schema_from_tsv() {
        let columns = columns
            .into_iter()
            .map(|(column, udt)| {
                let kind = ColKind::from_pg_udt(&name, &column, &udt)?;
                Ok(Column {
                    name: column,
                    udt,
                    kind,
                })
            })
            .collect::<Result<Vec<_>, ArchiveError>>()?;
        let select = columns
            .iter()
            .map(|c| quote_ident(&c.name))
            .collect::<Vec<_>>()
            .join(", ");
        let mut stmt = conn.prepare(&format!("SELECT {select} FROM {}", quote_ident(&name)))?;
        let width = columns.len();
        let rows = stmt
            .query_map([], |row| {
                (0..width)
                    .map(|i| row.get::<_, SqliteValue>(i))
                    .collect::<Result<Vec<_>, _>>()
            })?
            .collect::<Result<Vec<_>, _>>()?;
        tables.push(Table {
            name,
            columns,
            rows,
        });
    }
    Ok(tables)
}

struct ForeignKeys {
    /// `(child, parent)` between two different tables.
    edges: BTreeSet<(String, String)>,
    /// `table -> (referencing column, referenced column)` of a self-referencing key.
    self_refs: BTreeMap<String, (String, String)>,
}

async fn foreign_keys(
    conn: &mut PgConnection,
    names: &BTreeSet<String>,
) -> Result<ForeignKeys, ArchiveError> {
    let rows: Vec<(String, String, String, String)> = sqlx::query_as(
        "SELECT cl.relname::text, pl.relname::text, ca.attname::text, pa.attname::text \
         FROM pg_constraint k \
         JOIN pg_class cl ON cl.oid = k.conrelid \
         JOIN pg_class pl ON pl.oid = k.confrelid \
         JOIN pg_attribute ca ON ca.attrelid = k.conrelid AND ca.attnum = k.conkey[1] \
         JOIN pg_attribute pa ON pa.attrelid = k.confrelid AND pa.attnum = k.confkey[1] \
         WHERE k.contype = 'f' AND cl.relnamespace = 'public'::regnamespace",
    )
    .fetch_all(&mut *conn)
    .await
    .map_err(pg_err("<foreign keys>"))?;
    let mut keys = ForeignKeys {
        edges: BTreeSet::new(),
        self_refs: BTreeMap::new(),
    };
    for (child, parent, child_column, parent_column) in rows {
        if !names.contains(&child) || !names.contains(&parent) {
            continue;
        }
        if child == parent {
            keys.self_refs.insert(child, (child_column, parent_column));
        } else {
            keys.edges.insert((child, parent));
        }
    }
    Ok(keys)
}

/// Parents before children; ties by name so the order is stable.
fn insertion_order(
    names: &BTreeSet<String>,
    edges: &BTreeSet<(String, String)>,
) -> Result<Vec<String>, ArchiveError> {
    let mut remaining: BTreeSet<&str> = names.iter().map(String::as_str).collect();
    let mut order = Vec::with_capacity(names.len());
    while !remaining.is_empty() {
        let ready: Vec<&str> = remaining
            .iter()
            .copied()
            .filter(|table| {
                !edges
                    .iter()
                    .any(|(child, parent)| child == table && remaining.contains(parent.as_str()))
            })
            .collect();
        if ready.is_empty() {
            return Err(verification("foreign keys between tables form a cycle"));
        }
        for table in ready {
            remaining.remove(table);
            order.push(table.to_owned());
        }
    }
    Ok(order)
}

async fn insert_table(
    conn: &mut PgConnection,
    table: &Table,
    self_ref: Option<&(String, String)>,
) -> Result<(), ArchiveError> {
    if table.rows.is_empty() {
        return Ok(());
    }
    let deferred = self_ref.and_then(|(column, _)| table.position(column));
    let null = SqliteValue::Null;
    let head = format!(
        "INSERT INTO \"public\".{} ({}) VALUES ",
        quote_ident(&table.name),
        table
            .columns
            .iter()
            .map(|c| quote_ident(&c.name))
            .collect::<Vec<_>>()
            .join(", ")
    );
    let batch = (MAX_BIND_PARAMS / table.columns.len()).clamp(1, MAX_BATCH_ROWS);
    for chunk in table.rows.chunks(batch) {
        let mut query = QueryBuilder::<Postgres>::new(&head);
        for (r, row) in chunk.iter().enumerate() {
            query.push(if r == 0 { "(" } else { "), (" });
            for (i, (column, value)) in table.columns.iter().zip(row).enumerate() {
                if i > 0 {
                    query.push(", ");
                }
                let value = if Some(i) == deferred { &null } else { value };
                push_value(&mut query, column, value, &table.name)?;
            }
        }
        query.push(")");
        query
            .build()
            .execute(&mut *conn)
            .await
            .map_err(pg_err(&table.name))?;
    }
    if let (Some(column), Some((_, key))) = (deferred, self_ref) {
        let key = table
            .position(key)
            .ok_or_else(|| verification("a self-referencing key has no referenced column"))?;
        for row in table
            .rows
            .iter()
            .filter(|r| !matches!(r[column], SqliteValue::Null))
        {
            let mut query = QueryBuilder::<Postgres>::new(format!(
                "UPDATE \"public\".{} SET {} = ",
                quote_ident(&table.name),
                quote_ident(&table.columns[column].name)
            ));
            push_value(
                &mut query,
                &table.columns[column],
                &row[column],
                &table.name,
            )?;
            query.push(format!(
                " WHERE {} = ",
                quote_ident(&table.columns[key].name)
            ));
            push_value(&mut query, &table.columns[key], &row[key], &table.name)?;
            query
                .build()
                .execute(&mut *conn)
                .await
                .map_err(pg_err(&table.name))?;
        }
    }
    Ok(())
}

/// Binds one SQLite value as the PostgreSQL type of its column (`$n::<udt>`).
fn push_value(
    query: &mut QueryBuilder<Postgres>,
    column: &Column,
    value: &SqliteValue,
    table: &str,
) -> Result<(), ArchiveError> {
    let bad = || ArchiveError::Convert {
        table: table.to_owned(),
        column: column.name.clone(),
    };
    match column.kind {
        ColKind::Int64 => match value {
            SqliteValue::Integer(n) => query.push_bind(Some(*n)),
            SqliteValue::Null => query.push_bind(None::<i64>),
            _ => return Err(bad()),
        },
        ColKind::Bool => match value {
            SqliteValue::Integer(0) => query.push_bind(Some(false)),
            SqliteValue::Integer(1) => query.push_bind(Some(true)),
            SqliteValue::Null => query.push_bind(None::<bool>),
            _ => return Err(bad()),
        },
        ColKind::Numeric => match value {
            SqliteValue::Integer(n) => query.push_bind(Some(Decimal::from(*n))),
            _ => query.push_bind(parse(value, &bad, |s| decimal::from_sqlite_text(s).ok())?),
        },
        ColKind::Timestamptz => query.push_bind(parse(value, &bad, |s| {
            parse_instant(s).map(|t| t.and_utc())
        })?),
        ColKind::Timestamp => query.push_bind(parse(value, &bad, parse_instant)?),
        ColKind::Date => query.push_bind(parse(value, &bad, |s| {
            NaiveDate::parse_from_str(s.get(..10).unwrap_or(s), "%Y-%m-%d").ok()
        })?),
        ColKind::Uuid => query.push_bind(parse(value, &bad, |s| Uuid::parse_str(s).ok())?),
        ColKind::Bytea => match value {
            SqliteValue::Blob(bytes) => query.push_bind(Some(bytes.clone())),
            SqliteValue::Null => query.push_bind(None::<Vec<u8>>),
            _ => return Err(bad()),
        },
        ColKind::TextOrEnum => query.push_bind(parse(value, &bad, |s| Some(s.to_owned()))?),
    };
    query.push("::");
    query.push(quote_ident(&column.udt));
    Ok(())
}

fn parse<T>(
    value: &SqliteValue,
    bad: &impl Fn() -> ArchiveError,
    convert: impl FnOnce(&str) -> Option<T>,
) -> Result<Option<T>, ArchiveError> {
    match value {
        SqliteValue::Null => Ok(None),
        SqliteValue::Text(text) => convert(text).map(Some).ok_or_else(bad),
        _ => Err(bad()),
    }
}

/// An ISO timestamp as UTC: RFC 3339 (with `Z` or an offset), or a bare date-time read as UTC.
fn parse_instant(text: &str) -> Option<NaiveDateTime> {
    if let Ok(instant) = DateTime::parse_from_rfc3339(text) {
        return Some(instant.naive_utc());
    }
    let bare = text.trim_end_matches('Z');
    ["%Y-%m-%dT%H:%M:%S%.f", "%Y-%m-%d %H:%M:%S%.f"]
        .iter()
        .find_map(|format| NaiveDateTime::parse_from_str(bare, format).ok())
}

/// Moves every serial or identity sequence of the schema to its table's highest id, so the next
/// insert gets a fresh one.
async fn reset_sequences(
    conn: &mut PgConnection,
    names: &BTreeSet<String>,
) -> Result<(), ArchiveError> {
    let rows: Vec<(String, String, Option<String>)> = sqlx::query_as(
        "SELECT c.relname::text, a.attname::text, \
                pg_get_serial_sequence(format('%I.%I', n.nspname, c.relname), a.attname::text) \
         FROM pg_class c \
         JOIN pg_namespace n ON n.oid = c.relnamespace \
         JOIN pg_attribute a ON a.attrelid = c.oid \
         WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped",
    )
    .fetch_all(&mut *conn)
    .await
    .map_err(pg_err("<sequences>"))?;
    for (table, column, sequence) in rows {
        let Some(sequence) = sequence.filter(|_| names.contains(&table)) else {
            continue;
        };
        let (column, quoted) = (quote_ident(&column), quote_ident(&table));
        sqlx::query(sqlx::AssertSqlSafe(format!(
            "SELECT setval($1::regclass, COALESCE(MAX({column}), 1), COUNT({column}) > 0) \
             FROM \"public\".{quoted}"
        )))
        .bind(sequence)
        .execute(&mut *conn)
        .await
        .map_err(pg_err(&table))?;
    }
    Ok(())
}

/// Every table must hold what the manifest and the SQLite source say, or the load is rolled back.
async fn check_counts(
    conn: &mut PgConnection,
    tables: &[Table],
    manifest: &Manifest,
) -> Result<u64, ArchiveError> {
    let mut total = 0u64;
    for table in tables {
        let sql = format!(
            "SELECT COUNT(*) FROM \"public\".{}",
            quote_ident(&table.name)
        );
        let actual: i64 = sqlx::query_scalar(sqlx::AssertSqlSafe(sql))
            .fetch_one(&mut *conn)
            .await
            .map_err(pg_err(&table.name))?;
        let actual = u64::try_from(actual).unwrap_or(0);
        let source = u64::try_from(table.rows.len()).unwrap_or(u64::MAX);
        let expected = manifest
            .table_counts
            .get(&table.name)
            .copied()
            .unwrap_or(source);
        if actual != expected || actual != source {
            return Err(verification(format!(
                "row count mismatch on table [{}]: archive had {expected}, SQLite had {source}, PostgreSQL has {actual}",
                table.name
            )));
        }
        total += actual;
    }
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(items: &[&str]) -> BTreeSet<String> {
        items.iter().map(|s| (*s).to_owned()).collect()
    }

    fn edges(items: &[(&str, &str)]) -> BTreeSet<(String, String)> {
        items
            .iter()
            .map(|(c, p)| ((*c).to_owned(), (*p).to_owned()))
            .collect()
    }

    #[test]
    fn parents_come_before_their_children() {
        let order = insertion_order(
            &names(&["transaction", "account", "app_user", "family_member"]),
            &edges(&[
                ("transaction", "account"),
                ("account", "family_member"),
                ("app_user", "family_member"),
            ]),
        )
        .expect("an order");
        let at = |t: &str| order.iter().position(|o| o == t).expect("present");
        assert!(at("family_member") < at("account"));
        assert!(at("account") < at("transaction"));
        assert!(at("family_member") < at("app_user"));
    }

    #[test]
    fn a_cycle_between_tables_is_refused() {
        let err = insertion_order(&names(&["a", "b"]), &edges(&[("a", "b"), ("b", "a")]))
            .expect_err("a cycle");
        assert!(matches!(err, ArchiveError::Verification(_)));
    }

    #[test]
    fn timestamps_read_as_utc() {
        let utc = |s: &str| parse_instant(s).map(|t| t.to_string());
        assert_eq!(
            utc("2026-01-02T03:04:05.123456Z").as_deref(),
            Some("2026-01-02 03:04:05.123456")
        );
        assert_eq!(
            utc("2026-01-02T04:04:05+01:00").as_deref(),
            Some("2026-01-02 03:04:05")
        );
        assert_eq!(
            utc("2026-01-02 03:04:05").as_deref(),
            Some("2026-01-02 03:04:05")
        );
        assert_eq!(utc("not a date"), None);
    }
}
