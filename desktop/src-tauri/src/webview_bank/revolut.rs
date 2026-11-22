//! Revolut native WebView connector.
//!
//! Opens a real OS WebView (`WKWebView` on macOS, `WebView2` on Windows, `WebKitGTK` on Linux)
//! directly on `https://app.revolut.com/` so the user authenticates naturally (phone/email,
//! passcode, mobile app approval, any captcha — in a real OS browser). Picsou never sees
//! credentials. Once the retail `/api/retail/token/info` probe confirms the user is signed in,
//! read-only retail API endpoints are fetched inside that WebView, parsed in Rust via
//! `revolut_parsers`, cached per member for `POST /sync` / `POST /accounts`, and the login
//! window closes cleanly.

use std::collections::HashMap;
use std::future::Future;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::http::StatusCode;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tauri::{
    AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use uuid::Uuid;

use super::bnp::{
    build_cookie_header, merge_set_cookies, random_hex, unwrap_eval_string, ApiError, StoredCookie,
    WebviewStartResponse, WebviewStatusResponse,
};
use super::revolut_parsers::{self, RevolutHarvestPayload};
use super::{
    document_ready_complete, is_allowed_https_navigation, is_domain_or_subdomain,
    poll_webview_slot, read_capped_text, EvalPollError, PENDING_SENTINEL,
};

const APP_URL: &str = "https://app.revolut.com/";
const API_HOST: &str = "app.revolut.com";
const WINDOW_TITLE: &str = "Connexion — Revolut";

const TOKEN_INFO_PATH: &str = "/api/retail/token/info";
const TOKEN_REFRESH_PATH: &str = "/api/retail/token";
const WALLETS_PATH: &str = "/api/retail/wallets";
const SINGLE_WALLET_PATH: &str = "/api/retail/user/current/wallet";
const MONEY_BOXES_PATH: &str = "/api/retail/user/current/money-boxes";
const CURRENCIES_PATH: &str = "/api/retail/currencies";
const ACCOUNT_DETAILS_PATH: &str = "/api/retail/bank-accounts/account-details";
const TRANSACTIONS_PATH: &str = "/api/retail/user/current/transactions/last";

const LOGIN_TTL: Duration = Duration::from_secs(300);
const POLL_INTERVAL: Duration = Duration::from_millis(600);
const FETCH_POLL_INTERVAL: Duration = Duration::from_millis(50);
const PROBE_TIMEOUT: Duration = Duration::from_secs(3);
const FETCH_TIMEOUT: Duration = Duration::from_secs(25);
const SNAPSHOT_CACHE_TTL: Duration = Duration::from_secs(300);
const MAX_PROCESSES: usize = 4;
const MAX_CACHE_ENTRIES: usize = 16;
const MAX_SESSIONS: usize = 64;
const MAX_PROGRESS: usize = 64;
const MAX_RESPONSE_BYTES: usize = 8_000_000;
const REFRESH_USER_AGENT: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";

#[derive(Debug, Clone)]
enum ProcessStatus {
    WaitingLogin,
    Fetching,
    Done,
    Cancelled,
    Failed { error_code: &'static str },
}

struct ProcessEntry {
    status: ProcessStatus,
    window_label: String,
    created_at: Instant,
}

struct CachedSnapshot {
    expires_at: Instant,
    payload: RevolutHarvestPayload,
}

struct StoredRevolutSession {
    device_id: String,
    cookies: Vec<StoredCookie>,
    updated_at: Instant,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProgressSnapshot {
    pub phase: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub accounts_found: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<u64>,
}

struct FetchResponse {
    status: u16,
    data: Option<Value>,
}

pub struct RevolutWebviewManager {
    processes: Mutex<HashMap<String, ProcessEntry>>,
    snapshots: Mutex<HashMap<String, CachedSnapshot>>,
    sessions: Mutex<HashMap<String, StoredRevolutSession>>,
    progress: Mutex<HashMap<String, ProgressSnapshot>>,
    http: reqwest::Client,
}

impl RevolutWebviewManager {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .user_agent(REFRESH_USER_AGENT)
            .build()
            .expect("reqwest client");
        Self {
            processes: Mutex::new(HashMap::new()),
            snapshots: Mutex::new(HashMap::new()),
            sessions: Mutex::new(HashMap::new()),
            progress: Mutex::new(HashMap::new()),
            http,
        }
    }

    pub async fn start_login(
        self: &Arc<Self>,
        app: AppHandle,
        member_id: &str,
    ) -> Result<WebviewStartResponse, ApiError> {
        let member_key = normalize_member_key(member_id);

        let stale_labels: Vec<String> = {
            let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
            let now = Instant::now();
            procs.retain(|_, p| {
                now.duration_since(p.created_at) < LOGIN_TTL + Duration::from_secs(60)
            });
            let mut labels = Vec::new();
            for entry in procs.values_mut() {
                if matches!(
                    entry.status,
                    ProcessStatus::WaitingLogin | ProcessStatus::Fetching
                ) {
                    entry.status = ProcessStatus::Cancelled;
                    labels.push(entry.window_label.clone());
                }
            }
            while procs.len() >= MAX_PROCESSES {
                if let Some(oldest) = procs
                    .iter()
                    .min_by_key(|(_, p)| p.created_at)
                    .map(|(k, _)| k.clone())
                {
                    procs.remove(&oldest);
                } else {
                    break;
                }
            }
            labels
        };
        for label in stale_labels {
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.destroy();
            }
        }

        let process_id = Uuid::new_v4().simple().to_string();
        let window_label = format!("picsou-revolut-{process_id}");
        let entry_url = Url::parse(APP_URL)
            .map_err(|_| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"))?;

        {
            let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
            procs.insert(
                process_id.clone(),
                ProcessEntry {
                    status: ProcessStatus::WaitingLogin,
                    window_label: window_label.clone(),
                    created_at: Instant::now(),
                },
            );
        }
        self.set_progress(&member_key, "LOGGING_IN", None);

        let app_for_build = app.clone();
        let label_for_build = window_label.clone();
        let manager_for_close = Arc::clone(self);
        let pid_for_close = process_id.clone();

        let window = tokio::task::spawn_blocking(move || {
            let win = WebviewWindowBuilder::new(
                &app_for_build,
                &label_for_build,
                WebviewUrl::External(entry_url),
            )
            .title(WINDOW_TITLE)
            .inner_size(1040.0, 780.0)
            .min_inner_size(720.0, 560.0)
            .center()
            .incognito(true)
            .visible(true)
            .focused(true)
            .on_navigation(|url| {
                let allowed = is_allowed_navigation_url(url);
                if !allowed {
                    tracing::warn!(
                        "Revolut WebView blocked navigation to scheme={} host={:?}",
                        url.scheme(),
                        url.host_str()
                    );
                }
                allowed
            })
            .build()?;
            win.on_window_event(move |event| {
                if matches!(
                    event,
                    WindowEvent::CloseRequested { .. } | WindowEvent::Destroyed
                ) {
                    manager_for_close.mark_cancelled_if_active(&pid_for_close);
                }
            });
            Ok::<WebviewWindow, tauri::Error>(win)
        })
        .await
        .map_err(|_| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"))?
        .map_err(|_| {
            self.mark_failed(&process_id, "UPSTREAM_UNAVAILABLE");
            ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
        })?;

        tracing::info!("Revolut WebView login opened");

        let manager = Arc::clone(self);
        let pid = process_id.clone();
        tokio::spawn(async move {
            manager.watch_login(pid, member_key, window).await;
        });

        Ok(WebviewStartResponse {
            process_id,
            status: "WAITING_LOGIN",
        })
    }

    pub fn get_status(&self, process_id: &str) -> Result<WebviewStatusResponse, ApiError> {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        let Some(entry) = procs.get_mut(process_id) else {
            return Err(ApiError::new(StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED"));
        };
        if matches!(entry.status, ProcessStatus::WaitingLogin)
            && entry.created_at.elapsed() >= LOGIN_TTL
        {
            entry.status = ProcessStatus::Failed {
                error_code: "AUTH_ATTEMPT_EXPIRED",
            };
        }
        Ok(match &entry.status {
            ProcessStatus::WaitingLogin => WebviewStatusResponse {
                status: "WAITING_LOGIN",
                session_state: None,
                error_code: None,
            },
            ProcessStatus::Fetching => WebviewStatusResponse {
                status: "FETCHING",
                session_state: None,
                error_code: None,
            },
            ProcessStatus::Done => WebviewStatusResponse {
                status: "DONE",
                session_state: None,
                error_code: None,
            },
            ProcessStatus::Cancelled => WebviewStatusResponse {
                status: "CANCELLED",
                session_state: None,
                error_code: None,
            },
            ProcessStatus::Failed { error_code } => WebviewStatusResponse {
                status: "FAILED",
                session_state: None,
                error_code: Some((*error_code).to_owned()),
            },
        })
    }

    pub fn cancel(&self, app: &AppHandle, process_id: &str) -> bool {
        let label = {
            let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
            let Some(entry) = procs.get_mut(process_id) else {
                return false;
            };
            if matches!(
                entry.status,
                ProcessStatus::WaitingLogin | ProcessStatus::Fetching
            ) {
                entry.status = ProcessStatus::Cancelled;
            }
            entry.window_label.clone()
        };
        if let Some(win) = app.get_webview_window(&label) {
            let _ = win.destroy();
        }
        true
    }

    pub fn close_all(&self, app: &AppHandle) {
        let labels: Vec<String> = {
            let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
            procs
                .values_mut()
                .map(|entry| {
                    if matches!(
                        entry.status,
                        ProcessStatus::WaitingLogin | ProcessStatus::Fetching
                    ) {
                        entry.status = ProcessStatus::Cancelled;
                    }
                    entry.window_label.clone()
                })
                .collect()
        };
        for label in labels {
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.destroy();
            }
        }
    }

    pub fn get_progress(&self, member_id: &str) -> ProgressSnapshot {
        let key = normalize_member_key(member_id);
        self.progress
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&key)
            .cloned()
            .unwrap_or(ProgressSnapshot {
                phase: None,
                accounts_found: None,
                updated_at: None,
            })
    }

    /// Clears all in-memory Revolut state (cookies, cached snapshot, and progress) for `member_id`.
    pub fn clear_member(&self, member_id: &str) -> bool {
        let key = normalize_member_key(member_id);
        let removed_session = self
            .sessions
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&key)
            .is_some();
        let removed_snapshot = self
            .snapshots
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&key)
            .is_some();
        let removed_progress = self
            .progress
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&key)
            .is_some();
        removed_session || removed_snapshot || removed_progress
    }

    fn store_session(&self, member_key: String, device_id: String, cookies: Vec<StoredCookie>) {
        let mut sessions = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
        if !sessions.contains_key(&member_key) {
            while sessions.len() >= MAX_SESSIONS {
                if let Some(oldest) = sessions
                    .iter()
                    .min_by_key(|(_, s)| s.updated_at)
                    .map(|(k, _)| k.clone())
                {
                    sessions.remove(&oldest);
                } else {
                    break;
                }
            }
        }
        sessions.insert(
            member_key,
            StoredRevolutSession {
                device_id,
                cookies,
                updated_at: Instant::now(),
            },
        );
    }

    /// Serves `POST /revolut/sync` and `POST /revolut/accounts`: returns a fresh in-memory
    /// snapshot captured from the native WebView, or replays stored retail cookies over `reqwest`
    /// if the session is still alive, returning `401 SESSION_EXPIRED` when re-login is needed.
    pub async fn sync_member(&self, member_id: &str) -> Result<RevolutHarvestPayload, ApiError> {
        let key = normalize_member_key(member_id);
        if let Some(payload) = self.take_cached_snapshot(&key) {
            self.set_progress(
                &key,
                "HARVESTING",
                i32::try_from(payload.accounts.len()).ok(),
            );
            return Ok(payload);
        }

        let stored = {
            let guard = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
            guard
                .get(&key)
                .map(|s| (s.device_id.clone(), s.cookies.clone()))
        };
        let Some((device_id, cookies)) = stored else {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        };
        if cookies.is_empty() || device_id.is_empty() {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }

        self.set_progress(&key, "CHECKING_SESSION", None);
        let jar = Arc::new(Mutex::new(cookies));

        let refresh = self
            .http_api_call(&jar, &device_id, "PUT", TOKEN_REFRESH_PATH, &[])
            .await;
        if refresh.status == 401 || refresh.status == 403 {
            self.sessions
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&key);
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }

        let info = self
            .http_api_call(&jar, &device_id, "GET", TOKEN_INFO_PATH, &[])
            .await;
        if info.status != 200 {
            if info.status == 401 || info.status == 403 {
                self.sessions
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&key);
                return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
            }
            return Err(ApiError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }

        self.set_progress(&key, "HARVESTING", Some(0));
        let now_ms = Utc::now().timestamp_millis();
        let payload = harvest_accounts_with(
            now_ms,
            |method, path, params| {
                let jar = Arc::clone(&jar);
                let device_id = device_id.clone();
                let method = method.to_owned();
                let path = path.to_owned();
                let params: Vec<(String, String)> = params
                    .iter()
                    .map(|(k, v)| ((*k).to_owned(), v.clone()))
                    .collect();
                async move {
                    self.http_api_call(&jar, &device_id, &method, &path, &params)
                        .await
                }
            },
            |count| self.set_progress(&key, "HARVESTING", i32::try_from(count).ok()),
        )
        .await?;

        let updated_cookies: Vec<StoredCookie> = jar
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .filter(|c| cookie_in_scope(c))
            .cloned()
            .collect();
        self.store_session(key, device_id, updated_cookies);
        Ok(payload)
    }

    fn set_progress(&self, member_key: &str, phase: &str, accounts_found: Option<i32>) {
        let updated_at = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .ok();
        let mut progress = self.progress.lock().unwrap_or_else(|e| e.into_inner());
        if !progress.contains_key(member_key) {
            while progress.len() >= MAX_PROGRESS {
                if let Some(oldest) = progress
                    .iter()
                    .min_by_key(|(_, p)| p.updated_at.unwrap_or(0))
                    .map(|(k, _)| k.clone())
                {
                    progress.remove(&oldest);
                } else {
                    break;
                }
            }
        }
        progress.insert(
            member_key.to_owned(),
            ProgressSnapshot {
                phase: Some(phase.to_owned()),
                accounts_found,
                updated_at,
            },
        );
    }

    fn mark_cancelled_if_active(&self, process_id: &str) {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = procs.get_mut(process_id) {
            if matches!(
                entry.status,
                ProcessStatus::WaitingLogin | ProcessStatus::Fetching
            ) {
                entry.status = ProcessStatus::Cancelled;
                tracing::info!("Revolut WebView login cancelled by user");
            }
        }
    }

    fn mark_fetching(&self, process_id: &str) -> bool {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        let Some(entry) = procs.get_mut(process_id) else {
            return false;
        };
        if matches!(entry.status, ProcessStatus::WaitingLogin) {
            entry.status = ProcessStatus::Fetching;
            true
        } else {
            false
        }
    }

    fn mark_done(&self, process_id: &str) {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = procs.get_mut(process_id) {
            if !matches!(entry.status, ProcessStatus::Cancelled) {
                entry.status = ProcessStatus::Done;
            }
        }
    }

    fn mark_failed(&self, process_id: &str, error_code: &'static str) {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = procs.get_mut(process_id) {
            if matches!(
                entry.status,
                ProcessStatus::WaitingLogin | ProcessStatus::Fetching
            ) {
                entry.status = ProcessStatus::Failed { error_code };
            }
        }
    }

    fn is_waiting(&self, process_id: &str) -> bool {
        let procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        procs
            .get(process_id)
            .is_some_and(|e| matches!(e.status, ProcessStatus::WaitingLogin))
    }

    fn remember_snapshot(&self, member_key: &str, payload: RevolutHarvestPayload) {
        let now = Instant::now();
        let mut cache = self.snapshots.lock().unwrap_or_else(|e| e.into_inner());
        cache.retain(|_, v| v.expires_at > now);
        while cache.len() >= MAX_CACHE_ENTRIES {
            if let Some(oldest) = cache
                .iter()
                .min_by_key(|(_, v)| v.expires_at)
                .map(|(k, _)| k.clone())
            {
                cache.remove(&oldest);
            } else {
                break;
            }
        }
        cache.insert(
            member_key.to_owned(),
            CachedSnapshot {
                expires_at: now + SNAPSHOT_CACHE_TTL,
                payload,
            },
        );
    }

    fn take_cached_snapshot(&self, member_key: &str) -> Option<RevolutHarvestPayload> {
        let mut cache = self.snapshots.lock().unwrap_or_else(|e| e.into_inner());
        let entry = cache.remove(member_key)?;
        if entry.expires_at > Instant::now() {
            Some(entry.payload)
        } else {
            None
        }
    }

    async fn watch_login(&self, process_id: String, member_key: String, window: WebviewWindow) {
        let deadline = Instant::now() + LOGIN_TTL;
        loop {
            if !self.is_waiting(&process_id) {
                return;
            }
            if Instant::now() >= deadline {
                tracing::warn!("Revolut WebView login timed out");
                self.mark_failed(&process_id, "AUTH_ATTEMPT_EXPIRED");
                let _ = window.destroy();
                return;
            }

            let current_url = match window.url() {
                Ok(url) => url,
                Err(_) => {
                    self.mark_cancelled_if_active(&process_id);
                    return;
                }
            };

            if on_revolut_home(&current_url) {
                let path = current_url.path();
                if !document_ready_complete(&window).await {
                    tracing::debug!(
                        path,
                        "Revolut WebView waiting for document readyState=complete"
                    );
                    tokio::time::sleep(POLL_INTERVAL).await;
                    continue;
                }
                let device_id = read_device_id(&window).await;
                let has_device_id = !device_id.is_empty();
                match webview_revolut_fetch_with_timeout(
                    &window,
                    "GET",
                    TOKEN_INFO_PATH,
                    &device_id,
                    &[],
                    PROBE_TIMEOUT,
                )
                .await
                {
                    Ok(probe) if probe.status == 200 => {
                        tracing::info!(
                            path,
                            has_device_id,
                            status = probe.status,
                            "Revolut WebView auth probe succeeded"
                        );
                        if !self.mark_fetching(&process_id) {
                            return;
                        }
                        tracing::info!(
                            "Revolut WebView authenticated, harvesting read-only snapshot"
                        );
                        self.set_progress(&member_key, "HARVESTING", Some(0));
                        match self
                            .finish_webview_harvest(&window, &member_key, &device_id)
                            .await
                        {
                            Ok(payload) => {
                                self.remember_snapshot(&member_key, payload);
                                self.mark_done(&process_id);
                                tracing::info!(
                                    "Revolut WebView harvest ready, closing login window"
                                );
                            }
                            Err(err) => {
                                tracing::warn!(
                                    "Revolut WebView harvest failed (code={})",
                                    err.detail
                                );
                                self.mark_failed(&process_id, err.detail);
                            }
                        }
                        let _ = window.destroy();
                        return;
                    }
                    Ok(probe) => {
                        tracing::info!(
                            path,
                            has_device_id,
                            status = probe.status,
                            "Revolut WebView auth probe not ready"
                        );
                    }
                    Err(err) => {
                        tracing::info!(
                            path,
                            has_device_id,
                            reason = err.as_reason(),
                            "Revolut WebView auth probe interrupted"
                        );
                    }
                }
            }

            tokio::time::sleep(POLL_INTERVAL).await;
        }
    }

    async fn finish_webview_harvest(
        &self,
        window: &WebviewWindow,
        member_key: &str,
        device_id: &str,
    ) -> Result<RevolutHarvestPayload, ApiError> {
        let now_ms = Utc::now().timestamp_millis();
        let payload = harvest_accounts_with(
            now_ms,
            |method, path, params| {
                let win = window.clone();
                let method = method.to_owned();
                let path = path.to_owned();
                let dev = device_id.to_owned();
                let params: Vec<(String, String)> = params
                    .iter()
                    .map(|(k, v)| ((*k).to_owned(), v.clone()))
                    .collect();
                async move { webview_revolut_fetch(&win, &method, &path, &dev, &params).await }
            },
            |count| self.set_progress(member_key, "HARVESTING", i32::try_from(count).ok()),
        )
        .await?;

        // Best-effort cookie capture so subsequent syncs can refresh without reopening the window.
        let win_for_cookies = window.clone();
        let raw_cookies = tokio::task::spawn_blocking(move || win_for_cookies.cookies())
            .await
            .ok()
            .and_then(Result::ok)
            .unwrap_or_default();
        let stored: Vec<StoredCookie> = raw_cookies
            .into_iter()
            .map(|c| StoredCookie {
                name: c.name().to_owned(),
                value: c.value().to_owned(),
                domain: c.domain().map(str::to_owned),
                path: c.path().map(str::to_owned),
                expires: c.expires_datetime().map(|dt| dt.unix_timestamp()),
                http_only: c.http_only(),
                secure: c.secure(),
                same_site: c.same_site().map(|s| format!("{s:?}")),
            })
            .filter(cookie_in_scope)
            .collect();
        let final_device_id = if !device_id.is_empty() {
            device_id.to_owned()
        } else {
            stored
                .iter()
                .find(|c| c.name == "revo_device_id")
                .map(|c| c.value.clone())
                .unwrap_or_default()
        };
        if !stored.is_empty() && !final_device_id.is_empty() {
            self.store_session(member_key.to_owned(), final_device_id, stored);
        }

        Ok(payload)
    }

    async fn http_api_call(
        &self,
        jar: &Arc<Mutex<Vec<StoredCookie>>>,
        device_id: &str,
        method: &str,
        path: &str,
        params: &[(String, String)],
    ) -> FetchResponse {
        let cookie_header = {
            let guard = jar.lock().unwrap_or_else(|e| e.into_inner());
            build_cookie_header(&guard, API_HOST)
        };
        let mut url = match Url::parse(&format!("https://{API_HOST}{path}")) {
            Ok(u) => u,
            Err(_) => {
                return FetchResponse {
                    status: 0,
                    data: None,
                }
            }
        };
        if !params.is_empty() {
            let mut pairs = url.query_pairs_mut();
            for (k, v) in params {
                pairs.append_pair(k, v);
            }
        }
        let req_method = match method {
            "PUT" => reqwest::Method::PUT,
            "POST" => reqwest::Method::POST,
            _ => reqwest::Method::GET,
        };
        let mut req = self
            .http
            .request(req_method, url)
            .header("Accept", "application/json")
            .header("x-device-id", device_id)
            .header("x-browser-application", "WEB_CLIENT")
            .header("x-client-version", "100.0")
            .header("Origin", format!("https://{API_HOST}"))
            .header("Referer", format!("https://{API_HOST}/home"));
        if !cookie_header.is_empty() {
            req = req.header("Cookie", cookie_header);
        }
        let Ok(resp) = req.send().await else {
            return FetchResponse {
                status: 0,
                data: None,
            };
        };
        let set_cookies: Vec<String> = resp
            .headers()
            .get_all(reqwest::header::SET_COOKIE)
            .iter()
            .filter_map(|v| v.to_str().ok().map(str::to_owned))
            .collect();
        if !set_cookies.is_empty() {
            let mut guard = jar.lock().unwrap_or_else(|e| e.into_inner());
            merge_set_cookies(&mut guard, &set_cookies, API_HOST);
        }
        let status = resp.status().as_u16();
        let Ok(text) = read_capped_text(resp, MAX_RESPONSE_BYTES).await else {
            return FetchResponse {
                status: 0,
                data: None,
            };
        };
        let data = revolut_parsers::loads(&text).ok();
        FetchResponse { status, data }
    }
}

