//! American Express France native WebView connector.
//!
//! Opens a real OS WebView (`WKWebView` on macOS, `WebView2` on Windows, `WebKitGTK` on Linux)
//! directly on American Express France's official login page so the user authenticates naturally
//! (ID, password, SMS/e-mail 2FA — Akamai sees a real OS browser). Picsou never sees credentials.
//! Once the servicing `/api/servicing/v1/member` probe confirms the user is authenticated,
//! read-only JSON servicing endpoints are fetched inside that WebView, parsed in Rust via
//! `amex_parsers`, cached for the immediate backend sync, and the login window closes cleanly.

use std::collections::HashMap;
use std::future::Future;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::{AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent};
use uuid::Uuid;

use super::{ReadBodyError, read_capped_text};
use super::amex_parsers::{self, AccountPayload};
use super::bnp::{
    ApiError, StoredCookie, WebviewStartResponse, WebviewStatusResponse, build_cookie_header,
    merge_set_cookies, random_hex, sha256_hex, unwrap_eval_string,
};

const LOGIN_URL: &str = "https://www.americanexpress.com/fr-fr/account/login/";
const DASHBOARD_URL: &str = "https://global.americanexpress.com/dashboard";
const API_HOST: &str = "global.americanexpress.com";
const WINDOW_TITLE: &str = "Connexion — American Express";

const MEMBER_PATH: &str = "/api/servicing/v1/member";
const BALANCES_PATH: &str = "/api/servicing/v1/financials/balances?extended_details=deferred,non_deferred,pay_in_full,pay_over_time,early_pay";
const SCHEDULED_PAYMENTS_PATH: &str = "/api/servicing/v1/financials/payments?status=scheduled";
const TRANSACTIONS_PATH: &str = "/api/servicing/v1/financials/transactions";
const ACCOUNT_TOKEN_HEADER: &str = "account_token";
const TRANSACTION_PAGE_SIZE: usize = 100;
const TRANSACTION_HISTORY_SIZE: usize = 1000;

const LOGIN_TTL: Duration = Duration::from_secs(300);
const POLL_INTERVAL: Duration = Duration::from_millis(600);
const FETCH_POLL_INTERVAL: Duration = Duration::from_millis(50);
const FETCH_TIMEOUT: Duration = Duration::from_secs(25);
const SNAPSHOT_CACHE_TTL: Duration = Duration::from_secs(180);
const MAX_PROCESSES: usize = 4;
const MAX_CACHE_ENTRIES: usize = 8;
const MAX_COOKIES: usize = 250;
const MAX_SESSION_STATE_BYTES: usize = 250_000;
const MAX_RESPONSE_BYTES: usize = 8_000_000;
const REFRESH_USER_AGENT: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";

