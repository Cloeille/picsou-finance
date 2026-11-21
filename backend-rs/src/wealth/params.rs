//! @RequestParam binding as Spring MVC does it: a missing optional value takes its default, a
//! value of the wrong type answers 400 "Failed to convert ...".

use std::collections::HashMap;
use std::str::FromStr;

use crate::error::AppError;

pub type Query = axum::extract::Query<HashMap<String, String>>;

fn mismatch(name: &str, value: &str) -> AppError {
    AppError::BadRequest(format!("Failed to convert '{name}' with value: '{value}'"))
}

/// An optional parameter; an empty value is absent, as Spring's conversion treats it.
pub fn optional<T: FromStr>(
    query: &HashMap<String, String>,
    name: &str,
) -> Result<Option<T>, AppError> {
    match query.get(name).map(|v| v.trim()) {
        None | Some("") => Ok(None),
        Some(value) => value.parse().map(Some).map_err(|_| mismatch(name, value)),
    }
}

/// A parameter with a defaultValue.
pub fn or_default<T: FromStr>(
    query: &HashMap<String, String>,
    name: &str,
    default: T,
) -> Result<T, AppError> {
    Ok(optional(query, name)?.unwrap_or(default))
}

/// A required parameter.
pub fn required(query: &HashMap<String, String>, name: &str) -> Result<String, AppError> {
    query.get(name).cloned().ok_or_else(|| {
        AppError::BadRequest(format!("Required parameter '{name}' is not present."))
    })
}
