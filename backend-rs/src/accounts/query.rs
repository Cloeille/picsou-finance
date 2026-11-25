//! @RequestParam binding as Spring MVC does it: a missing required parameter is a 400 "Required
//! parameter 'x' is not present.", a value that does not convert a 400 "Failed to convert ...".

use std::collections::HashMap;

use chrono::NaiveDate;

use crate::error::AppError;

#[derive(Debug, Default)]
pub struct Params(HashMap<String, Vec<String>>);

impl Params {
    pub fn parse(raw: Option<&str>) -> Self {
        let mut map: HashMap<String, Vec<String>> = HashMap::new();
        for (key, value) in url::form_urlencoded::parse(raw.unwrap_or_default().as_bytes()) {
            map.entry(key.into_owned())
                .or_default()
                .push(value.into_owned());
        }
        Self(map)
    }

    fn first(&self, name: &str) -> Option<&str> {
        self.0.get(name).and_then(|v| v.first()).map(String::as_str)
    }

    fn mismatch(name: &str, value: &str) -> AppError {
        AppError::BadRequest(format!("Failed to convert '{name}' with value: '{value}'"))
    }

    fn missing(name: &str) -> AppError {
        AppError::BadRequest(format!("Required parameter '{name}' is not present."))
    }

    pub fn string(&self, name: &str) -> Option<String> {
        self.first(name).map(str::to_owned)
    }

    /// A String parameter without a default: present even when empty.
    pub fn required_string(&self, name: &str) -> Result<String, AppError> {
        self.string(name).ok_or_else(|| Self::missing(name))
    }

    /// A boolean with a defaultValue: an empty value takes the default, as Spring does.
    pub fn boolean(&self, name: &str, default: bool) -> Result<bool, AppError> {
        match self.first(name) {
            None => Ok(default),
            Some(v) if v.trim().is_empty() => Ok(default),
            Some(v) => match v.trim().to_ascii_lowercase().as_str() {
                "true" | "on" | "yes" | "1" => Ok(true),
                "false" | "off" | "no" | "0" => Ok(false),
                _ => Err(Self::mismatch(name, v)),
            },
        }
    }

    pub fn int(&self, name: &str, default: i32) -> Result<i32, AppError> {
        match self.first(name) {
            None => Ok(default),
            Some(v) if v.trim().is_empty() => Ok(default),
            Some(v) => v.trim().parse().map_err(|_| Self::mismatch(name, v)),
        }
    }

    /// An optional ISO date; an empty value is null.
    pub fn date(&self, name: &str) -> Result<Option<NaiveDate>, AppError> {
        match self.first(name) {
            None => Ok(None),
            Some(v) if v.trim().is_empty() => Ok(None),
            Some(v) => NaiveDate::parse_from_str(v.trim(), "%Y-%m-%d")
                .map(Some)
                .map_err(|_| Self::mismatch(name, v)),
        }
    }

    /// A required List<Long>: repeated parameters and comma-separated values both bind.
    pub fn required_longs(&self, name: &str) -> Result<Vec<i64>, AppError> {
        let values = self.0.get(name).ok_or_else(|| Self::missing(name))?;
        let joined = values.join(",");
        let mut ids = Vec::new();
        for part in joined.split(',') {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }
            ids.push(part.parse().map_err(|_| Self::mismatch(name, &joined))?);
        }
        Ok(ids)
    }
}