/// Full Revolut retail harvest orchestrator (mirrors `services/revolut-auth/main.py:harvest_accounts`).
async fn harvest_accounts_with<F, Fut, P>(
    now_ms: i64,
    api_call: F,
    on_progress: P,
) -> Result<RevolutHarvestPayload, ApiError>
where
    F: Fn(&str, &str, &[(&str, String)]) -> Fut,
    Fut: Future<Output = FetchResponse>,
    P: Fn(usize),
{
    let mut mb_responses = Vec::new();
    for account_type in [Some("PERSONAL"), Some("PERSONAL_JOINT"), None] {
        let params: Vec<(&str, String)> = match account_type {
            Some(at) => vec![("accountType", at.to_owned())],
            None => Vec::new(),
        };
        let resp = api_call("GET", MONEY_BOXES_PATH, &params).await;
        if resp.status == 200 {
            if let Some(data) = resp.data {
                mb_responses.push(data);
            }
        }
    }
    let money_boxes = revolut_parsers::collect_money_boxes(&mb_responses);

    let currencies_resp = api_call("GET", CURRENCIES_PATH, &[("type", "fiat".to_owned())]).await;
    let fiat = if currencies_resp.status == 200 {
        revolut_parsers::parse_fiat_currencies(currencies_resp.data.as_ref())
    } else {
        revolut_parsers::fiat_fallback()
    };

    let wallets_resp = api_call("GET", WALLETS_PATH, &[]).await;
    let mut wallets = if wallets_resp.status == 200 {
        wallets_resp
            .data
            .as_ref()
            .map(revolut_parsers::parse_wallets_grouped)
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    if wallets.is_empty() {
        let single_resp = api_call("GET", SINGLE_WALLET_PATH, &[]).await;
        if single_resp.status == 200 {
            if let Some(data) = single_resp.data.as_ref() {
                wallets = revolut_parsers::parse_single_wallet(data);
            }
        }
    }

    let (accounts, tx_pocket_ids, wallet_currencies) =
        revolut_parsers::assemble_accounts_and_wallets(&money_boxes, &wallets, &fiat);
    on_progress(accounts.len());

    let mut ibans: HashMap<String, Option<String>> = HashMap::new();
    for (wallet_id, ccy) in &wallet_currencies {
        let iban_resp = api_call(
            "GET",
            ACCOUNT_DETAILS_PATH,
            &[
                ("currency", ccy.clone()),
                ("pocketType", "CURRENT".to_owned()),
                ("locale", "fr-FR".to_owned()),
                ("walletId", wallet_id.clone()),
            ],
        )
        .await;
        let iban = if iban_resp.status == 200 {
            iban_resp
                .data
                .as_ref()
                .and_then(revolut_parsers::parse_iban)
        } else {
            None
        };
        ibans.insert(wallet_id.clone(), iban);
    }

    let mut accounts = revolut_parsers::with_wallet_parents(accounts, &wallet_currencies, &ibans);
    let total_accounts = accounts.len();

    for acc in &mut accounts {
        let Some(pocket_id) = tx_pocket_ids.get(&acc.external_id) else {
            continue;
        };
        let pocket_id = pocket_id.clone();
        let txns = revolut_parsers::fetch_transactions_with(
            |to| {
                let params = vec![
                    ("internalPocketId", pocket_id.clone()),
                    ("to", to.to_string()),
                ];
                let fut = api_call("GET", TRANSACTIONS_PATH, &params);
                async move {
                    let resp = fut.await;
                    if resp.status != 200 {
                        return Err(revolut_parsers::ParseError("UPSTREAM_UNAVAILABLE"));
                    }
                    resp.data.ok_or(revolut_parsers::ParseError("INVALID_DATA"))
                }
            },
            now_ms,
            &fiat,
        )
        .await
        .map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))?;
        acc.transactions = txns;
        on_progress(total_accounts);
    }

    revolut_parsers::classify_internal_transfers(&mut accounts);
    Ok(RevolutHarvestPayload { accounts })
}