#[derive(Debug, Clone)]
enum ProcessStatus {
    WaitingLogin,
    Fetching,
    Done { session_state: String },
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
    accounts: Vec<AccountPayload>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AmexEnrichment {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    due_date: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    reward_points: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AmexSessionEnvelope {
    cookies: Vec<StoredCookie>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    account_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    enrichment: Option<AmexEnrichment>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    desktop_only: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    nonce: Option<String>,
}

pub struct AmexWebviewManager {
    processes: Mutex<HashMap<String, ProcessEntry>>,
    cache: Mutex<HashMap<String, CachedSnapshot>>,
    http: reqwest::Client,
}

impl AmexWebviewManager {
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
    ) -> Result<WebviewStartResponse, ApiError> {
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
        let window_label = format!("picsou-amex-{process_id}");
        let entry_url = Url::parse(LOGIN_URL)
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

        tracing::info!("AMEX WebView login opened");

        let manager = Arc::clone(self);
        let pid = process_id.clone();
        tokio::spawn(async move {
            manager.watch_login(pid, window).await;
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
                tracing::info!("AMEX WebView login cancelled by user");
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

    fn remember_snapshot(&self, session_state: &str, accounts: Vec<AccountPayload>) {
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
            },
        );
    }

    fn take_cached_snapshot(&self, session_state: &str) -> Option<Vec<AccountPayload>> {
        let key = sha256_hex(session_state);
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let entry = cache.remove(&key)?;
        if entry.expires_at > Instant::now() {
            Some(entry.accounts)
        } else {
            None
        }
    }

    async fn watch_login(&self, process_id: String, window: WebviewWindow) {
        let deadline = Instant::now() + LOGIN_TTL;
        let mut navigated_to_global = false;
        loop {
            if !self.is_waiting(&process_id) {
                return;
            }
            if Instant::now() >= deadline {
                tracing::warn!("AMEX WebView login timed out");
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

            if on_authenticated_page(&current_url) {
                let on_api_origin = current_url
                    .host_str()
                    .is_some_and(|h| h.eq_ignore_ascii_case(API_HOST));
                if !on_api_origin {
                    if !navigated_to_global {
                        navigated_to_global = true;
                        let _ = window
                            .eval(format!("window.location.href = \"{DASHBOARD_URL}\";"));
                    }
                    tokio::time::sleep(POLL_INTERVAL).await;
                    continue;
                }

                // Confirm the servicing API accepts this session and reveals an account_token.
                if let Ok(member_json) = webview_fetch_json(&window, MEMBER_PATH, None).await {
                    let token = amex_parsers::account_token_from_member_payload(&member_json);
                    if token.is_some() {
                        if !self.mark_fetching(&process_id) {
                            return;
                        }
                        tracing::info!(
                            "AMEX WebView authenticated, fetching read-only snapshot"
                        );
                        let (member_due, member_points) =
                            amex_parsers::parse_dashboard_enrichment(&member_json);
                        let member_enrichment = AmexEnrichment {
                            due_date: member_due,
                            reward_points: member_points,
                        };
                        match self
                            .finish_webview_snapshot(&window, token, Some(member_enrichment))
                            .await
                        {
                            Ok((session_state, accounts)) => {
                                self.remember_snapshot(&session_state, accounts);
                                self.mark_done(&process_id, session_state);
                                tracing::info!(
                                    "AMEX WebView snapshot ready, closing login window"
                                );
                            }
                            Err(err) => {
                                tracing::warn!(
                                    "AMEX WebView snapshot failed (code={})",
                                    err.detail
                                );
                                self.mark_failed(&process_id, err.detail);
                            }
                        }
                        let _ = window.destroy();
                        return;
                    }
                }
            }

            tokio::time::sleep(POLL_INTERVAL).await;
        }
    }

    async fn finish_webview_snapshot(
        &self,
        window: &WebviewWindow,
        account_token: Option<String>,
        enrichment_hint: Option<AmexEnrichment>,
    ) -> Result<(String, Vec<AccountPayload>), ApiError> {
        // First import after login uses full history (matching backend-rs store_session_and_queue).
        let accounts = fetch_snapshot_with(
            account_token.as_deref(),
            enrichment_hint.as_ref(),
            true,
            |path, tok| {
                let win = window.clone();
                let path = path.to_owned();
                let tok = tok.map(str::to_owned);
                async move { webview_fetch_json(&win, &path, tok.as_deref()).await }
            },
        )
        .await?;

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

        let enrichment = accounts.first().map(|a| AmexEnrichment {
            due_date: a.due_date.clone(),
            reward_points: a.reward_points,
        });
        let session_state = encode_session(stored, account_token, enrichment)?;
        Ok((session_state, accounts))
    }

    /// Serves `POST /amex/accounts`: reuses the fresh in-memory WebView snapshot when available,
    /// otherwise replays the stored cookies directly against `global.americanexpress.com`.
    pub async fn accounts(
        &self,
        session_state: &str,
        history: bool,
    ) -> Result<Vec<AccountPayload>, ApiError> {
        let decoded = decode_session_state(session_state)?;
        if let Some(accounts) = self.take_cached_snapshot(session_state) {
            return Ok(accounts);
        }
        if decoded.cookies.is_empty() {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }

        let jar = Arc::new(Mutex::new(decoded.cookies));
        let mut account_token = decoded.account_token;
        if account_token.is_none() {
            let member = self.http_fetch_json(&jar, MEMBER_PATH, None).await?;
            account_token = amex_parsers::account_token_from_member_payload(&member);
        }

        fetch_snapshot_with(
            account_token.as_deref(),
            decoded.enrichment.as_ref(),
            history,
            |path, tok| {
                let jar = Arc::clone(&jar);
                let path = path.to_owned();
                let tok = tok.map(str::to_owned);
                async move { self.http_fetch_json(&jar, &path, tok.as_deref()).await }
            },
        )
        .await
    }

    async fn http_fetch_json(
        &self,
        jar: &Arc<Mutex<Vec<StoredCookie>>>,
        path: &str,
        account_token: Option<&str>,
    ) -> Result<Value, ApiError> {
        let cookie_header = {
            let guard = jar.lock().unwrap_or_else(|e| e.into_inner());
            build_cookie_header(&guard, API_HOST)
        };
        let url = format!("https://{API_HOST}{path}");
        let mut req = self
            .http
            .get(&url)
            .header("Accept", "application/json")
            .header("Accept-Language", "fr-FR,fr;q=0.9")
            .header("Origin", format!("https://{API_HOST}"))
            .header("Referer", format!("https://{API_HOST}/"));
        if let Some(token) = account_token {
            req = req.header(ACCOUNT_TOKEN_HEADER, token);
        }
        if !cookie_header.is_empty() {
            req = req.header("Cookie", cookie_header);
        }

        let resp = req
            .send()
            .await
            .map_err(|_| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE"))?;

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

        let status = resp.status();
        if status.is_redirection()
            || status == StatusCode::UNAUTHORIZED
            || status == StatusCode::FORBIDDEN
        {
            return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
        }
        if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
            return Err(ApiError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
            ));
        }
        if status != StatusCode::OK {
            return Err(ApiError::new(
                StatusCode::BAD_GATEWAY,
                "UPSTREAM_FORMAT_CHANGED",
            ));
        }
        let text = read_capped_text(resp, MAX_RESPONSE_BYTES)
            .await
            .map_err(|err| match err {
                ReadBodyError::Transport => {
                    ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE")
                }
                ReadBodyError::TooLarge => {
                    ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED")
                }
            })?;
        amex_parsers::loads(&text).map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))
    }
}

