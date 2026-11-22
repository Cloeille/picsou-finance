//! Dual-engine result rows (`DbRow`), typed column decoding (`DbDecode`), and row mapping
//! (`DbFromRow`, including tuples of arity 1 through 16).

use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};
use rusqlite::types::Value as SqliteValue;
use rust_decimal::Decimal;
use sqlx::Row;
use sqlx::postgres::PgRow;
use uuid::Uuid;

use crate::db::sqlite::SqliteRow;
use crate::db::sqlite::decimal::{self, DecimalCodecError};

/// One result row returned by either PostgreSQL or SQLite.
pub enum DbRow {
    Postgres(PgRow),
    Sqlite(SqliteRow),
}

impl DbRow {
    /// Returns the number of columns in this row.
    pub fn len(&self) -> usize {
        match self {
            Self::Postgres(r) => r.len(),
            Self::Sqlite(r) => r.len(),
        }
    }

    /// Returns `true` if this row has zero columns.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Decodes a column by zero-based index (`usize`) or column name (`&str`).
    pub fn try_get<T, I>(&self, index: I) -> Result<T, sqlx::Error>
    where
        T: DbDecode,
        I: DbIndex,
    {
        index.get_from_row(self)
    }

    /// Decodes a column by zero-based index (`usize`) or column name (`&str`), panicking on error
    /// (matching `sqlx::Row::get`).
    pub fn get<T, I>(&self, index: I) -> T
    where
        T: DbDecode,
        I: DbIndex + Copy + std::fmt::Display,
    {
        self.try_get(index)
            .unwrap_or_else(|e| panic!("failed to decode column {index}: {e}"))
    }
}

/// Column selector accepted by [`DbRow::try_get`] and [`DbRow::get`]: either `usize` or `&str`.
pub trait DbIndex {
    fn get_from_row<T: DbDecode>(self, row: &DbRow) -> Result<T, sqlx::Error>;
}

impl DbIndex for usize {
    fn get_from_row<T: DbDecode>(self, row: &DbRow) -> Result<T, sqlx::Error> {
        match row {
            DbRow::Postgres(pg) => T::decode_pg(pg, self),
            DbRow::Sqlite(sq) => {
                let val = sq.value_at(self)?;
                T::decode_sqlite(val).map_err(|err| wrap_col_decode(self.to_string(), err))
            }
        }
    }
}

impl DbIndex for &str {
    fn get_from_row<T: DbDecode>(self, row: &DbRow) -> Result<T, sqlx::Error> {
        match row {
            DbRow::Postgres(pg) => T::decode_pg(pg, self),
            DbRow::Sqlite(sq) => {
                let idx = sq.column_index(self)?;
                let val = sq.value_at(idx)?;
                T::decode_sqlite(val).map_err(|err| wrap_col_decode(self.to_owned(), err))
            }
        }
    }
}

fn wrap_col_decode(index: String, err: sqlx::Error) -> sqlx::Error {
    match err {
        sqlx::Error::Decode(source) => sqlx::Error::ColumnDecode { index, source },
        other => other,
    }
}

fn decode_err(msg: impl Into<String>) -> sqlx::Error {
    sqlx::Error::Decode(Box::new(std::io::Error::new(
        std::io::ErrorKind::InvalidData,
        msg.into(),
    )))
}

fn unexpected_null_err(ty: &str) -> sqlx::Error {
    decode_err(format!("unexpected NULL while decoding {ty}"))
}

/// Decodes a single column value from either a `PgRow` or a `SqliteValue`.
pub trait DbDecode: Sized {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>;

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>;

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error>;
}

impl<T: DbDecode> DbDecode for Option<T> {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        T::decode_pg_opt(row, index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        T::decode_pg_opt(row, index).map(Some)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Null => Ok(None),
            other => T::decode_sqlite(other).map(Some),
        }
    }
}

