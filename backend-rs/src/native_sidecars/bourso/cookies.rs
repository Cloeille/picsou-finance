//! Per-session cookie jar preserving each cookie's domain and path across
//! `clients.boursobank.com` and `api.boursobank.com`, wire-compatible with
//! `services/bourso-auth`'s `{"cookies":[...]}` sessionState JSON.

use std::sync::RwLock;

use http::{HeaderValue, StatusCode};
use reqwest::cookie::CookieStore;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use url::Url;

use crate::native_sidecars::NativeError;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredCookie {
    pub name: String,
    pub value: String,
    pub domain: String,
    pub path: String,
}

#[derive(Debug, Default)]
pub struct SessionCookieJar {
    cookies: RwLock<Vec<StoredCookie>>,
}

impl SessionCookieJar {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set(&self, name: impl Into<String>, value: impl Into<String>, domain: &str, path: &str) {
        let name = name.into();
        let value = value.into();
        let domain = domain.to_owned();
        let path = if path.is_empty() {
            "/".to_owned()
        } else {
            path.to_owned()
        };
        let mut guard = self
            .cookies
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(existing) = guard
            .iter_mut()
            .find(|c| c.name == name && c.domain.eq_ignore_ascii_case(&domain) && c.path == path)
        {
            existing.value = value;
        } else {
            guard.push(StoredCookie {
                name,
                value,
                domain,
                path,
            });
        }
    }

    pub fn remove(&self, name: &str, domain: &str, path: &str) {
        let mut guard = self
            .cookies
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.retain(|c| {
            !(c.name == name && c.domain.eq_ignore_ascii_case(domain) && c.path == path)
        });
    }

    pub fn get(&self, name: &str) -> Option<String> {
        let guard = self
            .cookies
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        guard
            .iter()
            .rev()
            .find(|c| c.name == name)
            .map(|c| c.value.clone())
    }

    pub fn snapshot(&self) -> Vec<StoredCookie> {
        self.cookies
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }

    /// Serialize the cookie jar as compact `{"cookies":[{"name":...,"value":...,"domain":...,"path":...}]}`.
    pub fn serialize_json(&self) -> String {
        let cookies = self.snapshot();
        serde_json::to_string(&serde_json::json!({ "cookies": cookies }))
            .expect("cookie serialization cannot fail")
    }

    /// Restore cookies from a serialized `sessionState` JSON string.
    ///
    /// Rejects non-JSON, non-object, empty cookie lists, or malformed entries with `400 INVALID_DATA`.
    pub fn restore_json(&self, raw: &str) -> Result<(), NativeError> {
        let invalid = || NativeError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
        let decoded: Value = serde_json::from_str(raw).map_err(|_| invalid())?;
        let obj = decoded.as_object().ok_or_else(invalid)?;
        let list = obj
            .get("cookies")
            .and_then(Value::as_array)
            .filter(|a| !a.is_empty())
            .ok_or_else(invalid)?;
        let mut parsed = Vec::with_capacity(list.len());
        for entry in list {
            let entry = entry.as_object().ok_or_else(invalid)?;
            let name = entry
                .get("name")
                .and_then(Value::as_str)
                .ok_or_else(invalid)?;
            let value = json_field_as_string(entry.get("value"), "");
            let domain = json_field_as_string(entry.get("domain"), "");
            let raw_path = json_field_as_string(entry.get("path"), "/");
            let path = if raw_path.is_empty() {
                "/".to_owned()
            } else {
                raw_path
            };
            parsed.push(StoredCookie {
                name: name.to_owned(),
                value,
                domain,
                path,
            });
        }
        for cookie in parsed {
            self.set(cookie.name, cookie.value, &cookie.domain, &cookie.path);
        }
        Ok(())
    }
}

fn json_field_as_string(value: Option<&Value>, default: &str) -> String {
    match value {
        None | Some(Value::Null) => default.to_owned(),
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

impl CookieStore for SessionCookieJar {
    fn set_cookies(&self, cookie_headers: &mut dyn Iterator<Item = &HeaderValue>, url: &Url) {
        let default_host = url.host_str().unwrap_or("").to_ascii_lowercase();
        for header in cookie_headers {
            let Ok(raw) = header.to_str() else {
                continue;
            };
            let mut parts = raw.split(';');
            let Some(name_value) = parts.next() else {
                continue;
            };
            let Some((raw_name, raw_value)) = name_value.split_once('=') else {
                continue;
            };
            let name = raw_name.trim();
            if name.is_empty() {
                continue;
            }
            let value = raw_value.trim();
            let mut domain: Option<String> = None;
            let mut path = "/".to_owned();
            let mut expired = false;

            for attr in parts {
                let attr = attr.trim();
                if attr.is_empty() {
                    continue;
                }
                if let Some((k, v)) = attr.split_once('=') {
                    let key = k.trim();
                    let val = v.trim();
                    if key.eq_ignore_ascii_case("domain") && !val.is_empty() {
                        let stripped = val.trim_start_matches('.').to_ascii_lowercase();
                        if !stripped.is_empty() {
                            domain = Some(format!(".{stripped}"));
                        }
                    } else if key.eq_ignore_ascii_case("path") && !val.is_empty() {
                        path = val.to_owned();
                    } else if key.eq_ignore_ascii_case("max-age")
                        && let Ok(secs) = val.parse::<i64>()
                        && secs <= 0
                    {
                        expired = true;
                    }
                }
            }

            let target_domain = domain.unwrap_or_else(|| default_host.clone());
            if expired {
                self.remove(name, &target_domain, &path);
            } else {
                self.set(name, value, &target_domain, &path);
            }
        }
    }

    fn cookies(&self, url: &Url) -> Option<HeaderValue> {
        let host = url.host_str().unwrap_or("").to_ascii_lowercase();
        let req_path = url.path();
        let guard = self
            .cookies
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let pairs: Vec<String> = guard
            .iter()
            .filter(|c| domain_matches(&c.domain, &host) && path_matches(&c.path, req_path))
            .map(|c| format!("{}={}", c.name, c.value))
            .collect();
        if pairs.is_empty() {
            return None;
        }
        HeaderValue::from_str(&pairs.join("; ")).ok()
    }
}

fn domain_matches(cookie_domain: &str, request_host: &str) -> bool {
    if cookie_domain.is_empty() {
        return true;
    }
    let cd = cookie_domain.to_ascii_lowercase();
    if let Some(bare) = cd.strip_prefix('.') {
        request_host == bare || request_host.ends_with(&cd)
    } else {
        request_host == cd
    }
}

fn path_matches(cookie_path: &str, request_path: &str) -> bool {
    if cookie_path.is_empty() || cookie_path == "/" {
        return true;
    }
    let trimmed = cookie_path.trim_end_matches('/');
    request_path == trimmed
        || request_path == cookie_path
        || request_path
            .strip_prefix(trimmed)
            .is_some_and(|rest| rest.starts_with('/'))
}