/// Shared snapshot orchestrator for both in-WebView fetches and direct HTTP refreshes.
async fn fetch_snapshot_with<F, Fut>(
    account_token: Option<&str>,
    enrichment: Option<&AmexEnrichment>,
    history: bool,
    fetch: F,
) -> Result<Vec<AccountPayload>, ApiError>
where
    F: Fn(&str, Option<&str>) -> Fut,
    Fut: Future<Output = Result<Value, ApiError>>,
{
    let balance_json = fetch(BALANCES_PATH, account_token).await?;
    let parsed_balance = amex_parsers::parse_balance(&balance_json, account_token)
        .map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))?;
    let effective_token = account_token.or(parsed_balance.account_token.as_deref());

    let scheduled_due = match fetch(SCHEDULED_PAYMENTS_PATH, effective_token).await {
        Ok(payments) => amex_parsers::parse_scheduled_due_date(&payments, effective_token),
        Err(err) if err.detail == "SESSION_EXPIRED" => return Err(err),
        Err(_) => None,
    };

    let limit = if history {
        TRANSACTION_HISTORY_SIZE
    } else {
        TRANSACTION_PAGE_SIZE
    };
    let tx_path = |status: &str| {
        if history {
            format!(
                "{TRANSACTIONS_PATH}?limit={limit}&status={status}&extended_details=merchant,category&beforeDate=1900-01-01"
            )
        } else {
            format!(
                "{TRANSACTIONS_PATH}?limit={limit}&status={status}&extended_details=merchant,category"
            )
        }
    };

    let posted_json = fetch(&tx_path("posted"), effective_token).await?;
    let posted = amex_parsers::parse_transactions(&posted_json, Some("posted"))
        .map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))?;

    let (pending, pending_complete) = match fetch(&tx_path("pending"), effective_token).await {
        Ok(pending_json) => match amex_parsers::parse_transactions(&pending_json, Some("pending")) {
            Ok(list) => (list, true),
            Err(_) => (Vec::new(), false),
        },
        Err(err) if err.detail == "SESSION_EXPIRED" => return Err(err),
        Err(_) => (Vec::new(), false),
    };

    let merged = amex_parsers::merge_transactions(posted, pending);
    let account = amex_parsers::build_account_payload(
        parsed_balance,
        scheduled_due,
        enrichment.and_then(|e| e.due_date.clone()),
        enrichment.and_then(|e| e.reward_points),
        merged,
        pending_complete,
    );
    Ok(vec![account])
}

