//! Native in-process Rust implementations of Picsou's pure-HTTP bank sidecars.
//!
//! Each sidecar implements [`NativeSidecar`], matching the exact HTTP routes, JSON payloads,
//! status codes, and `{"detail": "..."}` error contract of its Python FastAPI counterpart.
//! When `<BANK>_AUTH_URL` is configured as `"native"` or `"native://<service>"`,
//! [`crate::connectors::sidecar::SidecarHttp`] dispatches directly in-process with zero
//! network hop.

pub mod cic;
pub mod cmes;
pub mod common;
pub mod degiro;
pub mod euro_info;
pub mod sofidy;

use std::collections::HashMap;
use std::sync::Arc;

use async_trait::async_trait;
use http::{Method, StatusCode};
use serde_json::{Value, json};

use crate::config::Config;

/// An error returned by a native sidecar, matching FastAPI's HTTPException status + `{"detail": "..."}`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NativeSidecarError {
    pub status: StatusCode,
    pub detail: String,
}

impl NativeSidecarError {
    pub fn new(status: StatusCode, detail: impl Into<String>) -> Self {
        Self {
            status,
            detail: detail.into(),
        }
    }

    pub fn to_json_body(&self) -> Value {
        json!({ "detail": self.detail })
    }
}

/// Dispatch interface implemented by every native in-process bank sidecar.
#[async_trait]
pub trait NativeSidecar: Send + Sync {
    async fn call(
        &self,
        method: Method,
        path: &str,
        body: Value,
    ) -> Result<Value, NativeSidecarError>;
}

/// Process-local registry of native sidecar instances (holding each bank's pending MFA store).
#[derive(Clone, Default)]
pub struct NativeSidecars {
    handlers: HashMap<&'static str, Arc<dyn NativeSidecar>>,
}

impl NativeSidecars {
    pub fn from_config(config: &Config) -> Self {
        let mut registry = Self::default();
        registry.register(
            "degiro-auth",
            Arc::new(degiro::NativeDegiro::with_base_url(
                config.native_sidecar_url("degiro"),
            )),
        );
        registry.register(
            "cmes-auth",
            Arc::new(cmes::NativeCmes::with_base_url(
                config.native_sidecar_url("cmes"),
            )),
        );
        registry.register(
            "sofidy-auth",
            Arc::new(sofidy::NativeSofidy::with_base_url(
                config.native_sidecar_url("sofidy"),
            )),
        );
        registry.register(
            "cic-auth",
            Arc::new(cic::NativeCic::with_base_url(
                config.native_sidecar_url("cic"),
            )),
        );
        registry
    }

    pub fn register(&mut self, service: &'static str, handler: Arc<dyn NativeSidecar>) {
        self.handlers.insert(service, handler);
    }

    pub fn get(&self, service: &str) -> Option<Arc<dyn NativeSidecar>> {
        let canonical = canonical_service_name(service);
        self.handlers.get(canonical).cloned()
    }

    /// Resolves a `"native"` or `"native://<target>"` sidecar URL to its registered handler.
    pub fn resolve(&self, service: &str, configured_url: &str) -> Option<Arc<dyn NativeSidecar>> {
        let target = native_target(service, configured_url)?;
        self.get(target)
    }
}

/// Returns `true` if `url` asks for in-process native dispatch (`"native"` or `"native://..."`).
pub fn is_native_url(url: &str) -> bool {
    let trimmed = url.trim();
    trimmed.eq_ignore_ascii_case("native") || trimmed.to_ascii_lowercase().starts_with("native://")
}

/// Extracts the target service name from `"native"` or `"native://<target>"`.
pub fn native_target<'a>(default_service: &'a str, url: &'a str) -> Option<&'a str> {
    let trimmed = url.trim();
    if trimmed.eq_ignore_ascii_case("native") {
        return Some(default_service);
    }
    let rest = trimmed
        .strip_prefix("native://")
        .or_else(|| trimmed.strip_prefix("NATIVE://"))?;
    let target = rest.trim_end_matches('/');
    if target.is_empty() {
        Some(default_service)
    } else {
        Some(target)
    }
}

fn canonical_service_name(name: &str) -> &str {
    match name {
        "degiro" | "degiro-auth" => "degiro-auth",
        "cmes" | "cmes-auth" => "cmes-auth",
        "sofidy" | "sofidy-auth" => "sofidy-auth",
        "cic" | "cic-auth" => "cic-auth",
        "natixis" | "natixis-auth" => "natixis-auth",
        "bourso" | "bourso-auth" => "bourso-auth",
        other => other,
    }
}