impl DbDecode for i64 {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Integer(v) => Ok(*v),
            SqliteValue::Null => Err(unexpected_null_err("i64")),
            other => Err(decode_err(format!(
                "expected SQLite INTEGER for i64, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for i32 {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Integer(v) => i32::try_from(*v)
                .map_err(|_| decode_err(format!("SQLite integer {v} overflows i32"))),
            SqliteValue::Null => Err(unexpected_null_err("i32")),
            other => Err(decode_err(format!(
                "expected SQLite INTEGER for i32, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for i16 {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Integer(v) => i16::try_from(*v)
                .map_err(|_| decode_err(format!("SQLite integer {v} overflows i16"))),
            SqliteValue::Null => Err(unexpected_null_err("i16")),
            other => Err(decode_err(format!(
                "expected SQLite INTEGER for i16, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for bool {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Integer(0) => Ok(false),
            SqliteValue::Integer(1) => Ok(true),
            SqliteValue::Integer(other) => Err(decode_err(format!(
                "invalid SQLite boolean integer {other} (expected 0 or 1)"
            ))),
            SqliteValue::Null => Err(unexpected_null_err("bool")),
            other => Err(decode_err(format!(
                "expected SQLite INTEGER(0/1) for bool, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for String {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(s) => Ok(s.clone()),
            // When PostgreSQL `col::text` is stripped on SQLite for an integer column, preserve
            // the expected textual rendering seamlessly.
            SqliteValue::Integer(n) => Ok(n.to_string()),
            SqliteValue::Null => Err(unexpected_null_err("String")),
            other => Err(decode_err(format!(
                "expected SQLite TEXT for String, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for Decimal {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(raw) => {
                decimal::from_sqlite_text(raw).map_err(|err| sqlx::Error::Decode(Box::new(err)))
            }
            // Allow exact integers from SQL literals like `COALESCE(dec_sum(amount), 0)`.
            SqliteValue::Integer(n) => Ok(Decimal::from(*n)),
            SqliteValue::Real(_) => Err(sqlx::Error::Decode(Box::new(
                DecimalCodecError::FloatValueForbidden,
            ))),
            SqliteValue::Blob(_) => Err(sqlx::Error::Decode(Box::new(
                DecimalCodecError::BlobValueForbidden,
            ))),
            SqliteValue::Null => Err(unexpected_null_err("Decimal")),
        }
    }
}

impl DbDecode for DateTime<Utc> {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(raw) => parse_sqlite_datetime_utc(raw),
            SqliteValue::Null => Err(unexpected_null_err("DateTime<Utc>")),
            other => Err(decode_err(format!(
                "expected SQLite TEXT for DateTime<Utc>, got {other:?}"
            ))),
        }
    }
}

fn parse_sqlite_datetime_utc(raw: &str) -> Result<DateTime<Utc>, sqlx::Error> {
    let trimmed = raw.trim();
    if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
        return Ok(dt.with_timezone(&Utc));
    }
    for fmt in [
        "%Y-%m-%d %H:%M:%S%.f",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S%.f",
        "%Y-%m-%dT%H:%M:%S",
    ] {
        if let Ok(ndt) = NaiveDateTime::parse_from_str(trimmed, fmt) {
            return Ok(ndt.and_utc());
        }
    }
    Err(decode_err(format!("invalid SQLite UTC timestamp '{raw}'")))
}

impl DbDecode for NaiveDateTime {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(raw) => {
                let trimmed = raw.trim();
                if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
                    return Ok(dt.naive_utc());
                }
                for fmt in [
                    "%Y-%m-%dT%H:%M:%S%.f",
                    "%Y-%m-%dT%H:%M:%S",
                    "%Y-%m-%d %H:%M:%S%.f",
                    "%Y-%m-%d %H:%M:%S",
                ] {
                    if let Ok(ndt) = NaiveDateTime::parse_from_str(trimmed, fmt) {
                        return Ok(ndt);
                    }
                }
                Err(decode_err(format!("invalid SQLite NaiveDateTime '{raw}'")))
            }
            SqliteValue::Null => Err(unexpected_null_err("NaiveDateTime")),
            other => Err(decode_err(format!(
                "expected SQLite TEXT for NaiveDateTime, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for NaiveDate {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(raw) => NaiveDate::parse_from_str(raw.trim(), "%Y-%m-%d")
                .map_err(|e| decode_err(format!("invalid SQLite NaiveDate '{raw}': {e}"))),
            SqliteValue::Null => Err(unexpected_null_err("NaiveDate")),
            other => Err(decode_err(format!(
                "expected SQLite TEXT for NaiveDate, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for Uuid {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(raw) => Uuid::parse_str(raw.trim())
                .map_err(|e| decode_err(format!("invalid SQLite Uuid '{raw}': {e}"))),
            SqliteValue::Null => Err(unexpected_null_err("Uuid")),
            other => Err(decode_err(format!(
                "expected SQLite TEXT for Uuid, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for Vec<u8> {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Blob(bytes) => Ok(bytes.clone()),
            SqliteValue::Null => Err(unexpected_null_err("Vec<u8>")),
            other => Err(decode_err(format!(
                "expected SQLite BLOB for Vec<u8>, got {other:?}"
            ))),
        }
    }
}

impl DbDecode for serde_json::Value {
    fn decode_pg<I>(row: &PgRow, index: I) -> Result<Self, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_pg_opt<I>(row: &PgRow, index: I) -> Result<Option<Self>, sqlx::Error>
    where
        I: sqlx::ColumnIndex<PgRow>,
    {
        row.try_get(index)
    }

    fn decode_sqlite(value: &SqliteValue) -> Result<Self, sqlx::Error> {
        match value {
            SqliteValue::Text(raw) => serde_json::from_str(raw)
                .map_err(|e| decode_err(format!("invalid SQLite JSON '{raw}': {e}"))),
            SqliteValue::Null => Err(unexpected_null_err("serde_json::Value")),
            other => Err(decode_err(format!(
                "expected SQLite TEXT for serde_json::Value, got {other:?}"
            ))),
        }
    }
}

/// Maps a dual-engine [`DbRow`] into a typed Rust struct or tuple.
///
/// Derive automatically via `#[derive(crate::db::DbFromRow)]`.
pub trait DbFromRow: Sized {
    fn from_row(row: &DbRow) -> Result<Self, sqlx::Error>;
}

macro_rules! impl_db_from_row_tuple {
    ($(($($idx:tt : $T:ident),+)),+ $(,)?) => {
        $(
            impl<$($T: DbDecode),+> DbFromRow for ($($T,)+) {
                fn from_row(row: &DbRow) -> Result<Self, sqlx::Error> {
                    Ok((
                        $(row.try_get::<$T, _>($idx)?,)+
                    ))
                }
            }
        )+
    };
}

impl_db_from_row_tuple!(
    (0: T0),
    (0: T0, 1: T1),
    (0: T0, 1: T1, 2: T2),
    (0: T0, 1: T1, 2: T2, 3: T3),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9, 10: T10),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9, 10: T10, 11: T11),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9, 10: T10, 11: T11, 12: T12),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9, 10: T10, 11: T11, 12: T12, 13: T13),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9, 10: T10, 11: T11, 12: T12, 13: T13, 14: T14),
    (0: T0, 1: T1, 2: T2, 3: T3, 4: T4, 5: T5, 6: T6, 7: T7, 8: T8, 9: T9, 10: T10, 11: T11, 12: T12, 13: T13, 14: T14, 15: T15),
);

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    #[test]
    fn sqlite_datetime_parses_rfc3339_and_sqlite_timestamp_formats() {
        let a = DateTime::<Utc>::decode_sqlite(&SqliteValue::Text(
            "2026-01-15T10:30:00.123456Z".into(),
        ))
        .unwrap();
        assert_eq!(
            a.to_rfc3339_opts(chrono::SecondsFormat::Micros, true),
            "2026-01-15T10:30:00.123456Z"
        );

        let b = DateTime::<Utc>::decode_sqlite(&SqliteValue::Text("2026-01-15 10:30:00".into()))
            .unwrap();
        assert_eq!(
            b.format("%Y-%m-%d %H:%M:%S").to_string(),
            "2026-01-15 10:30:00"
        );
    }

    #[test]
    fn sqlite_decimal_accepts_text_and_int_and_rejects_binary_float() {
        let d = Decimal::decode_sqlite(&SqliteValue::Text("123.45000000".into())).unwrap();
        assert_eq!(d, Decimal::from_str("123.45000000").unwrap());
        assert_eq!(d.scale(), 8);

        let zero = Decimal::decode_sqlite(&SqliteValue::Integer(0)).unwrap();
        assert_eq!(zero, Decimal::ZERO);

        let bad_float = Decimal::decode_sqlite(&SqliteValue::Real(0.1));
        assert!(bad_float.is_err());

        let opt_none = Option::<Decimal>::decode_sqlite(&SqliteValue::Null).unwrap();
        assert_eq!(opt_none, None);
    }
}
