//! Enable Banking OAuth flow in a dedicated desktop WebView window (#590).
//!
//! Instead of navigating the main Picsou window away to the bank (where a Production HTTPS
//! callback URL cannot hit Picsou's plain-HTTP loopback backend and a port/host drift strands the
//! user outside the app), desktop opens `authLink` in an incognito popup `WebviewWindow` titled
//! "Enable Banking". Its `on_navigation` hook intercepts any redirect whose path ends with
//! `/sync/callback` before the WebView attempts to load it, closes the popup, and hands the raw
//! query string back to the main window so `BankSyncTab` can complete the exchange in place.

use std::sync::{Arc, Mutex};

use serde::Serialize;
use tauri::{
    AppHandle, Manager, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use tokio::sync::oneshot;
use uuid::Uuid;

/// Outcome returned by [`open_enable_banking_auth`] to the main window.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum AuthOutcome {
    /// The bank redirected to a `.../sync/callback` URL; `query` is the raw query string intact.
    Callback { query: String },
    /// The user closed the window (or a newer auth attempt replaced it) before any callback.
    Cancelled,
}

/// Tracks the currently open Enable Banking authorization window so a second attempt or app exit
/// cleanly closes any previous popup instead of leaking windows.
#[derive(Default)]
pub struct ActiveAuthWindow(Mutex<Option<String>>);

impl ActiveAuthWindow {
    fn replace(&self, app: &AppHandle, next_label: String) {
        let previous = {
            let mut slot = self.0.lock().unwrap_or_else(|e| e.into_inner());
            slot.replace(next_label)
        };
        if let Some(label) = previous {
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.destroy();
            }
        }
    }

    fn clear_if(&self, label: &str) {
        let mut slot = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if slot.as_deref() == Some(label) {
            *slot = None;
        }
    }

    pub fn close_active(&self, app: &AppHandle) {
        let active = self.0.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(label) = active {
            if let Some(win) = app.get_webview_window(&label) {
                let _ = win.destroy();
            }
        }
    }
}

/// Returns the raw query string (possibly empty) when `url` is an Enable Banking OAuth callback
/// whose path ends with `/sync/callback`, regardless of scheme, host, or port.
///
/// Pseudo-schemes (`javascript:`, `data:`, `blob:`, `about:`) are rejected even if their body
/// happens to end with `/sync/callback`.
pub fn extract_callback_query(url: &Url) -> Option<String> {
    if matches!(url.scheme(), "javascript" | "data" | "blob" | "about") {
        return None;
    }
    if !url.path().ends_with("/sync/callback") {
        return None;
    }
    Some(url.query().unwrap_or_default().to_owned())
}

/// Navigation allowlist for non-callback URLs inside the Enable Banking auth window.
/// Permits plain HTTPS pages (including non-443 ports some banks use) and subframe helper schemes
/// (`about:blank`, `about:srcdoc`, `blob:`) while blocking `file:`, `javascript:`, `http:`, etc.
fn is_allowed_auth_navigation(url: &Url) -> bool {
    if matches!(url.as_str(), "about:blank" | "about:srcdoc") {
        return true;
    }
    if url.scheme() == "blob" {
        return !url.path().is_empty();
    }
    crate::external::is_openable(url)
}