/// Runs a same-origin `fetch(...)` inside the authenticated Amex WebView (`global.americanexpress.com`).
async fn webview_fetch_json(
    window: &WebviewWindow,
    path: &str,
    account_token: Option<&str>,
) -> Result<Value, ApiError> {
    let slot = format!("__picsou_amex_{}", random_hex(12));
    let path_js = serde_json::to_string(path).unwrap_or_else(|_| "\"/\"".to_owned());
    let token_js = match account_token {
        Some(t) => serde_json::to_string(t).unwrap_or_else(|_| "null".to_owned()),
        None => "null".to_owned(),
    };

    let start_js = format!(
        r#"(function(){{
  var k = "{slot}";
  window[k] = null;
  var headers = {{ "Accept": "application/json" }};
  var tok = {token_js};
  if (tok !== null) {{
    headers["{ACCOUNT_TOKEN_HEADER}"] = tok;
  }}
  fetch({path_js}, {{ method: "GET", credentials: "same-origin", redirect: "error", headers: headers }})
    .then(function(r) {{
      return r.text().then(function(t) {{
        window[k] = JSON.stringify({{ ok: true, status: r.status, text: t }});
      }});
    }})
    .catch(function() {{
      window[k] = JSON.stringify({{ ok: false, status: 0, text: "" }});
    }});
}})();"#
    );

    window
        .eval(&start_js)
        .map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"))?;

    let read_js = format!(
        r#"(function(){{ var k = "{slot}"; var v = window[k]; if (v !== null && v !== undefined) {{ delete window[k]; return v; }} return ""; }})()"#
    );

    let deadline = Instant::now() + FETCH_TIMEOUT;
    let raw_payload = loop {
        if Instant::now() >= deadline {
            return Err(ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"));
        }
        let (tx, rx) = tokio::sync::oneshot::channel::<String>();
        let sender = Arc::new(Mutex::new(Some(tx)));
        if window
            .eval_with_callback(&read_js, move |val| {
                if let Some(tx) = sender.lock().unwrap_or_else(|e| e.into_inner()).take() {
                    let _ = tx.send(val);
                }
            })
            .is_err()
        {
            return Err(ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"));
        }
        if let Ok(Ok(res)) = tokio::time::timeout(Duration::from_secs(3), rx).await {
            if let Some(unwrapped) = unwrap_eval_string(&res) {
                if !unwrapped.is_empty() {
                    break unwrapped;
                }
            }
        }
        tokio::time::sleep(FETCH_POLL_INTERVAL).await;
    };

    #[derive(Deserialize)]
    struct JsFetchReply {
        ok: bool,
        status: u16,
        text: String,
    }

    let reply: JsFetchReply = serde_json::from_str(&raw_payload)
        .map_err(|_| ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED"))?;
    if !reply.ok || reply.text.len() > MAX_RESPONSE_BYTES {
        return Err(ApiError::new(StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE"));
    }
    if reply.status == 401 || reply.status == 403 {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "SESSION_EXPIRED"));
    }
    if reply.status == 429 || reply.status >= 500 {
        return Err(ApiError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "UPSTREAM_UNAVAILABLE",
        ));
    }
    if reply.status != 200 {
        return Err(ApiError::new(
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED",
        ));
    }
    amex_parsers::loads(&reply.text).map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.0))
}

// ─── host / URL / session helpers ────────────────────────────────────────────

pub fn is_amex_host(host: &str) -> bool {
    let lowered = host.to_ascii_lowercase();
    lowered == "americanexpress.com" || lowered.ends_with(".americanexpress.com")
}

