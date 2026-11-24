//! Dual-engine query parameter bindings (`DbArgs` and `DbBind`).
//!
//! Each `.bind(val)` call encodes into both a `sqlx::postgres::PgArguments` buffer (zero-overhead
//! native PostgreSQL wire encoding, including custom `sqlx::Type` enums and arrays) and a
//! `Vec<rusqlite::types::Value>` buffer for SQLite (exact canonical `TEXT` for `Decimal`, RFC 3339
//! UTC for timestamps, ISO-8601 for dates, JSON arrays for `= ANY($n)` / `!= ALL($n)`).

use std::borrow::Cow;

use chrono::{DateTime, NaiveDate, NaiveDateTime, SecondsFormat, Utc};
use rusqlite::types::Value as SqliteValue;
use rust_decimal::Decimal;
use sqlx::Arguments;
use sqlx::error::BoxDynError;
use sqlx::postgres::PgArguments;
use uuid::Uuid;

use crate::db::sqlite::decimal;

/// Dual-engine argument buffer built incrementally by `.bind(...)`.
#[derive(Default)]
pub struct DbArgs {
    pg: PgArguments,
    pg_error: Option<String>,
    sqlite: Vec<SqliteValue>,
}

impl DbArgs {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push<T: DbBind>(&mut self, value: T) {
        self.sqlite.push(value.to_sqlite_value());
        if self.pg_error.is_none()
            && let Err(err) = value.bind_pg(&mut self.pg)
        {
            self.pg_error = Some(err.to_string());
        }
    }

    pub(crate) fn into_pg(self) -> Result<PgArguments, sqlx::Error> {
        if let Some(err) = self.pg_error {
            return Err(sqlx::Error::Protocol(format!(
                "PostgreSQL argument encoding failed: {err}"
            )));
        }
        Ok(self.pg)
    }

    pub(crate) fn into_sqlite(self) -> Vec<SqliteValue> {
        self.sqlite
    }
}

/// Trait implemented by every Rust value that can be bound into a dual-engine query.
pub trait DbBind: Sized {
    /// Encodes `self` into PostgreSQL `PgArguments`.
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError>;

    /// Encodes a typed `NULL` for `Self` into PostgreSQL `PgArguments` (used by `Option<Self>`).
    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError>;

    /// Encodes `self` into a canonical `rusqlite::types::Value`.
    fn to_sqlite_value(&self) -> SqliteValue;
}

impl<T: DbBind> DbBind for Option<T> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        match self {
            Some(v) => v.bind_pg(args),
            None => T::bind_null_pg(args),
        }
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        T::bind_null_pg(args)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        match self {
            Some(v) => v.to_sqlite_value(),
            None => SqliteValue::Null,
        }
    }
}

impl<'a, T> DbBind for &'a Option<T>
where
    &'a T: DbBind,
{
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        match self {
            Some(v) => v.bind_pg(args),
            None => <&'a T as DbBind>::bind_null_pg(args),
        }
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        <&'a T as DbBind>::bind_null_pg(args)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        match self {
            Some(v) => v.to_sqlite_value(),
            None => SqliteValue::Null,
        }
    }
}

macro_rules! impl_copy_int_bind {
    ($($ty:ty),+ $(,)?) => {
        $(
            impl DbBind for $ty {
                fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
                    args.add(self)
                }

                fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
                    args.add(Option::<$ty>::None)
                }

                fn to_sqlite_value(&self) -> SqliteValue {
                    SqliteValue::Integer(i64::from(*self))
                }
            }

            impl DbBind for &$ty {
                fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
                    args.add(*self)
                }

                fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
                    args.add(Option::<$ty>::None)
                }

                fn to_sqlite_value(&self) -> SqliteValue {
                    SqliteValue::Integer(i64::from(**self))
                }
            }
        )+
    };
}

impl_copy_int_bind!(i16, i32, i64);

impl DbBind for bool {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<bool>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Integer(i64::from(*self))
    }
}

impl DbBind for &bool {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(*self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<bool>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Integer(i64::from(**self))
    }
}

impl DbBind for String {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<String>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.clone())
    }
}

impl DbBind for &String {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_str())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&str>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text((*self).clone())
    }
}

impl DbBind for &str {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&str>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text((*self).to_owned())
    }
}

impl DbBind for Cow<'_, str> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_ref())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&str>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.as_ref().to_owned())
    }
}

impl DbBind for Decimal {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Decimal>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(decimal::to_sqlite_text(*self))
    }
}

impl DbBind for &Decimal {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(*self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Decimal>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(decimal::to_sqlite_text(**self))
    }
}

impl DbBind for NaiveDate {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<NaiveDate>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.format("%Y-%m-%d").to_string())
    }
}

impl DbBind for &NaiveDate {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(*self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<NaiveDate>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.format("%Y-%m-%d").to_string())
    }
}

impl DbBind for NaiveDateTime {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<NaiveDateTime>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.format("%Y-%m-%dT%H:%M:%S%.6fZ").to_string())
    }
}

impl DbBind for &NaiveDateTime {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(*self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<NaiveDateTime>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.format("%Y-%m-%dT%H:%M:%S%.6fZ").to_string())
    }
}

impl DbBind for DateTime<Utc> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<DateTime<Utc>>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.to_rfc3339_opts(SecondsFormat::Micros, true))
    }
}

impl DbBind for &DateTime<Utc> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(*self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<DateTime<Utc>>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.to_rfc3339_opts(SecondsFormat::Micros, true))
    }
}

impl DbBind for Uuid {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Uuid>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.hyphenated().to_string())
    }
}

impl DbBind for &Uuid {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(*self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Uuid>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.hyphenated().to_string())
    }
}

impl DbBind for Vec<u8> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Vec<u8>>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Blob(self.clone())
    }
}

impl DbBind for &Vec<u8> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_slice())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[u8]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Blob((*self).clone())
    }
}

impl DbBind for &[u8] {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[u8]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Blob(self.to_vec())
    }
}

impl DbBind for serde_json::Value {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<serde_json::Value>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text(self.to_string())
    }
}

impl DbBind for &serde_json::Value {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<serde_json::Value>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        SqliteValue::Text((*self).to_string())
    }
}

// Array bindings (`= ANY($n)` / `!= ALL($n)`):
// PostgreSQL uses native array bindings; SQLite encodes JSON arrays consumed by `json_each(?n)`.

fn to_json_array<T: serde::Serialize>(slice: &[T]) -> SqliteValue {
    SqliteValue::Text(serde_json::to_string(slice).unwrap_or_else(|_| "[]".to_owned()))
}

impl DbBind for Vec<i64> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Vec<i64>>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &Vec<i64> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_slice())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[i64]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &[i64] {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[i64]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for Vec<i32> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Vec<i32>>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &Vec<i32> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_slice())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[i32]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &[i32] {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[i32]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for Vec<String> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<Vec<String>>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &Vec<String> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_slice())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[String]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &[String] {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[String]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for &[&str] {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self)
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[&str]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self)
    }
}

impl DbBind for Vec<&str> {
    fn bind_pg(self, args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(self.as_slice())
    }

    fn bind_null_pg(args: &mut PgArguments) -> Result<(), BoxDynError> {
        args.add(Option::<&[&str]>::None)
    }

    fn to_sqlite_value(&self) -> SqliteValue {
        to_json_array(self.as_slice())
    }
}
