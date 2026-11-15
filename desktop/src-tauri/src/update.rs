//! Updates from the release's `latest.json` (#591), signed with the maintainer's updater key.
//!
//! Only a build given an updater config (`plugins.updater`: endpoint and public key, passed by
//! the release workflow) checks for updates; a dev or PR build never does. Picsou checks once
//! the backend is ready, then every 6 h, and offers the update in the tray. Installing stops the
//! backend and PostgreSQL cleanly first; the new version's first start then copies the data
//! before migrating it, like any update (#588).

use std::sync::Mutex;
use std::time::Duration;

use tauri::menu::MenuItem;
use tauri::{AppHandle, Manager};
use tauri_plugin_updater::UpdaterExt;

use crate::supervisor::{self, Shared};

const EVERY: Duration = Duration::from_secs(6 * 60 * 60);

/// The tray entry, disabled until an update is found.
#[derive(Default)]
pub struct TrayItem(pub Mutex<Option<MenuItem<tauri::Wry>>>);

pub fn enabled(config: &tauri::Config) -> bool {
    config.plugins.0.get("updater").and_then(|u| u.get("pubkey")).and_then(|k| k.as_str()).is_some_and(|k| !k.is_empty())
}

/// Checks now and every 6 h; a found update becomes the tray's "Install Picsou x.y.z".
pub fn watch(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            match app.updater().map_err(|e| e.to_string()) {
                Ok(updater) => match updater.check().await {
                    Ok(Some(update)) => offer(&app, &update.version),
                    Ok(None) => {}
                    Err(e) => eprintln!("picsou: update check failed: {e}"),
                },
                Err(e) => eprintln!("picsou: no updater: {e}"),
            }
            tokio_sleep(EVERY).await;
        }
    });
}

async fn tokio_sleep(duration: Duration) {
    let _ = tauri::async_runtime::spawn_blocking(move || std::thread::sleep(duration)).await;
}

fn offer(app: &AppHandle, version: &str) {
    if let Some(item) = app.state::<TrayItem>().0.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
        let _ = item.set_text(format!("Install Picsou {version}"));
        let _ = item.set_enabled(true);
    }
}

/// Downloads, verifies and installs the update. PostgreSQL and the backend are stopped first,
/// so the installer replaces no file in use and the cluster needs no recovery.
pub fn install(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(e) = install_now(&app).await {
            eprintln!("picsou: update failed: {e}");
        }
    });
}

async fn install_now(app: &AppHandle) -> Result<(), String> {
    let stack = app.state::<Shared>().inner().clone();
    let before_exit = stack.clone();
    // Windows: the hook runs, then the installer starts and the app exits. Picsou's job object
    // would kill the installer with the app, so the hook lifts it once the stack is stopped.
    let updater = app
        .updater_builder()
        .on_before_exit(move || {
            supervisor::stop(&before_exit);
            #[cfg(windows)]
            supervisor::let_the_installer_outlive_the_app();
        })
        .build()
        .map_err(|e| e.to_string())?;
    let Some(update) = updater.check().await.map_err(|e| e.to_string())? else {
        return Ok(());
    };
    // Verified against the public key while downloading: a bad file never stops the stack.
    let bytes = update.download(|_, _| {}, || {}).await.map_err(|e| e.to_string())?;
    supervisor::stop(&stack);
    // Windows exits inside install. Elsewhere the bundle is replaced in place; the restart starts
    // the new version, or the old one again if the install failed, so the app never stays down.
    if let Err(e) = update.install(bytes) {
        eprintln!("picsou: update failed: {e}");
    }
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_build_with_an_updater_key_checks_for_updates() {
        let mut config: tauri::Config = serde_json::from_str(r#"{"identifier":"com.picsou.desktop"}"#).unwrap();
        assert!(!enabled(&config));
        config.plugins.0.insert("updater".into(), serde_json::json!({"pubkey": "", "endpoints": []}));
        assert!(!enabled(&config));
        config.plugins.0.insert("updater".into(), serde_json::json!({"pubkey": "dW50cnVzdGVk", "endpoints": []}));
        assert!(enabled(&config));
    }
}