/// Opens `auth_link` in a dedicated Enable Banking `WebviewWindow` and waits until either the bank
/// redirects to `.../sync/callback` (intercepted and cancelled before loading) or the user closes
/// the window.
#[tauri::command]
pub async fn open_enable_banking_auth(
    app: AppHandle,
    auth_link: String,
) -> Result<AuthOutcome, String> {
    let auth_url = Url::parse(&auth_link).map_err(|_| "Invalid authorization URL.".to_owned())?;
    if !crate::external::is_openable(&auth_url) {
        return Err("Authorization URL must use HTTPS.".to_owned());
    }

    let label = format!("enable-banking-auth-{}", Uuid::new_v4().simple());
    app.state::<ActiveAuthWindow>().replace(&app, label.clone());

    let (tx, rx) = oneshot::channel::<AuthOutcome>();
    let sender = Arc::new(Mutex::new(Some(tx)));

    let app_for_build = app.clone();
    let label_for_build = label.clone();
    let app_for_nav = app.clone();
    let label_for_nav = label.clone();
    let tx_for_nav = Arc::clone(&sender);
    let tx_for_close = Arc::clone(&sender);

    let built = tauri::async_runtime::spawn_blocking(move || {
        let win = WebviewWindowBuilder::new(
            &app_for_build,
            &label_for_build,
            WebviewUrl::External(auth_url),
        )
        .title("Enable Banking")
        .inner_size(1040.0, 780.0)
        .min_inner_size(720.0, 560.0)
        .center()
        .incognito(true)
        .visible(true)
        .focused(true)
        .on_new_window(|url, _features| {
            crate::external::open_in_browser(&url);
            tauri::webview::NewWindowResponse::Deny
        })
        .on_navigation(move |url| {
            if let Some(query) = extract_callback_query(url) {
                // Never log the query string: it carries the single-use OAuth code and state.
                tracing::info!("Enable Banking callback intercepted");
                if let Some(tx) = tx_for_nav.lock().unwrap_or_else(|e| e.into_inner()).take() {
                    let _ = tx.send(AuthOutcome::Callback { query });
                }
                let app_close = app_for_nav.clone();
                let label_close = label_for_nav.clone();
                tauri::async_runtime::spawn(async move {
                    if let Some(win) = app_close.get_webview_window(&label_close) {
                        let _ = win.destroy();
                    }
                });
                return false;
            }
            let allowed = is_allowed_auth_navigation(url);
            if !allowed {
                tracing::warn!(
                    scheme = url.scheme(),
                    "Enable Banking WebView blocked navigation with disallowed scheme"
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
                if let Some(tx) = tx_for_close
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .take()
                {
                    let _ = tx.send(AuthOutcome::Cancelled);
                }
            }
        });
        Ok::<WebviewWindow, tauri::Error>(win)
    })
    .await
    .map_err(|e| format!("Failed to launch Enable Banking window: {e}"))?;

    if let Err(err) = built {
        app.state::<ActiveAuthWindow>().clear_if(&label);
        return Err(format!("Failed to open Enable Banking window: {err}"));
    }

    let outcome = rx.await.unwrap_or(AuthOutcome::Cancelled);
    app.state::<ActiveAuthWindow>().clear_if(&label);
    crate::show_window(&app);
    Ok(outcome)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn callback_urls_match_across_schemes_hosts_ports_and_keep_query_intact() {
        let cases = [
            (
                "https://localhost/sync/callback?code=abc123&state=nonce-1",
                "code=abc123&state=nonce-1",
            ),
            (
                "http://127.0.0.1:47823/sync/callback?code=abc123&state=nonce-1",
                "code=abc123&state=nonce-1",
            ),
            (
                "https://127.0.0.1:8443/sync/callback?code=a%2Bb%3D1&state=s%202",
                "code=a%2Bb%3D1&state=s%202",
            ),
            (
                "http://localhost:5173/sync/callback?error=access_denied&error_description=User%20cancelled",
                "error=access_denied&error_description=User%20cancelled",
            ),
            (
                "https://picsou.example.com/subpath/sync/callback?code=xyz",
                "code=xyz",
            ),
            ("picsou://app/sync/callback?code=custom", "code=custom"),
            ("https://localhost/sync/callback", ""),
        ];

        for (raw, expected_query) in cases {
            let url = Url::parse(raw).unwrap();
            assert_eq!(
                extract_callback_query(&url).as_deref(),
                Some(expected_query),
                "expected callback match for {raw}"
            );
        }
    }

    #[test]
    fn non_callback_urls_are_rejected() {
        for raw in [
            "https://tilisy.enablebanking.com/auth?redirect_uri=https%3A%2F%2Flocalhost%2Fsync%2Fcallback",
            "https://localhost/sync/callback/extra?code=abc",
            "https://localhost/sync/callback2?code=abc",
            "https://localhost/sync/louve/callback?code=abc",
            "https://localhost/sync?code=abc",
            "https://bank.example.com/login",
            "javascript:void('/sync/callback')",
            "data:text/html,/sync/callback",
            "about:blank",
        ] {
            let url = Url::parse(raw).unwrap();
            assert_eq!(
                extract_callback_query(&url),
                None,
                "expected non-callback rejection for {raw}"
            );
        }
    }

    #[test]
    fn auth_navigation_allowlist_permits_https_and_subframe_schemes_only() {
        for allowed in [
            "https://tilisy.enablebanking.com/auth",
            "https://bank.example.com:8443/oauth/authorize",
            "about:blank",
            "about:srcdoc",
            "blob:https://bank.example.com/1234",
        ] {
            assert!(
                is_allowed_auth_navigation(&Url::parse(allowed).unwrap()),
                "expected allowed: {allowed}"
            );
        }
        for blocked in [
            "http://example.com/login",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "https://user:pass@bank.example.com/",
        ] {
            assert!(
                !is_allowed_auth_navigation(&Url::parse(blocked).unwrap()),
                "expected blocked: {blocked}"
            );
        }
    }

    #[test]
    fn capabilities_grant_enable_banking_auth_only_to_loopback_main_window() {
        let cap: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/enable-banking-auth.json")).unwrap();
        assert_eq!(cap["local"], false);
        assert_eq!(cap["windows"], serde_json::json!(["main"]));
        assert_eq!(
            cap["remote"]["urls"],
            serde_json::json!(["http://127.0.0.1:*"])
        );
        let perms: Vec<&str> = cap["permissions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap())
            .collect();
        assert!(perms.contains(&"allow-open-enable-banking-auth"));
        assert!(!perms.contains(&"allow-restore-backup"));
    }
}