async fn read_device_id(window: &WebviewWindow) -> String {
    let win = window.clone();
    if let Ok(Ok(cookies)) = tokio::task::spawn_blocking(move || win.cookies()).await {
        for cookie in cookies {
            if cookie.name() == "revo_device_id" && !cookie.value().is_empty() {
                return cookie.value().to_owned();
            }
        }
    }
    let (tx, rx) = tokio::sync::oneshot::channel::<String>();
    let sender = Arc::new(Mutex::new(Some(tx)));
    let js = r#"(function(){
  var c = document.cookie.split(';').map(function(s){return s.trim();}).find(function(s){return s.indexOf('revo_device_id=') === 0;});
  return c ? c.slice('revo_device_id='.length) : '';
})()"#;
    if window
        .eval_with_callback(js, move |val| {
            if let Some(tx) = sender.lock().unwrap_or_else(|e| e.into_inner()).take() {
                let _ = tx.send(val);
            }
        })
        .is_ok()
    {
        if let Ok(Ok(raw)) = tokio::time::timeout(Duration::from_secs(2), rx).await {
            if let Some(s) = unwrap_eval_string(&raw) {
                return s;
            }
        }
    }
    String::new()
}

/// Runs a same-origin read-only `fetch(...)` inside the authenticated Revolut WebView using
/// `FETCH_TIMEOUT` (for account/transaction harvesting).
async fn webview_revolut_fetch(
    window: &WebviewWindow,
    method: &str,
    path: &str,
    device_id: &str,
    params: &[(String, String)],
) -> FetchResponse {
    webview_revolut_fetch_with_timeout(window, method, path, device_id, params, FETCH_TIMEOUT)
        .await
        .unwrap_or(FetchResponse {
            status: 0,
            data: None,
        })
}

