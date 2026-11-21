//! InstanceStatusService and ReleaseCheckService: the admin instance check. The release check,
//! the session counts and the probes of the sidecars in use share one 3 s deadline (2 s per
//! probe); a failed probe is data, never an error response.

use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use axum::extract::State;
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::Value;

use crate::AppState;
use crate::error::AppResult;
use crate::setup_auth::settings;

const PROBE_TIMEOUT: Duration = Duration::from_secs(2);
const OVERALL_DEADLINE: Duration = Duration::from_secs(3);
const RELEASE_TIMEOUT: Duration = Duration::from_secs(5);
const CACHE_TTL: chrono::Duration = chrono::Duration::hours(6);
const GITHUB_API: &str = "https://api.github.com";
const REPOSITORY: &str = "Cloeille/picsou-finance";
const BASE_PYTHON: &str = "sidecar-python";
const BASE_CHROMIUM: &str = "sidecar-chromium";
const BASE_CAMOUFOX: &str = "sidecar-camoufox";

/// How a sidecar's sessions are counted: rows of sidecar_session for a bank, or a table.
#[derive(Clone, Copy)]
enum Sessions {
    Bank(&'static str),
    Table(&'static str),
}

/// The sidecar registry: name, expected base image, where its sessions live.
const SIDECARS: &[(&str, &str, Sessions)] = &[
    ("bourso-auth", BASE_PYTHON, Sessions::Bank("BOURSO")),
    ("sofidy-auth", BASE_PYTHON, Sessions::Bank("SOFIDY")),
    ("cmes-auth", BASE_PYTHON, Sessions::Bank("CMES")),
    ("cic-auth", BASE_PYTHON, Sessions::Bank("CIC")),
    ("natixis-auth", BASE_PYTHON, Sessions::Bank("NATIXIS")),
    (
        "degiro-auth",
        BASE_PYTHON,
        Sessions::Table("degiro_session"),
    ),
    ("tr-auth", BASE_CHROMIUM, Sessions::Bank("TRADE_REPUBLIC")),
    (
        "caisse-epargne-auth",
        BASE_CHROMIUM,
        Sessions::Bank("CAISSE_EPARGNE"),
    ),
    (
        "banque-populaire-auth",
        BASE_CHROMIUM,
        Sessions::Bank("BANQUE_POPULAIRE"),
    ),
    (
        "bourse-direct-auth",
        BASE_CHROMIUM,
        Sessions::Bank("BOURSE_DIRECT"),
    ),
    ("amundi-auth", BASE_CHROMIUM, Sessions::Bank("AMUNDI")),
    ("fortuneo-auth", BASE_CHROMIUM, Sessions::Bank("FORTUNEO")),
    ("corum-auth", BASE_CHROMIUM, Sessions::Bank("CORUM")),
    ("lbp-auth", BASE_CHROMIUM, Sessions::Table("lbp_session")),
    ("amex-auth", BASE_CAMOUFOX, Sessions::Bank("AMEX")),
    ("bnp-auth", BASE_CAMOUFOX, Sessions::Bank("BNP")),
    (
        "revolut-auth",
        BASE_CAMOUFOX,
        Sessions::Table("revolut_session"),
    ),
];

#[derive(Debug, Clone)]
struct Release {
    tag: String,
    url: String,
}

/// The latest release, cached six hours including failures, and the one-count-at-a-time flag.
#[derive(Default)]
pub struct Cache {
    release: Mutex<Option<(DateTime<Utc>, Option<Release>)>>,
    counting: AtomicBool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseUpdate {
    latest_version: String,
    release_url: String,
}

#[derive(Serialize, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum IssueCode {
    SidecarUnreachable,
    SidecarOutdated,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SidecarIssue {
    code: IssueCode,
    sidecar: &'static str,
    /// Always present, null when no base is declared.
    expected_base: Option<&'static str>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceStatusResponse {
    current_version: String,
    /// Always present, null when there is nothing to show.
    update: Option<ReleaseUpdate>,
    issues: Vec<SidecarIssue>,
}

fn semver(version: &str) -> Option<[u64; 3]> {
    let v = version.trim();
    let v = v.strip_prefix('v').unwrap_or(v);
    let mut parts = v.split('.');
    let mut out = [0u64; 3];
    for slot in &mut out {
        let part = parts.next()?;
        if part.is_empty() || !part.chars().all(|c| c.is_ascii_digit()) {
            return None;
        }
        *slot = part.parse().ok()?;
    }
    parts.next().is_none().then_some(out)
}

pub fn is_newer(latest: &str, current: &str) -> bool {
    match (semver(latest), semver(current)) {
        (Some(l), Some(c)) => l > c,
        _ => false,
    }
}

async fn fetch_release(state: &AppState) -> Result<Release, String> {
    let response = state
        .http
        .get(format!("{GITHUB_API}/repos/{REPOSITORY}/releases/latest"))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "picsou-finance")
        .timeout(RELEASE_TIMEOUT)
        .send()
        .await
        .map_err(|e| e.without_url().to_string())?;
    if !response.status().is_success() {
        return Err(format!(
            "github release status {}",
            response.status().as_u16()
        ));
    }
    let node: Value = response.json().await.map_err(|e| e.to_string())?;
    let text = |field: &str| {
        node.get(field)
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .map(str::to_owned)
    };
    let prefix = format!("https://github.com/{REPOSITORY}/releases/");
    match (text("tag_name"), text("html_url")) {
        (Some(tag), Some(url)) if url.starts_with(&prefix) => Ok(Release { tag, url }),
        _ => Err("github release missing tag_name or html_url".into()),
    }
}

async fn release_update(state: &AppState, current: &str) -> Option<ReleaseUpdate> {
    if !state.config.update_check_enabled || semver(current).is_none() {
        return None;
    }
    let now = state.clock.now();
    let cached = state
        .admin
        .status
        .release
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
        .filter(|(at, _)| now - *at < CACHE_TTL);
    let release = match cached {
        Some((_, release)) => release,
        None => {
            let fetched = fetch_release(state)
                .await
                .inspect_err(|e| tracing::info!("instance-status.release.fetch_failed err={e}"))
                .ok();
            *state
                .admin
                .status
                .release
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = Some((state.clock.now(), fetched.clone()));
            fetched
        }
    };
    release
        .filter(|r| is_newer(&r.tag, current))
        .map(|r| ReleaseUpdate {
            latest_version: r.tag,
            release_url: r.url,
        })
}

async fn session_count(state: &AppState, sessions: Sessions) -> i64 {
    let result = match sessions {
        Sessions::Bank(bank) => {
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM sidecar_session WHERE bank = $1")
                .bind(bank)
                .fetch_one(&state.db)
                .await
        }
        Sessions::Table(table) => {
            sqlx::query_scalar::<_, i64>(&format!("SELECT count(*) FROM {table}"))
                .fetch_one(&state.db)
                .await
        }
    };
    result.unwrap_or_else(|e| {
        tracing::warn!("instance-status.sidecar.count_failed err={e}");
        0
    })
}

async fn sidecar_url(state: &AppState, name: &str) -> String {
    let configured = state.config.sidecars.get(name).cloned().unwrap_or_default();
    if name == "bourso-auth" {
        return settings::read(state, settings::BOURSO_AUTH_URL)
            .await
            .ok()
            .flatten()
            .filter(|v| !v.trim().is_empty())
            .unwrap_or(configured);
    }
    configured
}

/// One in-use sidecar's health: unreachable, a non-2xx, or a base image other than expected.
async fn probe(
    state: &AppState,
    name: &'static str,
    expected: &'static str,
) -> Option<SidecarIssue> {
    let issue = |code| SidecarIssue {
        code,
        sidecar: name,
        expected_base: Some(expected),
    };
    let url = sidecar_url(state, name).await;
    if url.trim().is_empty() {
        return Some(issue(IssueCode::SidecarUnreachable));
    }
    if crate::native_sidecars::is_native_url(&url) {
        return state
            .connectors
            .native
            .resolve(name, &url)
            .is_none()
            .then(|| issue(IssueCode::SidecarUnreachable));
    }
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(PROBE_TIMEOUT)
        .timeout(PROBE_TIMEOUT)
        .build()
        .ok()?;
    let health = format!("{}/health", url.trim().trim_end_matches('/'));
    match client.get(&health).send().await {
        Ok(response) if response.status().is_success() => {
            let base = response
                .json::<Value>()
                .await
                .ok()
                .and_then(|v| {
                    v.get("base")
                        .and_then(Value::as_str)
                        .map(|s| s.trim().to_owned())
                })
                .filter(|s| !s.is_empty());
            (base.as_deref() != Some(expected)).then(|| issue(IssueCode::SidecarOutdated))
        }
        Ok(response) => {
            tracing::info!(
                "instance-status.sidecar.unreachable name={name} status={}",
                response.status().as_u16()
            );
            Some(issue(IssueCode::SidecarUnreachable))
        }
        Err(e) => {
            tracing::info!(
                "instance-status.sidecar.unreachable name={name} err={}",
                e.without_url()
            );
            Some(issue(IssueCode::SidecarUnreachable))
        }
    }
}

async fn probe_in_use(state: &AppState) -> Vec<SidecarIssue> {
    let cache = &state.admin.status;
    if cache.counting.swap(true, Ordering::AcqRel) {
        tracing::info!("instance-status.counts.busy");
        return Vec::new();
    }
    let mut in_use = Vec::new();
    for (name, base, sessions) in SIDECARS {
        if session_count(state, *sessions).await > 0 {
            in_use.push((*name, *base));
        }
    }
    cache.counting.store(false, Ordering::Release);
    let probes = in_use
        .into_iter()
        .map(|(name, base)| probe(state, name, base));
    let mut issues: Vec<SidecarIssue> = futures::future::join_all(probes)
        .await
        .into_iter()
        .flatten()
        .collect();
    issues.sort_by(|a, b| a.sidecar.cmp(b.sidecar));
    issues
}

pub async fn instance_status(
    State(state): State<AppState>,
) -> AppResult<axum::Json<InstanceStatusResponse>> {
    let current = if state.config.app_version.trim().is_empty() {
        "dev".to_owned()
    } else {
        state.config.app_version.trim().to_owned()
    };
    let (update, issues) = tokio::join!(
        tokio::time::timeout(OVERALL_DEADLINE, release_update(&state, &current)),
        tokio::time::timeout(OVERALL_DEADLINE, probe_in_use(&state)),
    );
    if issues.is_err() {
        state.admin.status.counting.store(false, Ordering::Release);
    }
    Ok(axum::Json(InstanceStatusResponse {
        current_version: current,
        update: update.ok().flatten(),
        issues: issues.unwrap_or_default(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_plain_semver_only() {
        assert!(is_newer("v1.3.0", "1.2.9"));
        assert!(!is_newer("1.2.0", "1.2.0"));
        assert!(!is_newer("1.10.0-rc1", "1.2.0"));
        assert!(!is_newer("1.3.0", "dev"));
        assert!(is_newer("1.10.0", "1.9.9"));
    }
}
