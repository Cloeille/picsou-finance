//! Updates from the release's `latest.json` (#591), signed with the maintainer's updater key.
//!
//! Only a build given an updater config (`plugins.updater`: endpoint and public key, passed by
//! the release workflow) checks for updates; a dev or PR build never does. Picsou checks once
//! the backend is ready, then every 6 h, and offers the update in the tray. Installing stops the
//! embedded SQLite backend cleanly first; the new version's first start then copies the data
//! before migrating it, like any update (#588).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use serde::Serialize;
use tauri::menu::MenuItem;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::UpdaterExt;

/// `logs/update.log` in the data folder: a release build has no console, so stderr is lost.
fn note<R: tauri::Runtime>(app: &AppHandle<R>, line: &str) {
    use std::io::Write;
    let Ok(dir) = app.path().app_local_data_dir() else {
        return;
    };
    let logs = dunce::simplified(&dir).join("logs");
    let _ = std::fs::create_dir_all(&logs);
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(logs.join("update.log"))
    {
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

/// Event emitted to the main window whenever the desktop updater state changes.
pub const STATUS_EVENT: &str = "desktop-update-status";

/// Snapshot of the desktop updater exposed to the main window via IPC and [`STATUS_EVENT`].
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UpdateStatus {
    pub enabled: bool,
    pub current_version: String,
    pub available_version: Option<String>,
    pub checked: bool,
    pub installing: bool,
    pub error: Option<String>,
}

impl UpdateStatus {
    fn disabled(current_version: &str) -> Self {
        Self {
            enabled: false,
            current_version: current_version.to_owned(),
            available_version: None,
            checked: false,
            installing: false,
            error: None,
        }
    }
}

#[derive(Default)]
struct CachedUpdate {
    available_version: Option<String>,
    checked: bool,
}

/// Last known update check result, shared between the background watcher, the tray, and IPC commands.
#[derive(Default)]
pub struct UpdateState(Mutex<CachedUpdate>);

impl UpdateState {
    fn record_available(&self, version: &str) {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        guard.available_version = Some(version.to_owned());
        guard.checked = true;
    }

    /// Marks the app up to date. Returns true when a previously offered version was cleared.
    fn record_up_to_date(&self) -> bool {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let had_offer = guard.available_version.take().is_some();
        guard.checked = true;
        had_offer
    }

    fn snapshot(
        &self,
        enabled: bool,
        current_version: &str,
        installing: bool,
        error: Option<String>,
    ) -> UpdateStatus {
        if !enabled {
            return UpdateStatus::disabled(current_version);
        }
        let guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        UpdateStatus {
            enabled: true,
            current_version: current_version.to_owned(),
            available_version: guard.available_version.clone(),
            checked: guard.checked,
            installing,
            error,
        }
    }
}

/// The tray entry, disabled until an update is found.
#[derive(Default)]
pub struct TrayItem(pub Mutex<Option<MenuItem<tauri::Wry>>>);

/// Prevents overlapping downloads/installs on double clicks or repeated `--install-update` calls.
struct InstallGate {
    active: AtomicBool,
}

struct InstallGuard<'a> {
    gate: &'a InstallGate,
}

impl Drop for InstallGuard<'_> {
    fn drop(&mut self) {
        self.gate.active.store(false, Ordering::SeqCst);
    }
}

impl InstallGate {
    const fn new() -> Self {
        Self {
            active: AtomicBool::new(false),
        }
    }

    fn try_begin(&self) -> Option<InstallGuard<'_>> {
        self.active
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .ok()
            .map(|_| InstallGuard { gate: self })
    }

    fn is_active(&self) -> bool {
        self.active.load(Ordering::SeqCst)
    }
}

static INSTALLING: InstallGate = InstallGate::new();

#[derive(Debug, PartialEq, Eq)]
enum InstallOutcome {
    NoUpdate,
    Started,
}

fn begin_install(gate: &InstallGate, on_start: impl FnOnce()) -> Option<InstallGuard<'_>> {
    let guard = gate.try_begin()?;
    on_start();
    Some(guard)
}

