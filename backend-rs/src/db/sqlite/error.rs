//! SQLite error wrapper implementing [`sqlx::error::DatabaseError`].
//!
//! Wrapping `rusqlite::Error::SqliteFailure` inside `sqlx::Error::Database(Box::new(SqliteDatabaseError))`
//! guarantees that callers checking `err.as_database_error().is_some_and(|e| e.is_unique_violation())`,
//! `is_foreign_key_violation()`, `is_check_violation()`, or SQLSTATE codes (`"23505"`, `"23503"`,
//! `"23502"`, `"23514"`) behave identically across PostgreSQL and SQLite with zero signature churn
//! on `AppResult` or `From<sqlx::Error> for AppError`.

use std::borrow::Cow;
use std::fmt;

use rusqlite::ffi;
use sqlx::error::{DatabaseError, ErrorKind};

use super::SqliteError;

#[derive(Debug, Clone, Copy)]
enum SqliteErrorKind {
    UniqueViolation,
    ForeignKeyViolation,
    NotNullViolation,
    CheckViolation,
    Other,
}

impl From<SqliteErrorKind> for ErrorKind {
    fn from(kind: SqliteErrorKind) -> Self {
        match kind {
            SqliteErrorKind::UniqueViolation => Self::UniqueViolation,
            SqliteErrorKind::ForeignKeyViolation => Self::ForeignKeyViolation,
            SqliteErrorKind::NotNullViolation => Self::NotNullViolation,
            SqliteErrorKind::CheckViolation => Self::CheckViolation,
            SqliteErrorKind::Other => Self::Other,
        }
    }
}

/// SQLite constraint/driver error implementing [`sqlx::error::DatabaseError`].
#[derive(Debug)]
pub struct SqliteDatabaseError {
    extended_code: i32,
    message: String,
    kind: SqliteErrorKind,
    sqlstate: Option<&'static str>,
    constraint: Option<String>,
}

impl SqliteDatabaseError {
    pub fn from_ffi(err: ffi::Error, msg: Option<String>) -> Self {
        let extended_code = err.extended_code;
        let message = msg.unwrap_or_else(|| err.to_string());
        let (kind, sqlstate) = classify_sqlite_error(extended_code, &message);
        let constraint = extract_constraint_name(&message);
        Self {
            extended_code,
            message,
            kind,
            sqlstate,
            constraint,
        }
    }

    pub fn extended_code(&self) -> i32 {
        self.extended_code
    }
}

fn classify_sqlite_error(
    extended_code: i32,
    message: &str,
) -> (SqliteErrorKind, Option<&'static str>) {
    match extended_code {
        ffi::SQLITE_CONSTRAINT_UNIQUE | ffi::SQLITE_CONSTRAINT_PRIMARYKEY => {
            (SqliteErrorKind::UniqueViolation, Some("23505"))
        }
        ffi::SQLITE_CONSTRAINT_FOREIGNKEY => (SqliteErrorKind::ForeignKeyViolation, Some("23503")),
        ffi::SQLITE_CONSTRAINT_NOTNULL => (SqliteErrorKind::NotNullViolation, Some("23502")),
        ffi::SQLITE_CONSTRAINT_CHECK => (SqliteErrorKind::CheckViolation, Some("23514")),
        _ => {
            let upper = message.to_ascii_uppercase();
            if upper.contains("UNIQUE CONSTRAINT FAILED")
                || upper.contains("PRIMARY KEY CONSTRAINT FAILED")
            {
                (SqliteErrorKind::UniqueViolation, Some("23505"))
            } else if upper.contains("FOREIGN KEY CONSTRAINT FAILED") {
                (SqliteErrorKind::ForeignKeyViolation, Some("23503"))
            } else if upper.contains("NOT NULL CONSTRAINT FAILED") {
                (SqliteErrorKind::NotNullViolation, Some("23502"))
            } else if upper.contains("CHECK CONSTRAINT FAILED") {
                (SqliteErrorKind::CheckViolation, Some("23514"))
            } else {
                (SqliteErrorKind::Other, None)
            }
        }
    }
}

fn extract_constraint_name(message: &str) -> Option<String> {
    let (_, rest) = message.split_once("constraint failed:")?;
    let trimmed = rest.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_owned())
    }
}

