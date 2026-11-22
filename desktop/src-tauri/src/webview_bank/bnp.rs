//! BNP Paribas & Hello bank! native WebView connector.
//!
//! Opens a real OS WebView (`WKWebView` on macOS, `WebView2` on Windows, `WebKitGTK` on Linux)
//! directly on the bank's official login page so the user authenticates naturally (customer number,
//! keypad, Clé Digitale / 2FA). Picsou never intercepts credentials. Once the bank's own
//! `InfosClient` probe confirms the user is signed in, read-only JSON endpoints are fetched inside
//! that authenticated WebView (inheriting the native TLS fingerprint and HttpOnly cookies), parsed
//! in Rust via `bnp_parsers`, cached for the immediate backend sync, and the login window closes.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::http::StatusCode;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use chrono::{Local, TimeDelta};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tauri::{
    AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use uuid::Uuid;

use super::{
    document_ready_complete, is_allowed_https_navigation, is_domain_or_subdomain,
    poll_webview_slot, read_capped_text, EvalPollError, ReadBodyError, PENDING_SENTINEL,
};

use super::bnp_parsers::{
    self, EnvelopeError, NormalizedAccount, NormalizedOperation, NormalizedPosition,
    UnsupportedProduct,
};

const BRAND_TLD: &str = "bnpparibas";
const LOGIN_PATH: &str = "/auth/login";
const INFOS_CLIENT: &str = "/serviceinfosclient-wspl/rpc/InfosClient?modeAppel=0";
const EP_IBANS: &str = "/rib-wspl/rpc/comptes";
const EP_ACCOUNTS: &str = "/udc-wspl/rest/getlstcpt";
const EP_HISTORY: &str = "/rop2-wspl/rest/releveOp";
const EP_SYNTHESIS: &str = "/pe-war/rpc/synthesis/get";
const EP_PORTFOLIO: &str = "/pe-war/rpc/portfolioDetails/get";

const LOGIN_TTL: Duration = Duration::from_secs(300);
const POLL_INTERVAL: Duration = Duration::from_millis(600);
const FETCH_POLL_INTERVAL: Duration = Duration::from_millis(50);
const PROBE_TIMEOUT: Duration = Duration::from_secs(3);
const FETCH_TIMEOUT: Duration = Duration::from_secs(25);
const SNAPSHOT_CACHE_TTL: Duration = Duration::from_secs(180);
const MAX_PROCESSES: usize = 4;
const MAX_CACHE_ENTRIES: usize = 8;
const MAX_COOKIES: usize = 200;
const MAX_SESSION_STATE_BYTES: usize = 150_000;
const MAX_RESPONSE_BYTES: usize = 8_000_000;
const REFRESH_USER_AGENT: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Brand {
    pub name: &'static str,
    pub site: &'static str,
    pub login: &'static str,
    pub entry: &'static str,
    pub window_title: &'static str,
    pub hosts: &'static [&'static str],
    pub cookie_domains: &'static [&'static str],
}

pub const BNP_BRAND: Brand = Brand {
    name: "bnp",
    site: "mabanque.bnpparibas",
    login: "connexion.mabanque.bnpparibas",
    entry: "https://mabanque.bnpparibas/auth/login",
    window_title: "Connexion — BNP Paribas",
    hosts: &[
        "mabanque.bnpparibas",
        "connexion.mabanque.bnpparibas",
        "content.connexion.mabanque.bnpparibas",
    ],
    cookie_domains: &[
        "mabanque.bnpparibas",
        "connexion.mabanque.bnpparibas",
        "content.connexion.mabanque.bnpparibas",
    ],
};

pub const HELLOBANK_BRAND: Brand = Brand {
    name: "hellobank",
    site: "www.hellobank.fr",
    login: "connexion.hellobank.fr",
    entry: "https://www.hellobank.fr/auth/login?requiredDAC=3",
    window_title: "Connexion — Hello bank!",
    hosts: &[
        "www.hellobank.fr",
        "connexion.hellobank.fr",
        "content.connexion.hellobank.fr",
    ],
    cookie_domains: &[
        "www.hellobank.fr",
        "connexion.hellobank.fr",
        "content.connexion.hellobank.fr",
        "hellobank.fr",
    ],
};

pub fn brand_by_name(name: &str) -> Option<Brand> {
    match name {
        "bnp" => Some(BNP_BRAND),
        "hellobank" => Some(HELLOBANK_BRAND),
        _ => None,
    }
}

#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub detail: &'static str,
}