/// Runs a same-origin read-only `fetch(...)` inside the Revolut WebView with an explicit timeout,
/// returning `Err(EvalPollError::ContextLost)` immediately if page navigation resets the JS slot.
async fn webview_revolut_fetch_with_timeout(
    window: &WebviewWindow,
    method: &str,
    path: &str,
    device_id: &str,
    params: &[(String, String)],
    timeout: Duration,
) -> Result<FetchResponse, EvalPollError> {
    let slot = format!("__picsou_revo_{}", random_hex(12));
    let method_js = serde_json::to_string(method).unwrap_or_else(|_| "\"GET\"".to_owned());
    let path_js = serde_json::to_string(path).unwrap_or_else(|_| "\"/\"".to_owned());
    let dev_js = serde_json::to_string(device_id).unwrap_or_else(|_| "\"\"".to_owned());
    let params_map: serde_json::Map<String, Value> =
        params.iter().map(|(k, v)| (k.clone(), json!(v))).collect();
    let params_js =
        serde_json::to_string(&Value::Object(params_map)).unwrap_or_else(|_| "{}".to_owned());

    let start_js = format!(
        r#"(function(){{
  var k = "{slot}";
  window[k] = "{PENDING_SENTINEL}";
  var url = new URL({path_js}, window.location.origin);
  var params = {params_js};
  Object.keys(params).forEach(function(key) {{
    if (params[key] !== undefined && params[key] !== null) {{
      url.searchParams.set(key, params[key]);
    }}
  }});
  var dev = {dev_js};
  if (!dev) {{
    var c = document.cookie.split(';').map(function(s){{return s.trim();}}).find(function(s){{return s.indexOf('revo_device_id=') === 0;}});
    dev = c ? c.slice('revo_device_id='.length) : '';
  }}
  fetch(url.toString(), {{
    method: {method_js},
    credentials: "same-origin",
    redirect: "error",
    headers: {{
      "x-device-id": dev,
      "x-browser-application": "WEB_CLIENT",
      "x-client-version": "100.0"
    }}
  }})
    .then(function(r) {{
      return r.text().then(function(t) {{
        if (window[k] === "{PENDING_SENTINEL}") {{
          window[k] = JSON.stringify({{ ok: true, status: r.status, text: t }});
        }}
      }});
    }})
    .catch(function() {{
      if (window[k] === "{PENDING_SENTINEL}") {{
        window[k] = JSON.stringify({{ ok: false, status: 0, text: "" }});
      }}
    }});
}})();"#
    );

    if window.eval(&start_js).is_err() {
        return Err(EvalPollError::EvalFailed);
    }

    let raw_payload = poll_webview_slot(window, &slot, timeout, FETCH_POLL_INTERVAL).await?;

    #[derive(Deserialize)]
    struct JsFetchReply {
        ok: bool,
        status: u16,
        text: String,
    }

    let Ok(reply) = serde_json::from_str::<JsFetchReply>(&raw_payload) else {
        return Ok(FetchResponse {
            status: 0,
            data: None,
        });
    };
    if !reply.ok || reply.text.len() > MAX_RESPONSE_BYTES {
        return Ok(FetchResponse {
            status: 0,
            data: None,
        });
    }
    let data = revolut_parsers::loads(&reply.text).ok();
    Ok(FetchResponse {
        status: reply.status,
        data,
    })
}