impl fmt::Display for SqliteDatabaseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "SQLite error (code {}): {}",
            self.extended_code, self.message
        )
    }
}

impl std::error::Error for SqliteDatabaseError {}

impl DatabaseError for SqliteDatabaseError {
    fn message(&self) -> &str {
        &self.message
    }

    fn code(&self) -> Option<Cow<'_, str>> {
        match self.sqlstate {
            Some(state) => Some(Cow::Borrowed(state)),
            None => Some(Cow::Owned(self.extended_code.to_string())),
        }
    }

    fn as_error(&self) -> &(dyn std::error::Error + Send + Sync + 'static) {
        self
    }

    fn as_error_mut(&mut self) -> &mut (dyn std::error::Error + Send + Sync + 'static) {
        self
    }

    fn into_error(self: Box<Self>) -> Box<dyn std::error::Error + Send + Sync + 'static> {
        self
    }

    fn constraint(&self) -> Option<&str> {
        self.constraint.as_deref()
    }

    fn kind(&self) -> ErrorKind {
        self.kind.into()
    }
}

/// Converts a `rusqlite::Error` into a `sqlx::Error` preserving row-not-found and database
/// constraint semantics (`UniqueViolation`, `ForeignKeyViolation`, `NotNullViolation`,
/// `CheckViolation`).
pub fn rusqlite_to_sqlx(err: rusqlite::Error) -> sqlx::Error {
    match err {
        rusqlite::Error::QueryReturnedNoRows => sqlx::Error::RowNotFound,
        rusqlite::Error::SqliteFailure(ffi_err, msg) => {
            sqlx::Error::Database(Box::new(SqliteDatabaseError::from_ffi(ffi_err, msg)))
        }
        rusqlite::Error::InvalidColumnName(name) => sqlx::Error::ColumnNotFound(name),
        other => sqlx::Error::Protocol(format!("sqlite: {other}")),
    }
}

/// Converts a [`SqliteError`] into a `sqlx::Error`.
pub fn sqlite_err_to_sqlx(err: SqliteError) -> sqlx::Error {
    match err {
        SqliteError::Rusqlite(e) => rusqlite_to_sqlx(e),
        SqliteError::Io(e) => sqlx::Error::Io(e),
        other => sqlx::Error::Protocol(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_unique_and_fk_constraints_to_sqlx_database_error() {
        let uniq = rusqlite_to_sqlx(rusqlite::Error::SqliteFailure(
            ffi::Error {
                code: ffi::ErrorCode::ConstraintViolation,
                extended_code: ffi::SQLITE_CONSTRAINT_UNIQUE,
            },
            Some("UNIQUE constraint failed: app_user.username".into()),
        ));
        let db_err = uniq.as_database_error().expect("database error");
        assert!(db_err.is_unique_violation());
        assert!(!db_err.is_foreign_key_violation());
        assert_eq!(db_err.code().as_deref(), Some("23505"));
        assert_eq!(db_err.constraint(), Some("app_user.username"));

        let fk = rusqlite_to_sqlx(rusqlite::Error::SqliteFailure(
            ffi::Error {
                code: ffi::ErrorCode::ConstraintViolation,
                extended_code: ffi::SQLITE_CONSTRAINT_FOREIGNKEY,
            },
            Some("FOREIGN KEY constraint failed".into()),
        ));
        let db_err = fk.as_database_error().expect("database error");
        assert!(db_err.is_foreign_key_violation());
        assert!(!db_err.is_unique_violation());
        assert_eq!(db_err.code().as_deref(), Some("23503"));

        let check = rusqlite_to_sqlx(rusqlite::Error::SqliteFailure(
            ffi::Error {
                code: ffi::ErrorCode::ConstraintViolation,
                extended_code: ffi::SQLITE_CONSTRAINT_CHECK,
            },
            Some("CHECK constraint failed: ck_budget_limit".into()),
        ));
        let db_err = check.as_database_error().expect("database error");
        assert!(db_err.is_check_violation());
        assert_eq!(db_err.code().as_deref(), Some("23514"));

        assert!(matches!(
            rusqlite_to_sqlx(rusqlite::Error::QueryReturnedNoRows),
            sqlx::Error::RowNotFound
        ));
    }
}