/// True once the WebView has left the login / 2FA / logon screens and reached an authenticated
/// American Express surface (`global.americanexpress.com/dashboard`, `/overview`, `/myca/`, etc.).
pub fn on_authenticated_page(url: &Url) -> bool {
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
    if !is_amex_host(host) {
        return false;
    }
    let path = url.path().to_ascii_lowercase();
    if path.contains("/account/login")
        || path.contains("two-step-verification")
        || path.contains("/logon/")
        || path.contains("/identity/")
        || path.contains("/logout")
        || path == "/"
        || path == "/fr-fr"
        || path == "/fr-fr/"
    {
        return false;
    }
    host.eq_ignore_ascii_case(API_HOST) || path.contains("/myca/") || path.contains("/dashboard")
}

pub fn cookie_in_scope(cookie: &StoredCookie) -> bool {
    let domain = cookie
        .domain
        .as_deref()
        .unwrap_or("")
        .trim_start_matches('.')
        .to_ascii_lowercase();
    !domain.is_empty() && is_amex_host(&domain)
}

fn encode_session(
    cookies: Vec<StoredCookie>,
    account_token: Option<String>,
    enrichment: Option<AmexEnrichment>,
) -> Result<String, ApiError> {
    let kept: Vec<StoredCookie> = cookies.into_iter().filter(cookie_in_scope).collect();
    if kept.len() > MAX_COOKIES {
        return Err(ApiError::new(
            StatusCode::BAD_GATEWAY,
            "UPSTREAM_FORMAT_CHANGED",
        ));
    }
    let desktop_only = kept.is_empty().then_some(true);
    let nonce = desktop_only.map(|_| random_hex(16));
    let envelope = AmexSessionEnvelope {
        cookies: kept,
        account_token,
        enrichment,
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
    Ok(text)
}

struct DecodedAmexSession {
    cookies: Vec<StoredCookie>,
    account_token: Option<String>,
    enrichment: Option<AmexEnrichment>,
}

fn decode_session_state(session_state: &str) -> Result<DecodedAmexSession, ApiError> {
    let invalid = || ApiError::new(StatusCode::BAD_REQUEST, "INVALID_DATA");
    let env: AmexSessionEnvelope = serde_json::from_str(session_state).map_err(|_| invalid())?;
    if env.cookies.len() > MAX_COOKIES {
        return Err(invalid());
    }
    if env.cookies.is_empty() && env.desktop_only != Some(true) {
        return Err(invalid());
    }
    for cookie in &env.cookies {
        if cookie.name.is_empty() || !cookie_in_scope(cookie) {
            return Err(invalid());
        }
    }
    Ok(DecodedAmexSession {
        cookies: env.cookies,
        account_token: env.account_token,
        enrichment: env.enrichment,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn authenticated_page_detection_ignores_login_and_2fa() {
        assert!(!on_authenticated_page(
            &Url::parse("https://www.americanexpress.com/fr-fr/account/login/").unwrap()
        ));
        assert!(!on_authenticated_page(
            &Url::parse("https://www.americanexpress.com/fr-fr/account/two-step-verification/verify")
                .unwrap()
        ));
        assert!(!on_authenticated_page(
            &Url::parse("https://evil.example/dashboard").unwrap()
        ));
        assert!(on_authenticated_page(
            &Url::parse("https://global.americanexpress.com/dashboard").unwrap()
        ));
        assert!(on_authenticated_page(
            &Url::parse("https://global.americanexpress.com/overview").unwrap()
        ));
        assert!(on_authenticated_page(
            &Url::parse("https://www.americanexpress.com/myca/intl/acctmgmt/emea/myacct.do")
                .unwrap()
        ));
    }

    #[test]
    fn session_state_roundtrips_and_filters_foreign_cookies() {
        let cookies = vec![
            StoredCookie {
                name: "amex_sess".to_owned(),
                value: "synthetic".to_owned(),
                domain: Some(".americanexpress.com".to_owned()),
                path: Some("/".to_owned()),
                expires: None,
                http_only: Some(true),
                secure: Some(true),
                same_site: None,
            },
            StoredCookie {
                name: "tracker".to_owned(),
                value: "drop".to_owned(),
                domain: Some("evil.example".to_owned()),
                path: Some("/".to_owned()),
                expires: None,
                http_only: None,
                secure: None,
                same_site: None,
            },
        ];
        let encoded = encode_session(
            cookies,
            Some("tok-synthetic".to_owned()),
            Some(AmexEnrichment {
                due_date: Some("2026-04-05".to_owned()),
                reward_points: Some(420),
            }),
        )
        .unwrap();
        let decoded = decode_session_state(&encoded).unwrap();
        assert_eq!(decoded.cookies.len(), 1);
        assert_eq!(decoded.cookies[0].name, "amex_sess");
        assert_eq!(decoded.account_token.as_deref(), Some("tok-synthetic"));
        assert_eq!(
            decoded.enrichment.as_ref().and_then(|e| e.due_date.as_deref()),
            Some("2026-04-05")
        );
        assert_eq!(
            decoded.enrichment.as_ref().and_then(|e| e.reward_points),
            Some(420)
        );
    }

    #[tokio::test]
    async fn snapshot_fetch_handles_pending_failure_without_dropping_posted() {
        let accounts = fetch_snapshot_with(Some("tok-1"), None, false, |path, _tok| {
            let path = path.to_owned();
            async move {
                if path.starts_with("/api/servicing/v1/financials/balances") {
                    Ok(json!([{"account_token": "tok-1", "statement_balance_amount": 10.0}]))
                } else if path.contains("status=pending") {
                    Err(ApiError::new(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "UPSTREAM_UNAVAILABLE",
                    ))
                } else if path.contains("status=posted") {
                    Ok(json!({
                        "transactions": [{"charge_date": "2026-01-02", "description": "posted", "amount": "1.00"}]
                    }))
                } else {
                    Err(ApiError::new(StatusCode::NOT_FOUND, "UPSTREAM_FORMAT_CHANGED"))
                }
            }
        })
        .await
        .unwrap();

        assert_eq!(accounts.len(), 1);
        assert!(!accounts[0].pending_complete);
        assert!(accounts[0].snapshot_complete);
        assert_eq!(accounts[0].transactions.len(), 1);
        assert_eq!(accounts[0].transactions[0].status.as_deref(), Some("posted"));
    }

    #[tokio::test]
    async fn cached_snapshot_is_returned_on_first_accounts_call() {
        let mgr = AmexWebviewManager::new();
        let state = encode_session(vec![], Some("tok-1".to_owned()), None).unwrap();
        let sample = fetch_snapshot_with(Some("tok-1"), None, false, |path, _| {
            let path = path.to_owned();
            async move {
                if path.starts_with("/api/servicing/v1/financials/balances") {
                    Ok(json!([{"account_token": "tok-1", "total_balance_amount": "55.00"}]))
                } else if path.contains("status=posted") || path.contains("status=pending") {
                    Ok(json!({"transactions": []}))
                } else {
                    Ok(json!([]))
                }
            }
        })
        .await
        .unwrap();
        mgr.remember_snapshot(&state, sample.clone());

        let returned = mgr.accounts(&state, true).await.unwrap();
        assert_eq!(returned, sample);

        // Once consumed, desktop-only empty-cookie session reports SESSION_EXPIRED on next refresh.
        let err = mgr.accounts(&state, false).await.unwrap_err();
        assert_eq!(err.status, StatusCode::UNAUTHORIZED);
        assert_eq!(err.detail, "SESSION_EXPIRED");
    }

    #[test]
    fn desktop_only_envelopes_carry_unique_nonces() {
        let a = encode_session(vec![], Some("tok-1".to_owned()), None).unwrap();
        let b = encode_session(vec![], Some("tok-1".to_owned()), None).unwrap();
        assert_ne!(a, b);
        assert_ne!(sha256_hex(&a), sha256_hex(&b));
        assert!(decode_session_state(&a).is_ok());
        assert!(decode_session_state(&b).is_ok());
    }
}
