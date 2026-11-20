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

/// `logs/update.log` in the data folder: a release build has no console, so stderr is lost.
fn note<R: tauri::Runtime>(app: &AppHandle<R>, line: &str) {
    use std::io::Write;
    let Ok(dir) = app.path().app_local_data_dir() else { return };
    let logs = dunce::simplified(&dir).join("logs");
    let _ = std::fs::create_dir_all(&logs);
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(logs.join("update.log")) {
        let _ = writeln!(file, "{secs} {line}");
    }
}

use crate::supervisor::{self, Shared};

const EVERY: Duration = Duration::from_secs(6 * 60 * 60);

/// Passed to a second launch: installs the available update, as the tray entry does.
pub const INSTALL_ARG: &str = "--install-update";

pub fn wants_install(args: impl IntoIterator<Item = String>) -> bool {
    args.into_iter().any(|arg| arg == INSTALL_ARG)
}

/// The tray entry, disabled until an update is found.
#[derive(Default)]
pub struct TrayItem(pub Mutex<Option<MenuItem<tauri::Wry>>>);

pub fn enabled(config: &tauri::Config) -> bool {
    has_key(config) && self_updatable()
}

fn has_key(config: &tauri::Config) -> bool {
    config.plugins.0.get("updater").and_then(|u| u.get("pubkey")).and_then(|k| k.as_str()).is_some_and(|k| !k.is_empty())
}

/// Linux: only the AppImage replaces itself. A .deb belongs to the package manager, and the
/// release publishes no .deb update, so a .deb install never offers one.
fn self_updatable() -> bool {
    !cfg!(target_os = "linux") || std::env::var_os("APPIMAGE").is_some()
}

/// Checks now and every 6 h; a found update becomes the tray's "Install Picsou x.y.z".
pub fn watch(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            match app.updater().map_err(|e| e.to_string()) {
                Ok(updater) => match updater.check().await {
                    Ok(Some(update)) => {
                        note(&app, &format!("update {} available", update.version));
                        offer(&app, &update.version)
                    }
                    Ok(None) => {}
                    Err(e) => note(&app, &format!("update check failed: {e}")),
                },
                Err(e) => note(&app, &format!("no updater: {e}")),
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
            note(&app, &format!("update failed: {e}"));
        }
    });
}

async fn install_now(app: &AppHandle) -> Result<(), String> {
    let stack = app.state::<Shared>().inner().clone();
    let updater = app.updater().map_err(|e| e.to_string())?;
    let Some(update) = updater.check().await.map_err(|e| e.to_string())? else {
        note(app, "install asked, no update available");
        return Ok(());
    };
    note(app, &format!("downloading {}", update.version));
    // Verified against the public key while downloading: a bad file never stops the stack.
    let bytes = update.download(|_, _| {}, || {}).await.map_err(|e| e.to_string())?;
    note(app, &format!("downloaded and verified {} bytes, stopping the stack", bytes.len()));
    supervisor::stop(&stack);
    note(app, "stack stopped, installing");
    install_bytes(app, &update, bytes);
    Ok(())
}

/// Windows: the plugin's own install hung between its exit hook and the installer start, with
/// no error (seen on windows-latest; cause not found). Picsou starts the verified NSIS installer
/// itself, outside its job object, then exits; the installer replaces it and restarts it (`/R`).
#[cfg(windows)]
fn install_bytes(app: &AppHandle, update: &tauri_plugin_updater::Update, bytes: Vec<u8>) {
    use std::os::windows::process::CommandExt;
    const DETACHED_PROCESS: u32 = 0x0000_0008;
    let started = (|| -> std::io::Result<()> {
        let dir = std::env::temp_dir().join(format!("picsou-update-{}", update.version));
        std::fs::create_dir_all(&dir)?;
        let installer = dir.join(format!("Picsou-{}-setup.exe", update.version));
        std::fs::write(&installer, &bytes)?;
        supervisor::let_the_installer_outlive_the_app();
        std::process::Command::new(&installer)
            .args(["/P", "/UPDATE", "/R"])
            .creation_flags(DETACHED_PROCESS)
            .spawn()?;
        Ok(())
    })();
    match started {
        Ok(()) => {
            note(app, "installer started, exiting");
            app.exit(0);
        }
        Err(e) => {
            note(app, &format!("installer did not start, restarting the current version: {e}"));
            app.restart();
        }
    }
}

/// Linux (AppImage) and macOS replace the bundle in place; the restart starts the new version,
/// or the old one again if the install failed, so the app never stays down.
#[cfg(not(windows))]
fn install_bytes(app: &AppHandle, update: &tauri_plugin_updater::Update, bytes: Vec<u8>) {
    match update.install(bytes) {
        Ok(()) => note(app, "installed in place, restarting"),
        Err(e) => note(app, &format!("install failed, restarting the current version: {e}")),
    }
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_build_with_an_updater_key_checks_for_updates() {
        let mut config: tauri::Config = serde_json::from_str(r#"{"identifier":"com.picsou.desktop"}"#).unwrap();
        assert!(!has_key(&config));
        config.plugins.0.insert("updater".into(), serde_json::json!({"pubkey": "", "endpoints": []}));
        assert!(!has_key(&config));
        config.plugins.0.insert("updater".into(), serde_json::json!({"pubkey": "dW50cnVzdGVk", "endpoints": []}));
        assert!(has_key(&config));
    }

    #[test]
    fn the_install_argument_is_recognised() {
        assert!(wants_install(["picsou".to_owned(), INSTALL_ARG.to_owned()]));
        assert!(!wants_install(["picsou".to_owned(), "--hidden".to_owned()]));
    }
}