async fn finish_guarded_install<F, H>(_guard: InstallGuard<'_>, work: F, on_outcome: H)
where
    F: std::future::Future<Output = Result<InstallOutcome, String>>,
    H: FnOnce(Result<InstallOutcome, String>),
{
    on_outcome(work.await);
}

pub fn enabled(config: &tauri::Config) -> bool {
    has_key(config) && self_updatable()
}

fn has_key(config: &tauri::Config) -> bool {
    config
        .plugins
        .0
        .get("updater")
        .and_then(|u| u.get("pubkey"))
        .and_then(|k| k.as_str())
        .is_some_and(|k| !k.is_empty())
}

/// Linux: only the AppImage replaces itself. A .deb belongs to the package manager, and the
/// release publishes no .deb update, so a .deb install never offers one.
fn self_updatable() -> bool {
    !cfg!(target_os = "linux") || std::env::var_os("APPIMAGE").is_some()
}

/// Checks now and every 6 h; a found update becomes the tray's "Install Picsou x.y.z"
/// and is emitted to the main window.
pub fn watch(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            match app.updater().map_err(|e| e.to_string()) {
                Ok(updater) => match updater.check().await {
                    Ok(Some(update)) => {
                        note(&app, &format!("update {} available", update.version));
                        offer(&app, &update.version);
                    }
                    Ok(None) => mark_up_to_date(&app),
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

fn current_status(app: &AppHandle, installing: bool, error: Option<String>) -> UpdateStatus {
    app.state::<UpdateState>().snapshot(
        enabled(app.config()),
        supervisor::APP_VERSION,
        installing,
        error,
    )
}

fn emit_status(app: &AppHandle, status: &UpdateStatus) {
    let _ = app.emit_to("main", STATUS_EVENT, status);
}

fn set_tray(app: &AppHandle, text: &str, enabled: bool) {
    if let Some(item) = app
        .state::<TrayItem>()
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .as_ref()
    {
        let _ = item.set_text(text);
        let _ = item.set_enabled(enabled);
    }
}

fn offer(app: &AppHandle, version: &str) {
    app.state::<UpdateState>().record_available(version);
    if INSTALLING.is_active() {
        return;
    }
    let text = crate::i18n::tray_install(crate::i18n::Lang::system(), version);
    set_tray(app, &text, true);
    let status = current_status(app, false, None);
    emit_status(app, &status);
}

fn mark_up_to_date(app: &AppHandle) {
    let had_offer = app.state::<UpdateState>().record_up_to_date();
    if INSTALLING.is_active() {
        return;
    }
    if had_offer {
        set_tray(
            app,
            crate::i18n::tray_up_to_date(crate::i18n::Lang::system()),
            false,
        );
    }
    let status = current_status(app, false, None);
    emit_status(app, &status);
}

/// Returns the cached desktop updater status without issuing a network request.
#[tauri::command]
pub fn get_desktop_update_status(app: AppHandle) -> UpdateStatus {
    current_status(&app, INSTALLING.is_active(), None)
}

/// Checks for a desktop update now and returns the resulting status.
#[tauri::command]
pub async fn check_desktop_update(app: AppHandle) -> UpdateStatus {
    if !enabled(app.config()) {
        return UpdateStatus::disabled(supervisor::APP_VERSION);
    }
    if INSTALLING.is_active() {
        return current_status(&app, true, None);
    }
    match app.updater().map_err(|e| e.to_string()) {
        Ok(updater) => match updater.check().await {
            Ok(Some(update)) => {
                note(&app, &format!("update {} available", update.version));
                offer(&app, &update.version);
                current_status(&app, INSTALLING.is_active(), None)
            }
            Ok(None) => {
                note(&app, "update check: up to date");
                mark_up_to_date(&app);
                current_status(&app, INSTALLING.is_active(), None)
            }
            Err(e) => {
                let err = e.to_string();
                note(&app, &format!("update check failed: {err}"));
                current_status(&app, INSTALLING.is_active(), Some(err))
            }
        },
        Err(e) => {
            note(&app, &format!("no updater: {e}"));
            current_status(&app, INSTALLING.is_active(), Some(e))
        }
    }
}

/// Starts installing an available desktop update via the existing install flow.
#[tauri::command]
pub fn install_desktop_update(app: AppHandle) -> Result<(), String> {
    if !enabled(app.config()) {
        return Err("Desktop updater is not enabled in this build.".to_owned());
    }
    install(&app);
    Ok(())
}

fn update_failed_script(message: &str) -> String {
    let text = serde_json::to_string(message).unwrap_or_else(|_| "\"\"".to_owned());
    format!(
        r##"(function(){{
  var id = "picsou-update-error";
  var prev = document.getElementById(id);
  if (prev) prev.remove();
  var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  var box = document.createElement("div");
  box.id = id;
  box.setAttribute("role", "alert");
  box.style.cssText = "position:fixed;right:1rem;bottom:1rem;z-index:2147483647;max-width:24rem;padding:0.875rem 1rem;border-radius:0.5rem;display:flex;gap:0.75rem;align-items:flex-start;font:0.875rem/1.45 \"Geist Variable\",system-ui,-apple-system,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,0.14);border:1px solid " + (dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.12)") + ";background:" + (dark ? "#171717" : "#ffffff") + ";color:" + (dark ? "#fafafa" : "#0a0a0a") + ";";
  var msg = document.createElement("span");
  msg.textContent = {text};
  msg.style.flex = "1";
  var close = document.createElement("button");
  close.type = "button";
  close.textContent = "\u00d7";
  close.setAttribute("aria-label", "Close");
  close.style.cssText = "border:0;background:transparent;color:inherit;font:inherit;font-size:1.125rem;line-height:1;cursor:pointer;padding:0 0.25rem;opacity:0.7;";
  close.addEventListener("click", function(){{ box.remove(); }});
  box.appendChild(msg);
  box.appendChild(close);
  (document.body || document.documentElement).appendChild(box);
}})();"##
    )
}

fn show_update_error(app: &AppHandle, reason: &str) {
    let lang = crate::i18n::Lang::system();
    set_tray(app, crate::i18n::tray_install_failed(lang), true);
    let message = crate::i18n::update_install_failed(lang, reason);
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
        let _ = window.eval(update_failed_script(&message));
    }
}