impl ApiError {
    pub fn new(status: StatusCode, detail: &'static str) -> Self {
        Self { status, detail }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewStartResponse {
    pub process_id: String,
    pub status: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WebviewStatusResponse {
    pub status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_state: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_code: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountsResponse {
    pub brand: String,
    pub accounts: Vec<NormalizedAccount>,
    pub unsupported: Vec<UnsupportedProduct>,
    pub session_state: String,
}

#[derive(Debug, Clone)]
enum ProcessStatus {
    WaitingLogin,
    Fetching,
    Done { session_state: String },
    Cancelled,
    Failed { error_code: &'static str },
}

struct ProcessEntry {
    brand: Brand,
    status: ProcessStatus,
    window_label: String,
    created_at: Instant,
}

struct CachedSnapshot {
    expires_at: Instant,
    accounts: Vec<NormalizedAccount>,
    unsupported: Vec<UnsupportedProduct>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredCookie {
    pub name: String,
    pub value: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub domain: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub http_only: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secure: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub same_site: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionEnvelope {
    v: u8,
    brand: String,
    cookies: Vec<StoredCookie>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    landing: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    desktop_only: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    nonce: Option<String>,
}

pub struct BnpWebviewManager {
    processes: Mutex<HashMap<String, ProcessEntry>>,
    cache: Mutex<HashMap<String, CachedSnapshot>>,
    http: reqwest::Client,
}

impl BnpWebviewManager {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .user_agent(REFRESH_USER_AGENT)
            .build()
            .expect("reqwest client");
        Self {
            processes: Mutex::new(HashMap::new()),
            cache: Mutex::new(HashMap::new()),
            http,
        }
    }

    pub async fn start_login(
        self: &Arc<Self>,
        app: AppHandle,
        brand_name: &str,
    ) -> Result<WebviewStartResponse, ApiError> {
        let brand = brand_by_name(brand_name)
            .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "INVALID_REQUEST"))?;

        // Close any previous in-flight BNP login window so the user never gets stuck with stale windows.
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
        let window_label = format!("picsou-bnp-{process_id}");
        let entry_url = Url::parse(brand.entry)
            .map_err(|_| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"))?;

        {
            let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
            procs.insert(
                process_id.clone(),
                ProcessEntry {
                    brand,
                    status: ProcessStatus::WaitingLogin,
                    window_label: window_label.clone(),
                    created_at: Instant::now(),
                },
            );
        }

        let app_for_build = app.clone();
        let label_for_build = window_label.clone();
        let title = brand.window_title.to_owned();
        let manager_for_close = Arc::clone(self);
        let pid_for_close = process_id.clone();

        let window = tokio::task::spawn_blocking(move || {
            let win = WebviewWindowBuilder::new(
                &app_for_build,
                &label_for_build,
                WebviewUrl::External(entry_url),
            )
            .title(title)
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
                        "BNP WebView blocked navigation to scheme={} host={:?}",
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

        tracing::info!("BNP WebView login opened (brand={})", brand.name);

        let manager = Arc::clone(self);
        let pid = process_id.clone();
        tokio::spawn(async move {
            manager.watch_login(pid, brand, window).await;
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
            ProcessStatus::Done { session_state } => WebviewStatusResponse {
                status: "DONE",
                session_state: Some(session_state.clone()),
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

    fn mark_cancelled_if_active(&self, process_id: &str) {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = procs.get_mut(process_id) {
            if matches!(
                entry.status,
                ProcessStatus::WaitingLogin | ProcessStatus::Fetching
            ) {
                entry.status = ProcessStatus::Cancelled;
                tracing::info!(
                    "BNP WebView login cancelled by user (brand={})",
                    entry.brand.name
                );
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

    fn mark_done(&self, process_id: &str, session_state: String) {
        let mut procs = self.processes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = procs.get_mut(process_id) {
            if !matches!(entry.status, ProcessStatus::Cancelled) {
                entry.status = ProcessStatus::Done { session_state };
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

    fn remember_snapshot(
        &self,
        session_state: &str,
        accounts: Vec<NormalizedAccount>,
        unsupported: Vec<UnsupportedProduct>,
    ) {
        let key = sha256_hex(session_state);
        let now = Instant::now();
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
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
            key,
            CachedSnapshot {
                expires_at: now + SNAPSHOT_CACHE_TTL,
                accounts,
                unsupported,
            },
        );
    }

    fn take_cached_snapshot(
        &self,
        session_state: &str,
    ) -> Option<(Vec<NormalizedAccount>, Vec<UnsupportedProduct>)> {
        let key = sha256_hex(session_state);
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let entry = cache.remove(&key)?;
        if entry.expires_at > Instant::now() {
            Some((entry.accounts, entry.unsupported))
        } else {
            None
        }
    }

    async fn watch_login(&self, process_id: String, brand: Brand, window: WebviewWindow) {
        let deadline = Instant::now() + LOGIN_TTL;
        loop {
            if !self.is_waiting(&process_id) {
                return;
            }
            if Instant::now() >= deadline {
                tracing::warn!("BNP WebView login timed out (brand={})", brand.name);
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

            if on_site(&current_url, brand) {
                let path = current_url.path();
                if !document_ready_complete(&window).await {
                    tracing::debug!(
                        brand = brand.name,
                        path,
                        "BNP WebView waiting for document readyState=complete"
                    );
                    tokio::time::sleep(POLL_INTERVAL).await;
                    continue;
                }
                // Confirm the session is genuinely active before reading accounts.
                match webview_fetch_json_with_timeout(
                    &window,
                    "GET",
                    INFOS_CLIENT,
                    None,
                    PROBE_TIMEOUT,
                )
                .await
                {
                    Ok(info) => {
                        let alive = bnp_parsers::session_alive(&info);
                        tracing::info!(
                            brand = brand.name,
                            path,
                            status = 200u16,
                            alive,
                            "BNP WebView auth probe completed"
                        );
                        if alive {
                            if !self.mark_fetching(&process_id) {
                                return;
                            }
                            tracing::info!(
                                "BNP WebView authenticated, fetching read-only snapshot (brand={})",
                                brand.name
                            );
                            let landing = landing_host(&current_url, brand);
                            match self.finish_webview_snapshot(&window, brand, landing).await {
                                Ok((session_state, accounts, unsupported)) => {
                                    self.remember_snapshot(&session_state, accounts, unsupported);
                                    self.mark_done(&process_id, session_state);
                                    tracing::info!(
                                        "BNP WebView snapshot ready, closing login window (brand={})",
                                        brand.name
                                    );
                                }
                                Err(err) => {
                                    tracing::warn!(
                                        "BNP WebView snapshot failed (brand={}; code={})",
                                        brand.name,
                                        err.detail
                                    );
                                    self.mark_failed(&process_id, err.detail);
                                }
                            }
                            let _ = window.destroy();
                            return;
                        }
                    }
                    Err(err) => {
                        tracing::info!(
                            brand = brand.name,
                            path,
                            status = err.status,
                            reason = err.reason,
                            "BNP WebView auth probe not ready"
                        );
                    }
                }
            }

            tokio::time::sleep(POLL_INTERVAL).await;
        }
    }

    async fn finish_webview_snapshot(
        &self,
        window: &WebviewWindow,
        brand: Brand,
        landing: Option<String>,
    ) -> Result<(String, Vec<NormalizedAccount>, Vec<UnsupportedProduct>), ApiError> {
        let (accounts, unsupported) = fetch_snapshot_with(|method, path, body| {
            let win = window.clone();
            async move { webview_fetch_json(&win, method, path, body).await }
        })
        .await?;

        // Read cookies off the main thread (safe on both macOS WKWebView and Windows WebView2).
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
            .filter(|c| cookie_in_scope(c, brand))
            .collect();

        let session_state = encode_session(brand, stored, landing.as_deref())?;
        Ok((session_state, accounts, unsupported))
    }

    /// Serves `POST /accounts` using either the fresh in-memory WebView snapshot or stored cookies.
    pub async fn accounts(&self, session_state: &str) -> Result<AccountsResponse, ApiError> {
        let decoded = decode_session_state(session_state)?;
        if let Some((accounts, unsupported)) = self.take_cached_snapshot(session_state) {
            return Ok(AccountsResponse {
                brand: decoded.brand.name.to_owned(),
                accounts,
                unsupported,
                session_state: session_state.to_owned(),
            });
        }
        if decoded.cookies.is_empty() {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }

        let host = decoded
            .landing
            .clone()
            .unwrap_or_else(|| decoded.brand.site.to_owned());
        let jar = Arc::new(Mutex::new(decoded.cookies));

        // Probe session first via InfosClient, matching services/bnp-auth/bank.py.
        let info = self
            .http_fetch_json(&host, &jar, "GET", INFOS_CLIENT, None)
            .await?;
        if !bnp_parsers::session_alive(&info) {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }

        let (accounts, unsupported) = fetch_snapshot_with(|method, path, body| {
            let host = host.clone();
            let jar = Arc::clone(&jar);
            async move { self.http_fetch_json(&host, &jar, method, path, body).await }
        })
        .await?;

        let updated_cookies = jar
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .filter(|c| cookie_in_scope(c, decoded.brand))
            .cloned()
            .collect();
        let next_state = encode_session(decoded.brand, updated_cookies, Some(&host))
            .unwrap_or_else(|_| session_state.to_owned());

        Ok(AccountsResponse {
            brand: decoded.brand.name.to_owned(),
            accounts,
            unsupported,
            session_state: next_state,
        })
    }

    async fn http_fetch_json(
        &self,
        host: &str,
        jar: &Arc<Mutex<Vec<StoredCookie>>>,
        method: &str,
        path: &str,
        body: Option<Value>,
    ) -> Result<Value, ApiError> {
        let cookie_header = {
            let guard = jar.lock().unwrap_or_else(|e| e.into_inner());
            build_cookie_header(&guard, host)
        };
        let url = format!("https://{host}{path}");
        let req_method = match method {
            "POST" => reqwest::Method::POST,
            _ => reqwest::Method::GET,
        };
        let mut req = self
            .http
            .request(req_method, &url)
            .header("Accept", "application/json")
            .header("Origin", format!("https://{host}"))
            .header("Referer", format!("https://{host}/"));
        if !cookie_header.is_empty() {
            req = req.header("Cookie", cookie_header);
        }
        if let Some(payload) = body {
            req = req.json(&payload);
        }
        let resp = req
            .send()
            .await
            .map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"))?;

        // Merge any rotated Set-Cookie headers back into our in-memory jar.
        let set_cookies: Vec<String> = resp
            .headers()
            .get_all(reqwest::header::SET_COOKIE)
            .iter()
            .filter_map(|v| v.to_str().ok().map(str::to_owned))
            .collect();
        if !set_cookies.is_empty() {
            let mut guard = jar.lock().unwrap_or_else(|e| e.into_inner());
            merge_set_cookies(&mut guard, &set_cookies, host);
        }

        let status = resp.status();
        if status.is_redirection()
            || status == StatusCode::UNAUTHORIZED
            || status == StatusCode::FORBIDDEN
        {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }
        if status != StatusCode::OK {
            return Err(ApiError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_UNAVAILABLE",
            ));
        }
        let text = read_capped_text(resp, MAX_RESPONSE_BYTES)
            .await
            .map_err(|err| match err {
                ReadBodyError::Transport => {
                    ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE")
                }
                ReadBodyError::TooLarge => {
                    ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED")
                }
            })?;
        bnp_parsers::loads(&text).map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))
    }
}

/// Shared snapshot orchestrator used for both in-WebView fetches and direct HTTP refreshes.
async fn fetch_snapshot_with<F, Fut>(
    fetch: F,
) -> Result<(Vec<NormalizedAccount>, Vec<UnsupportedProduct>), ApiError>
where
    F: Fn(&'static str, &'static str, Option<Value>) -> Fut,
    Fut: std::future::Future<Output = Result<Value, ApiError>>,
{
    let ibans_raw = fetch("GET", EP_IBANS, None).await?;
    let ibans_data = unwrap_envelope(&ibans_raw)?;
    let ibans = bnp_parsers::parse_ibans(&ibans_data);

    let accounts_raw = fetch("GET", EP_ACCOUNTS, None).await?;
    let accounts_data = unwrap_envelope(&accounts_raw)?;
    let (rows, unsupported) = bnp_parsers::parse_accounts(&accounts_data, &ibans)
        .map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))?;

    let today = Local::now().date_naive();
    let start_date = (today - TimeDelta::days(365)).format("%d%m%Y").to_string();
    let end_date = today.format("%d%m%Y").to_string();

    let mut histories: HashMap<String, Option<Vec<NormalizedOperation>>> = HashMap::new();
    let mut portfolios: HashMap<String, Option<Vec<NormalizedPosition>>> = HashMap::new();
    let mut synthesis = Vec::new();

    let has_securities = rows
        .iter()
        .any(|r| matches!(r.kind, "PEA" | "COMPTE_TITRES") && !r.cash);
    if has_securities {
        let syn_raw = fetch("POST", EP_SYNTHESIS, Some(json!({}))).await?;
        let syn_data = unwrap_envelope(&syn_raw)?;
        synthesis = bnp_parsers::parse_synthesis(&syn_data);
    }

    for row in &rows {
        if row.cash {
            continue;
        }
        if matches!(row.kind, "PEA" | "COMPTE_TITRES") {
            let matched = bnp_parsers::match_securities(&row.value, &synthesis);
            let lines = match matched {
                Some(sec) => {
                    let body = json!({ "securityAccountNumber": sec.number });
                    match fetch("POST", EP_PORTFOLIO, Some(body)).await {
                        Ok(raw) => match unwrap_envelope(&raw) {
                            Ok(data) => bnp_parsers::parse_positions(&data),
                            Err(err) if err.detail == "SESSION_EXPIRED" => return Err(err),
                            Err(_) => None,
                        },
                        Err(err) if err.detail == "SESSION_EXPIRED" => return Err(err),
                        Err(_) => None,
                    }
                }
                None => None,
            };
            portfolios.insert(row.external_id.clone(), lines);
        } else if !matches!(row.kind, "ASSURANCE_VIE" | "PER" | "LOAN") {
            let body = json!({
                "ibanCrypte": row.key,
                "pastOrPending": 1,
                "triAV": 0,
                "startDate": start_date,
                "endDate": end_date,
            });
            let ops = match fetch("POST", EP_HISTORY, Some(body)).await {
                Ok(raw) => match unwrap_envelope(&raw) {
                    Ok(data) => bnp_parsers::parse_operations(&data),
                    Err(err) if err.detail == "SESSION_EXPIRED" => return Err(err),
                    Err(_) => None,
                },
                Err(err) if err.detail == "SESSION_EXPIRED" => return Err(err),
                Err(_) => None,
            };
            histories.insert(row.external_id.clone(), ops);
        }
    }

    let accounts = bnp_parsers::build_accounts(&rows, &histories, &synthesis, &portfolios);
    Ok((accounts, unsupported))
}

fn unwrap_envelope(raw: &Value) -> Result<Value, ApiError> {
    bnp_parsers::envelope(raw).map_err(|err| match err {
        EnvelopeError::SessionExpired => ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"),
        EnvelopeError::ParseError(code) => ApiError::new(StatusCode::BAD_GATEWAY, code),
    })
}

struct WebviewFetchFailure {
    status: Option<u16>,
    reason: &'static str,
    api: ApiError,
}

/// Runs a same-origin `fetch(...)` inside the authenticated WebView using `FETCH_TIMEOUT`
/// (for full account/operation harvesting).
async fn webview_fetch_json(
    window: &WebviewWindow,
    method: &str,
    path: &str,
    body: Option<Value>,
) -> Result<Value, ApiError> {
    webview_fetch_json_with_timeout(window, method, path, body, FETCH_TIMEOUT)
        .await
        .map_err(|err| err.api)
}

/// Runs a same-origin `fetch(...)` inside the WebView with an explicit timeout and returns its
/// parsed JSON via an ephemeral random `window` slot read with `eval_with_callback`. Never
/// triggers cross-origin CSP (`connect-src`) or Mixed Content blocks, and bails immediately if a
/// page navigation wipes `window[slot]`.
async fn webview_fetch_json_with_timeout(
    window: &WebviewWindow,
    method: &str,
    path: &str,
    body: Option<Value>,
    timeout: Duration,
) -> Result<Value, WebviewFetchFailure> {
    let slot = format!("__picsou_{}", random_hex(12));
    let method_js = serde_json::to_string(method).unwrap_or_else(|_| "\"GET\"".to_owned());
    let path_js = serde_json::to_string(path).unwrap_or_else(|_| "\"/\"".to_owned());
    let body_js = match body {
        Some(v) => serde_json::to_string(&v.to_string()).unwrap_or_else(|_| "null".to_owned()),
        None => "null".to_owned(),
    };

    let start_js = format!(
        r#"(function(){{
  var k = "{slot}";
  window[k] = "{PENDING_SENTINEL}";
  var init = {{ method: {method_js}, credentials: "same-origin", redirect: "error", headers: {{ "Accept": "application/json" }} }};
  var bodyStr = {body_js};
  if (bodyStr !== null) {{
    init.body = bodyStr;
    init.headers["Content-Type"] = "application/json";
  }}
  fetch({path_js}, init)
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

    window.eval(&start_js).map_err(|_| WebviewFetchFailure {
        status: None,
        reason: EvalPollError::EvalFailed.as_reason(),
        api: ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"),
    })?;

    let raw_payload = poll_webview_slot(window, &slot, timeout, FETCH_POLL_INTERVAL)
        .await
        .map_err(|err| WebviewFetchFailure {
            status: None,
            reason: err.as_reason(),
            api: ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"),
        })?;

    #[derive(Deserialize)]
    struct JsFetchReply {
        ok: bool,
        status: u16,
        text: String,
    }

    let reply: JsFetchReply =
        serde_json::from_str(&raw_payload).map_err(|_| WebviewFetchFailure {
            status: None,
            reason: "invalid_envelope",
            api: ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED"),
        })?;
    if !reply.ok || reply.text.len() > MAX_RESPONSE_BYTES {
        return Err(WebviewFetchFailure {
            status: Some(reply.status),
            reason: "fetch_failed",
            api: ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"),
        });
    }
    if reply.status == 401 || reply.status == 403 {
        return Err(WebviewFetchFailure {
            status: Some(reply.status),
            reason: "unauthorized",
            api: ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"),
        });
    }
    if reply.status != 200 {
        return Err(WebviewFetchFailure {
            status: Some(reply.status),
            reason: "http_status",
            api: ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"),
        });
    }
    bnp_parsers::loads(&reply.text).map_err(|e| WebviewFetchFailure {
        status: Some(reply.status),
        reason: "invalid_json",
        api: ApiError::new(StatusCode::BAD_GATEWAY, e.0),
    })
}

pub(crate) fn unwrap_eval_string(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed == "null" || trimmed == "\"\"" {
        return None;
    }
    if let Ok(s) = serde_json::from_str::<String>(trimmed) {
        return (!s.is_empty()).then_some(s);
    }
    Some(trimmed.to_owned())
}

// ─── host / URL / session helpers ────────────────────────────────────────────

fn valid_dns_label(label: &str) -> bool {
    let bytes = label.as_bytes();
    if bytes.is_empty() || bytes.len() > 63 {
        return false;
    }
    if !bytes[0].is_ascii_alphanumeric() || !bytes[bytes.len() - 1].is_ascii_alphanumeric() {
        return false;
    }
    bytes
        .iter()
        .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'-')
}

pub fn is_brand_tld_host(host: &str) -> bool {
    let lowered = host.to_ascii_lowercase();
    let suffix = format!(".{BRAND_TLD}");
    let Some(prefix) = lowered.strip_suffix(&suffix) else {
        return false;
    };
    !prefix.is_empty() && prefix.split('.').all(valid_dns_label)
}

fn is_login_host(host: &str) -> bool {
    let lowered = host.to_ascii_lowercase();
    lowered.starts_with("connexion.") || lowered.starts_with("content.connexion.")
}

/// Host allowlist for `WebviewWindowBuilder::on_navigation`: permits `about:blank` and HTTPS
/// navigations on BNP Paribas (`.bnpparibas` brand TLD and `bnpparibas.com`) and Hello bank!
/// (`hellobank.fr`) domains, blocking all other schemes and hosts.
pub fn is_allowed_navigation_url(url: &Url) -> bool {
    is_allowed_https_navigation(url, |host| {
        is_brand_tld_host(host)
            || is_domain_or_subdomain(host, "bnpparibas.com")
            || is_domain_or_subdomain(host, "hellobank.fr")
    })
}

pub fn is_allowed_url(url: &Url, brand: Brand) -> bool {
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
    let lowered = host.to_ascii_lowercase();
    brand.hosts.iter().any(|h| *h == lowered) || is_brand_tld_host(&lowered)
}

/// True when the browser is on the bank's site past login (never PingFederate, `/auth/login`,
/// an OIDC callback carrying `code` & `state`, or an error redirect).
pub fn on_site(url: &Url, brand: Brand) -> bool {
    if !is_allowed_url(url, brand) {
        return false;
    }
    let Some(host) = url.host_str() else {
        return false;
    };
    let lowered = host.to_ascii_lowercase();
    if lowered != brand.site && !is_brand_tld_host(&lowered) {
        return false;
    }
    if is_login_host(&lowered) || url.path().starts_with(LOGIN_PATH) {
        return false;
    }
    let path_lower = url.path().to_ascii_lowercase();
    if path_lower.contains("callback") || path_lower.contains("on-login") {
        return false;
    }
    let mut has_code = false;
    let mut has_state = false;
    for (k, _) in url.query_pairs() {
        if k == "error_code" || k == "errorCode" {
            return false;
        }
        if k == "code" {
            has_code = true;
        }
        if k == "state" {
            has_state = true;
        }
    }
    !(has_code && has_state)
}

pub fn landing_host(url: &Url, brand: Brand) -> Option<String> {
    if !on_site(url, brand) {
        return None;
    }
    let host = url.host_str()?.to_ascii_lowercase();
    valid_landing(&host, brand).then_some(host)
}

pub fn valid_landing(host: &str, brand: Brand) -> bool {
    if host.is_empty() || host != host.to_ascii_lowercase() {
        return false;
    }
    let Ok(url) = Url::parse(&format!("https://{host}/")) else {
        return false;
    };
    url.host_str() == Some(host) && on_site(&url, brand)
}

pub fn cookie_in_scope(cookie: &StoredCookie, brand: Brand) -> bool {
    let domain = cookie
        .domain
        .as_deref()
        .unwrap_or("")
        .trim_start_matches('.')
        .to_ascii_lowercase();
    if domain.is_empty() {
        return false;
    }
    brand
        .cookie_domains
        .iter()
        .any(|known| domain == *known || domain.ends_with(&format!(".{known}")))
        || is_brand_tld_host(&domain)
}

pub fn cookie_sent_to(cookie: &StoredCookie, host: &str) -> bool {
    let raw = cookie.domain.as_deref().unwrap_or("").to_ascii_lowercase();
    let domain = raw.trim_start_matches('.');
    !domain.is_empty()
        && (host == domain || (raw.starts_with('.') && host.ends_with(&format!(".{domain}"))))
}

pub(crate) fn build_cookie_header(cookies: &[StoredCookie], host: &str) -> String {
    let host_lower = host.to_ascii_lowercase();
    cookies
        .iter()
        .filter(|c| cookie_sent_to(c, &host_lower))
        .map(|c| format!("{}={}", c.name, c.value))
        .collect::<Vec<_>>()
        .join("; ")
}

pub(crate) fn merge_set_cookies(
    jar: &mut Vec<StoredCookie>,
    headers: &[String],
    default_host: &str,
) {
    for raw in headers {
        let mut parts = raw.split(';');
        let Some(first) = parts.next() else { continue };
        let Some((name, value)) = first.split_once('=') else {
            continue;
        };
        let name = name.trim();
        let value = value.trim();
        if name.is_empty() {
            continue;
        }
        let mut domain = default_host.to_ascii_lowercase();
        let mut path = Some("/".to_owned());
        let mut http_only = None;
        let mut secure = None;
        let mut expired = false;
        for attr in parts {
            let trimmed = attr.trim();
            if let Some((k, v)) = trimmed.split_once('=') {
                let k = k.trim();
                let v = v.trim();
                if k.eq_ignore_ascii_case("domain") && !v.is_empty() {
                    domain = v.to_ascii_lowercase();
                } else if k.eq_ignore_ascii_case("path") && !v.is_empty() {
                    path = Some(v.to_owned());
                } else if k.eq_ignore_ascii_case("max-age")
                    && v.parse::<i64>().is_ok_and(|secs| secs <= 0)
                {
                    expired = true;
                }
            } else if trimmed.eq_ignore_ascii_case("httponly") {
                http_only = Some(true);
            } else if trimmed.eq_ignore_ascii_case("secure") {
                secure = Some(true);
            }
        }
        jar.retain(|c| {
            !(c.name == name
                && c.domain
                    .as_deref()
                    .unwrap_or("")
                    .eq_ignore_ascii_case(&domain))
        });
        if expired {
            continue;
        }
        while jar.len() >= MAX_COOKIES {
            jar.remove(0);
        }
        jar.push(StoredCookie {
            name: name.to_owned(),
            value: value.to_owned(),
            domain: Some(domain),
            path,
            expires: None,
            http_only,
            secure,
            same_site: None,
        });
    }
}

pub fn encode_session(
    brand: Brand,
    cookies: Vec<StoredCookie>,
    landing: Option<&str>,
) -> Result<String, ApiError> {
    let kept: Vec<StoredCookie> = cookies
        .into_iter()
        .filter(|c| cookie_in_scope(c, brand))
        .collect();
    if kept.len() > MAX_COOKIES {
        return Err(ApiError::new(
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED",
        ));
    }
    let desktop_only = kept.is_empty().then_some(true);
    let nonce = desktop_only.map(|_| random_hex(16));
    let landing = landing
        .filter(|h| valid_landing(h, brand))
        .map(str::to_owned);
    let envelope = SessionEnvelope {
        v: 2,
        brand: brand.name.to_owned(),
        cookies: kept,
        landing,
        desktop_only,
        nonce,
    };
    let text = serde_json::to_string(&envelope)
        .map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED"))?;
    if text.len() > MAX_SESSION_STATE_BYTES {
        return Err(ApiError::new(
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED",
        ));
    }
    Ok(BASE64.encode(text.as_bytes()))
}

pub struct DecodedSession {
    pub brand: Brand,
    pub cookies: Vec<StoredCookie>,
    pub landing: Option<String>,
}

pub fn decode_session_state(session_state: &str) -> Result<DecodedSession, ApiError> {
    let invalid = || ApiError::new(StatusCode::BAD_REQUEST, "INVALID_SESSION_STATE");
    let raw = BASE64.decode(session_state).map_err(|_| invalid())?;
    let env: SessionEnvelope = serde_json::from_slice(&raw).map_err(|_| invalid())?;
    if env.v != 2 || env.cookies.len() > MAX_COOKIES {
        return Err(invalid());
    }
    if env.cookies.is_empty() && env.desktop_only != Some(true) {
        return Err(invalid());
    }
    let brand = brand_by_name(&env.brand).ok_or_else(invalid)?;
    if let Some(ref landing) = env.landing {
        if !valid_landing(landing, brand) {
            return Err(invalid());
        }
    }
    for cookie in &env.cookies {
        if !cookie_in_scope(cookie, brand) {
            return Err(invalid());
        }
    }
    Ok(DecodedSession {
        brand,
        cookies: env.cookies,
        landing: env.landing,
    })
}

pub(crate) fn sha256_hex(input: &str) -> String {
    hex::encode(Sha256::digest(input.as_bytes()))
}

pub(crate) fn random_hex(num_bytes: usize) -> String {
    let mut bytes = vec![0u8; num_bytes];
    getrandom::fill(&mut bytes).expect("OS CSPRNG unavailable");
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_and_site_detection_matches_bnp_rules() {
        assert!(is_brand_tld_host("mabanqueprivee.bnpparibas"));
        assert!(!is_brand_tld_host("bnpparibas"));
        assert!(!is_brand_tld_host("mabanque.bnpparibas.evil.example"));

        assert!(on_site(
            &Url::parse("https://mabanqueprivee.bnpparibas/fr/secure/comptes-et-contrats").unwrap(),
            BNP_BRAND
        ));
        assert!(!on_site(
            &Url::parse("https://mabanque.bnpparibas/auth/login").unwrap(),
            BNP_BRAND
        ));
        assert!(!on_site(
            &Url::parse("https://connexion.mabanque.bnpparibas/as/authorization.oauth2").unwrap(),
            BNP_BRAND
        ));
        assert!(!on_site(
            &Url::parse("https://mabanque.bnpparibas/auth/on-login-ciam?code=a&state=b").unwrap(),
            BNP_BRAND
        ));
        assert!(!on_site(
            &Url::parse("https://mabanque.bnpparibas/fr/secure?error_code=6").unwrap(),
            BNP_BRAND
        ));
        assert!(on_site(
            &Url::parse("https://www.hellobank.fr/fr/client/comptes").unwrap(),
            HELLOBANK_BRAND
        ));
    }

    #[test]
    fn navigation_allowlist_restricts_to_bnp_and_hellobank_https_and_about_blank() {
        for allowed in [
            "about:blank",
            "https://mabanque.bnpparibas/auth/login",
            "https://connexion.mabanque.bnpparibas/as/authorization.oauth2",
            "https://mabanquepro.bnpparibas/fr/connexion",
            "https://mabanqueprivee.bnpparibas/fr/secure/comptes",
            "https://bnpparibas.com/",
            "https://group.bnpparibas.com/",
            "https://hellobank.fr/",
            "https://www.hellobank.fr/auth/login?requiredDAC=3",
            "https://connexion.hellobank.fr/login",
        ] {
            assert!(
                is_allowed_navigation_url(&Url::parse(allowed).unwrap()),
                "expected allowed: {allowed}"
            );
        }
        for rejected in [
            "http://mabanque.bnpparibas/auth/login",
            "https://bnpparibas/",
            "https://mabanque.bnpparibas:8443/",
            "https://user@mabanque.bnpparibas/",
            "https://mabanque.bnpparibas.evil.example/",
            "https://evil-hellobank.fr/",
            "https://hellobank.fr.evil.example/",
            "https://evil.example/",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
        ] {
            assert!(
                !is_allowed_navigation_url(&Url::parse(rejected).unwrap()),
                "expected rejected: {rejected}"
            );
        }
    }

    #[test]
    fn session_state_roundtrips_and_rejects_foreign_cookies() {
        let cookies = vec![
            StoredCookie {
                name: "JSESSIONID".to_owned(),
                value: "synthetic".to_owned(),
                domain: Some("mabanqueprivee.bnpparibas".to_owned()),
                path: Some("/".to_owned()),
                expires: None,
                http_only: Some(true),
                secure: Some(true),
                same_site: None,
            },
            StoredCookie {
                name: "tracker".to_owned(),
                value: "drop-me".to_owned(),
                domain: Some("evil.example".to_owned()),
                path: Some("/".to_owned()),
                expires: None,
                http_only: None,
                secure: None,
                same_site: None,
            },
        ];
        let encoded =
            encode_session(BNP_BRAND, cookies, Some("mabanqueprivee.bnpparibas")).unwrap();
        let decoded = decode_session_state(&encoded).unwrap();
        assert_eq!(decoded.brand, BNP_BRAND);
        assert_eq!(
            decoded.landing.as_deref(),
            Some("mabanqueprivee.bnpparibas")
        );
        assert_eq!(decoded.cookies.len(), 1);
        assert_eq!(decoded.cookies[0].name, "JSESSIONID");
    }

    #[test]
    fn desktop_only_envelopes_carry_unique_nonces() {
        let a = encode_session(BNP_BRAND, vec![], None).unwrap();
        let b = encode_session(BNP_BRAND, vec![], None).unwrap();
        assert_ne!(a, b);
        assert_ne!(sha256_hex(&a), sha256_hex(&b));
        assert!(decode_session_state(&a).is_ok());
        assert!(decode_session_state(&b).is_ok());
    }

    #[test]
    fn merge_set_cookies_handles_max_age_zero_and_caps_jar() {
        let mut jar = Vec::new();
        merge_set_cookies(
            &mut jar,
            &["SID=abc; Path=/; HttpOnly".to_owned()],
            "mabanque.bnpparibas",
        );
        assert_eq!(jar.len(), 1);
        merge_set_cookies(
            &mut jar,
            &["SID=; Max-Age=0; Path=/".to_owned()],
            "mabanque.bnpparibas",
        );
        assert!(jar.is_empty());

        let many: Vec<String> = (0..(MAX_COOKIES + 15))
            .map(|i| format!("c{i}=v{i}; Path=/"))
            .collect();
        merge_set_cookies(&mut jar, &many, "mabanque.bnpparibas");
        assert_eq!(jar.len(), MAX_COOKIES);
    }
}