// ─── host / URL / member helpers ─────────────────────────────────────────────

pub fn normalize_member_key(member_id: &str) -> String {
    let cleaned: String = member_id
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .collect();
    if cleaned.is_empty() {
        "default".to_owned()
    } else {
        cleaned
    }
}

pub fn is_revolut_host(host: &str) -> bool {
    is_domain_or_subdomain(&host.to_ascii_lowercase(), "revolut.com")
}

/// Host allowlist for `WebviewWindowBuilder::on_navigation`: permits `about:blank` and HTTPS
/// navigations within `revolut.com` and its subdomains, blocking all other schemes and hosts.
pub fn is_allowed_navigation_url(url: &Url) -> bool {
    is_allowed_https_navigation(url, |host| is_domain_or_subdomain(host, "revolut.com"))
}

/// True once the WebView is on `https://app.revolut.com/home` (or another authenticated retail
/// route on `app.revolut.com`, never `sso.revolut.com`, login/start pages, or an in-flight OAuth
/// callback carrying `code` or `rwa_auth_type`).
pub fn on_revolut_home(url: &Url) -> bool {
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || !matches!(url.port(), None | Some(443))
    {
        return false;
    }
    let Some(host) = url.host_str() else {
        return false;
    };
    if !host.eq_ignore_ascii_case(API_HOST) {
        return false;
    }
    for (k, _) in url.query_pairs() {
        if k == "code" || k == "rwa_auth_type" {
            return false;
        }
    }
    let path = url.path().to_ascii_lowercase();
    path == "/home"
        || path.starts_with("/home/")
        || path.starts_with("/accounts")
        || path.starts_with("/vaults")
        || path.starts_with("/pockets")
}