/// Downloads, verifies and installs the update. The backend and sidecars are stopped first,
/// so the installer replaces no file in use and SQLite closes cleanly.
pub fn install(app: &AppHandle) {
    let lang = crate::i18n::Lang::system();
    // Acquire synchronously so two rapid tray clicks cannot both spawn an install.
    let Some(guard) = begin_install(&INSTALLING, || {
        set_tray(app, crate::i18n::tray_installing(lang), false);
        let status = current_status(app, true, None);
        emit_status(app, &status);
    }) else {
        note(app, "install already in progress, ignoring");
        return;
    };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        finish_guarded_install(guard, install_now(&app), |outcome| match outcome {
            Ok(InstallOutcome::NoUpdate) => {
                app.state::<UpdateState>().record_up_to_date();
                set_tray(&app, crate::i18n::tray_up_to_date(lang), false);
                let status = current_status(&app, false, None);
                emit_status(&app, &status);
            }
            Ok(InstallOutcome::Started) => {}
            Err(e) => {
                note(&app, &format!("update failed: {e}"));
                show_update_error(&app, &e);
                // `show_update_error` already surfaces the localized install failure toast;
                // emit `error: None` with `installing: false` so the UI re-enables retrying
                // without showing a misleading check-failed banner.
                let status = current_status(&app, false, None);
                emit_status(&app, &status);
            }
        })
        .await;
    });
}