pub fn cookie_in_scope(cookie: &StoredCookie) -> bool {
    let domain = cookie
        .domain
        .as_deref()
        .unwrap_or("")
        .trim_start_matches('.')
        .to_ascii_lowercase();
    !domain.is_empty() && is_revolut_host(&domain)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn revolut_home_detection_excludes_sso_and_start() {
        assert!(!on_revolut_home(
            &Url::parse("https://sso.revolut.com/signin").unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://app.revolut.com/start").unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://app.revolut.com/").unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://evil.example/home").unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://app.revolut.com/home?rwa_auth_type=auth&code=xxx&state=yyy")
                .unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://app.revolut.com/home?code=abc&state=def").unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://app.revolut.com/home?code=abc").unwrap()
        ));
        assert!(!on_revolut_home(
            &Url::parse("https://app.revolut.com/home?rwa_auth_type=auth").unwrap()
        ));
        assert!(on_revolut_home(
            &Url::parse("https://app.revolut.com/home").unwrap()
        ));
        assert!(on_revolut_home(
            &Url::parse("https://app.revolut.com/home?tab=accounts").unwrap()
        ));
    }

    #[test]
    fn navigation_allowlist_restricts_to_revolut_https_and_about_blank() {
        for allowed in [
            "about:blank",
            "https://revolut.com/",
            "https://app.revolut.com/start",
            "https://sso.revolut.com/signin",
            "https://APP.REVOLUT.COM/home",
        ] {
            assert!(
                is_allowed_navigation_url(&Url::parse(allowed).unwrap()),
                "expected allowed: {allowed}"
            );
        }
        for rejected in [
            "http://app.revolut.com/",
            "https://app.revolut.com:8443/",
            "https://user:pass@app.revolut.com/",
            "https://evil-revolut.com/",
            "https://app.revolut.com.evil.example/",
            "https://evil.example/",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
        ] {
            assert!(
                !is_allowed_navigation_url(&Url::parse(rejected).unwrap()),
                "expected rejected: {rejected}"
            );
        }
    }

    #[test]
    fn member_key_collapses_punctuation_and_defaults_when_empty() {
        assert_eq!(normalize_member_key("42"), "42");
        assert_eq!(normalize_member_key("42!"), "42");
        assert_eq!(normalize_member_key("///"), "default");
    }

    #[tokio::test]
    async fn sync_member_returns_cached_snapshot_then_401_without_cookies() {
        let mgr = RevolutWebviewManager::new();
        let now_ms = Utc::now().timestamp_millis();
        let sample = harvest_accounts_with(
            now_ms,
            |_method, path, _params| {
                let path = path.to_owned();
                async move {
                    if path == MONEY_BOXES_PATH {
                        FetchResponse { status: 200, data: Some(json!([])) }
                    } else if path == CURRENCIES_PATH {
                        FetchResponse { status: 200, data: Some(json!([{"code": "EUR"}])) }
                    } else if path == WALLETS_PATH {
                        FetchResponse {
                            status: 200,
                            data: Some(json!({
                                "PERSONAL": [{
                                    "id": "w-1",
                                    "pockets": [
                                        {"id": "p-eur", "type": "CURRENT", "currency": "EUR", "balance": 12500},
                                        {"id": "p-noise", "type": "MERCHANT", "currency": "EUR", "balance": 9999}
                                    ]
                                }]
                            })),
                        }
                    } else if path == ACCOUNT_DETAILS_PATH {
                        FetchResponse {
                            status: 200,
                            data: Some(json!({"iban": "FR7630006000011234567890189"})),
                        }
                    } else if path == TRANSACTIONS_PATH {
                        FetchResponse { status: 200, data: Some(json!([])) }
                    } else {
                        FetchResponse { status: 404, data: None }
                    }
                }
            },
            |_| {},
        )
        .await
        .unwrap();

        assert_eq!(sample.accounts.len(), 2); // p-eur + synthesized w-1 parent; p-noise dropped!
        mgr.remember_snapshot("7", sample.clone());

        let first = mgr.sync_member("7").await.unwrap();
        assert_eq!(first, sample);

        let second = mgr.sync_member("7").await.unwrap_err();
        assert_eq!(second.status, StatusCode::UNAUTHORIZED);
        assert_eq!(second.detail, "SESSION_EXPIRED");
    }

    #[test]
    fn clear_member_purges_ram_and_maps_stay_bounded() {
        let mgr = RevolutWebviewManager::new();
        mgr.store_session(
            "42".to_owned(),
            "dev-42".to_owned(),
            vec![StoredCookie {
                name: "sid".to_owned(),
                value: "secret".to_owned(),
                domain: Some(".revolut.com".to_owned()),
                path: Some("/".to_owned()),
                expires: None,
                http_only: Some(true),
                secure: Some(true),
                same_site: None,
            }],
        );
        mgr.remember_snapshot("42", RevolutHarvestPayload { accounts: vec![] });
        mgr.set_progress("42", "HARVESTING", Some(2));

        assert!(mgr.clear_member("42"));
        assert!(!mgr.clear_member("42"));
        assert!(mgr.get_progress("42").phase.is_none());
        assert!(mgr.sessions.lock().unwrap().is_empty());
        assert!(mgr.snapshots.lock().unwrap().is_empty());

        for i in 0..(MAX_SESSIONS + 12) {
            mgr.store_session(format!("m{i}"), "dev".to_owned(), vec![]);
            mgr.set_progress(&format!("m{i}"), "HARVESTING", Some(1));
        }
        assert_eq!(mgr.sessions.lock().unwrap().len(), MAX_SESSIONS);
        assert_eq!(mgr.progress.lock().unwrap().len(), MAX_PROGRESS);
    }
}