async fn install_now(app: &AppHandle) -> Result<InstallOutcome, String> {
    let stack = app.state::<Shared>().inner().clone();
    let updater = app.updater().map_err(|e| e.to_string())?;
    let Some(update) = updater.check().await.map_err(|e| e.to_string())? else {
        note(app, "install asked, no update available");
        return Ok(InstallOutcome::NoUpdate);
    };
    note(app, &format!("downloading {}", update.version));
    // Verified against the public key while downloading: a bad file never stops the stack.
    let bytes = update
        .download(|_, _| {}, || {})
        .await
        .map_err(|e| e.to_string())?;
    note(
        app,
        &format!(
            "downloaded and verified {} bytes, stopping the stack",
            bytes.len()
        ),
    );
    supervisor::stop(&stack);
    note(app, "stack stopped, installing");
    install_bytes(app, &update, bytes);
    Ok(InstallOutcome::Started)
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
            note(
                app,
                &format!("installer did not start, restarting the current version: {e}"),
            );
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
        Err(e) => note(
            app,
            &format!("install failed, restarting the current version: {e}"),
        ),
    }
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_build_with_an_updater_key_checks_for_updates() {
        let mut config: tauri::Config =
            serde_json::from_str(r#"{"identifier":"com.picsou.desktop"}"#).unwrap();
        assert!(!has_key(&config));
        config.plugins.0.insert(
            "updater".into(),
            serde_json::json!({"pubkey": "", "endpoints": []}),
        );
        assert!(!has_key(&config));
        config.plugins.0.insert(
            "updater".into(),
            serde_json::json!({"pubkey": "dW50cnVzdGVk", "endpoints": []}),
        );
        assert!(has_key(&config));
    }

    #[test]
    fn the_install_argument_is_recognised() {
        assert!(wants_install(["picsou".to_owned(), INSTALL_ARG.to_owned()]));
        assert!(!wants_install(["picsou".to_owned(), "--hidden".to_owned()]));
    }

    #[tokio::test]
    async fn double_click_is_ignored_while_installing_and_failures_are_reported_and_retryable() {
        use std::sync::atomic::AtomicUsize;
        use std::sync::Arc;
        use tokio::sync::Notify;

        let gate = Arc::new(InstallGate::new());
        let starts = Arc::new(AtomicUsize::new(0));
        let failures: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        let started = Arc::new(Notify::new());
        let release = Arc::new(Notify::new());

        let (g1, st1, f1, s1, rel1) = (
            Arc::clone(&gate),
            Arc::clone(&starts),
            Arc::clone(&failures),
            Arc::clone(&started),
            Arc::clone(&release),
        );
        let first = tokio::spawn(async move {
            let guard = begin_install(&g1, || {
                st1.fetch_add(1, Ordering::SeqCst);
            })
            .expect("first install must acquire gate");
            finish_guarded_install(
                guard,
                async move {
                    s1.notify_one();
                    rel1.notified().await;
                    Err("network timeout".to_owned())
                },
                |outcome| {
                    if let Err(err) = outcome {
                        f1.lock().unwrap().push(err);
                    }
                },
            )
            .await;
        });

        started.notified().await;
        assert!(gate.is_active());

        // Second click while the first install is still in flight must be ignored.
        let second = begin_install(&gate, || {
            starts.fetch_add(1, Ordering::SeqCst);
        });
        assert!(
            second.is_none(),
            "second concurrent install must be ignored"
        );
        assert_eq!(starts.load(Ordering::SeqCst), 1);

        release.notify_one();
        first.await.unwrap();
        assert!(!gate.is_active());
        assert_eq!(
            *failures.lock().unwrap(),
            vec!["network timeout".to_owned()],
            "install failure must be surfaced"
        );

        // Once the failed install finishes, a retry must be allowed.
        let retry = begin_install(&gate, || {
            starts.fetch_add(1, Ordering::SeqCst);
        })
        .expect("retry after failure must acquire gate");
        finish_guarded_install(retry, async { Ok(InstallOutcome::NoUpdate) }, |_| {}).await;
        assert_eq!(starts.load(Ordering::SeqCst), 2);
        assert!(!gate.is_active());
    }

    #[test]
    fn update_failed_script_escapes_message_and_uses_text_content() {
        let script = update_failed_script("bad \"sig\" <script>\nline2");
        assert!(script.contains(r#"msg.textContent = "bad \"sig\" <script>\nline2";"#));
        assert!(script.contains(r#"setAttribute("role", "alert")"#));
        assert!(!script.contains("innerHTML"));
    }

    #[test]
    fn update_state_tracks_availability_and_serializes_camel_case() {
        let state = UpdateState::default();

        let disabled = state.snapshot(false, "1.3.1", true, Some("ignored".to_owned()));
        assert_eq!(disabled, UpdateStatus::disabled("1.3.1"));

        let initial = state.snapshot(true, "1.3.1", false, None);
        assert!(initial.enabled);
        assert_eq!(initial.current_version, "1.3.1");
        assert_eq!(initial.available_version, None);
        assert!(!initial.checked);
        assert!(!initial.installing);
        assert_eq!(initial.error, None);

        state.record_available("1.4.0");
        let offered = state.snapshot(true, "1.3.1", false, None);
        assert_eq!(offered.available_version.as_deref(), Some("1.4.0"));
        assert!(offered.checked);

        let json = serde_json::to_value(&offered).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "enabled": true,
                "currentVersion": "1.3.1",
                "availableVersion": "1.4.0",
                "checked": true,
                "installing": false,
                "error": null
            })
        );

        assert!(state.record_up_to_date());
        assert!(!state.record_up_to_date());
        let up_to_date = state.snapshot(true, "1.3.1", false, None);
        assert_eq!(up_to_date.available_version, None);
        assert!(up_to_date.checked);
    }

    #[test]
    fn capabilities_isolate_restore_backup_from_the_backend_updater_bridge() {
        let error_cap: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/error-page.json")).unwrap();
        assert!(error_cap.get("remote").is_none());
        assert_eq!(error_cap["windows"], serde_json::json!(["main"]));
        assert_eq!(
            error_cap["permissions"],
            serde_json::json!(["allow-restore-backup"])
        );

        let updater_cap: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/desktop-updater.json")).unwrap();
        assert_eq!(updater_cap["local"], false);
        assert_eq!(updater_cap["windows"], serde_json::json!(["main"]));
        assert_eq!(
            updater_cap["remote"]["urls"],
            serde_json::json!(["http://127.0.0.1:*"])
        );
        let perms: Vec<&str> = updater_cap["permissions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap())
            .collect();
        assert!(perms.contains(&"allow-get-desktop-update-status"));
        assert!(perms.contains(&"allow-check-desktop-update"));
        assert!(perms.contains(&"allow-install-desktop-update"));
        assert!(perms.contains(&"core:event:allow-listen"));
        assert!(perms.contains(&"core:event:allow-unlisten"));
        assert!(!perms.contains(&"allow-restore-backup"));
    }
}
