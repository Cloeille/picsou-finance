//! Updates from the release's `latest.json` (#591), signed with the maintainer's updater key.
//!
//! Only a build given an updater config (`plugins.updater`: endpoint and public key, passed by
//! the release workflow) checks for updates; a dev or PR build never does. Picsou checks once
//! the backend is ready, then every 6 h, and offers the update in the tray. Installing stops the
//! embedded SQLite backend cleanly first; the new version's first start then copies the data
//! before migrating it, like any update (#588).

use std::sync::{Arc, Mutex};
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

#[derive(Debug, Clone, PartialEq, Eq)]
enum TrayAction {
    Offer(String),
    UpToDate,
    Installing,
    InstallFailed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Transition {
    status: UpdateStatus,
    tray: Option<TrayAction>,
    emit: bool,
}

#[derive(Debug, PartialEq, Eq)]
enum InstallOutcome {
    NoUpdate,
    Started,
}

#[derive(Default, Debug, Clone, PartialEq, Eq)]
struct UpdaterMachine {
    available_version: Option<String>,
    checked: bool,
    installing: bool,
}

impl UpdaterMachine {
    fn snapshot(
        &self,
        enabled: bool,
        current_version: &str,
        error: Option<String>,
    ) -> UpdateStatus {
        if !enabled {
            return UpdateStatus::disabled(current_version);
        }
        UpdateStatus {
            enabled: true,
            current_version: current_version.to_owned(),
            available_version: self.available_version.clone(),
            checked: self.checked,
            installing: self.installing,
            error: if self.installing { None } else { error },
        }
    }

    fn apply_offer(&mut self, enabled: bool, current_version: &str, version: &str) -> Transition {
        if !enabled {
            return Transition {
                status: UpdateStatus::disabled(current_version),
                tray: None,
                emit: false,
            };
        }
        if self.installing {
            return Transition {
                status: self.snapshot(true, current_version, None),
                tray: None,
                emit: false,
            };
        }
        self.available_version = Some(version.to_owned());
        self.checked = true;
        Transition {
            status: self.snapshot(true, current_version, None),
            tray: Some(TrayAction::Offer(version.to_owned())),
            emit: true,
        }
    }

    fn apply_up_to_date(&mut self, enabled: bool, current_version: &str) -> Transition {
        if !enabled {
            return Transition {
                status: UpdateStatus::disabled(current_version),
                tray: None,
                emit: false,
            };
        }
        if self.installing {
            return Transition {
                status: self.snapshot(true, current_version, None),
                tray: None,
                emit: false,
            };
        }
        let had_offer = self.available_version.take().is_some();
        self.checked = true;
        Transition {
            status: self.snapshot(true, current_version, None),
            tray: had_offer.then_some(TrayAction::UpToDate),
            emit: true,
        }
    }

    fn apply_check_error(&self, enabled: bool, current_version: &str, error: String) -> Transition {
        if !enabled {
            return Transition {
                status: UpdateStatus::disabled(current_version),
                tray: None,
                emit: false,
            };
        }
        if self.installing {
            return Transition {
                status: self.snapshot(true, current_version, None),
                tray: None,
                emit: false,
            };
        }
        Transition {
            status: self.snapshot(true, current_version, Some(error)),
            tray: None,
            emit: false,
        }
    }

    fn try_begin_install(&mut self, enabled: bool, current_version: &str) -> Option<Transition> {
        if self.installing {
            return None;
        }
        self.installing = true;
        Some(Transition {
            status: self.snapshot(enabled, current_version, None),
            tray: Some(TrayAction::Installing),
            emit: enabled,
        })
    }

    fn finish_install(
        &mut self,
        enabled: bool,
        current_version: &str,
        outcome: &Result<InstallOutcome, String>,
    ) -> Transition {
        self.installing = false;
        match outcome {
            Ok(InstallOutcome::NoUpdate) => {
                self.available_version = None;
                self.checked = true;
                Transition {
                    status: self.snapshot(enabled, current_version, None),
                    tray: Some(TrayAction::UpToDate),
                    emit: enabled,
                }
            }
            Ok(InstallOutcome::Started) => Transition {
                status: self.snapshot(enabled, current_version, None),
                tray: None,
                emit: false,
            },
            Err(_) => Transition {
                // `show_update_error_toast` surfaces the localized install failure toast;
                // emit `error: None` with `installing: false` so the UI re-enables retrying
                // without showing a misleading check-failed banner.
                status: self.snapshot(enabled, current_version, None),
                tray: Some(TrayAction::InstallFailed),
                emit: enabled,
            },
        }
    }
}

/// Serializes updater availability, the active-install flag, tray updates, and status events
/// behind a single short mutex (never held across network I/O).
#[derive(Default)]
pub struct UpdateState(Arc<Mutex<UpdaterMachine>>);

impl UpdateState {
    fn snapshot(
        &self,
        enabled: bool,
        current_version: &str,
        error: Option<String>,
    ) -> UpdateStatus {
        let machine = self.0.lock().unwrap_or_else(|e| e.into_inner());
        machine.snapshot(enabled, current_version, error)
    }

    #[cfg(test)]
    fn is_installing(&self) -> bool {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).installing
    }
}

/// The tray entry, disabled until an update is found.
#[derive(Default)]
pub struct TrayItem(pub Mutex<Option<MenuItem<tauri::Wry>>>);

/// Releases `installing` if an install task is cancelled or panics before `finish_guarded_install`
/// transitions the state normally.
struct InstallGuard {
    state: Arc<Mutex<UpdaterMachine>>,
    armed: bool,
}

impl Drop for InstallGuard {
    fn drop(&mut self) {
        if self.armed {
            let mut machine = self.state.lock().unwrap_or_else(|e| e.into_inner());
            machine.installing = false;
        }
    }
}

fn begin_install<E>(
    state: &UpdateState,
    enabled: bool,
    current_version: &str,
    on_transition: E,
) -> Option<InstallGuard>
where
    E: FnOnce(&Transition),
{
    let mut machine = state.0.lock().unwrap_or_else(|e| e.into_inner());
    let transition = machine.try_begin_install(enabled, current_version)?;
    on_transition(&transition);
    drop(machine);
    Some(InstallGuard {
        state: Arc::clone(&state.0),
        armed: true,
    })
}

async fn finish_guarded_install<F, E, H>(
    mut guard: InstallGuard,
    enabled: bool,
    current_version: &str,
    work: F,
    on_transition: E,
    on_outcome: H,
) where
    F: std::future::Future<Output = Result<InstallOutcome, String>>,
    E: FnOnce(&Transition),
    H: FnOnce(Result<InstallOutcome, String>),
{
    let outcome = work.await;
    {
        let mut machine = guard.state.lock().unwrap_or_else(|e| e.into_inner());
        let transition = machine.finish_install(enabled, current_version, &outcome);
        guard.armed = false;
        on_transition(&transition);
    }
    drop(guard);
    on_outcome(outcome);
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
                        let _ = offer(&app, &update.version);
                    }
                    Ok(None) => {
                        let _ = mark_up_to_date(&app);
                    }
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

fn apply_transition(app: &AppHandle, lang: crate::i18n::Lang, transition: &Transition) {
    if let Some(tray) = &transition.tray {
        match tray {
            TrayAction::Offer(version) => {
                let text = crate::i18n::tray_install(lang, version);
                set_tray(app, &text, true);
            }
            TrayAction::UpToDate => {
                set_tray(app, crate::i18n::tray_up_to_date(lang), false);
            }
            TrayAction::Installing => {
                set_tray(app, crate::i18n::tray_installing(lang), false);
            }
            TrayAction::InstallFailed => {
                set_tray(app, crate::i18n::tray_install_failed(lang), true);
            }
        }
    }
    if transition.emit {
        emit_status(app, &transition.status);
    }
}

fn offer(app: &AppHandle, version: &str) -> UpdateStatus {
    let lang = crate::i18n::Lang::system();
    let is_enabled = enabled(app.config());
    let state = app.state::<UpdateState>();
    let mut machine = state.0.lock().unwrap_or_else(|e| e.into_inner());
    let transition = machine.apply_offer(is_enabled, supervisor::APP_VERSION, version);
    apply_transition(app, lang, &transition);
    transition.status
}

fn mark_up_to_date(app: &AppHandle) -> UpdateStatus {
    let lang = crate::i18n::Lang::system();
    let is_enabled = enabled(app.config());
    let state = app.state::<UpdateState>();
    let mut machine = state.0.lock().unwrap_or_else(|e| e.into_inner());
    let transition = machine.apply_up_to_date(is_enabled, supervisor::APP_VERSION);
    apply_transition(app, lang, &transition);
    transition.status
}

fn record_check_error(app: &AppHandle, err: String) -> UpdateStatus {
    let is_enabled = enabled(app.config());
    let state = app.state::<UpdateState>();
    let machine = state.0.lock().unwrap_or_else(|e| e.into_inner());
    let transition = machine.apply_check_error(is_enabled, supervisor::APP_VERSION, err);
    transition.status
}

/// Returns the cached desktop updater status without issuing a network request.
#[tauri::command]
pub fn get_desktop_update_status(app: AppHandle) -> UpdateStatus {
    app.state::<UpdateState>()
        .snapshot(enabled(app.config()), supervisor::APP_VERSION, None)
}

/// Checks for a desktop update now and returns the resulting status.
#[tauri::command]
pub async fn check_desktop_update(app: AppHandle) -> UpdateStatus {
    if !enabled(app.config()) {
        return UpdateStatus::disabled(supervisor::APP_VERSION);
    }
    let current = app
        .state::<UpdateState>()
        .snapshot(true, supervisor::APP_VERSION, None);
    if current.installing {
        return current;
    }
    match app.updater().map_err(|e| e.to_string()) {
        Ok(updater) => match updater.check().await {
            Ok(Some(update)) => {
                note(&app, &format!("update {} available", update.version));
                offer(&app, &update.version)
            }
            Ok(None) => {
                note(&app, "update check: up to date");
                mark_up_to_date(&app)
            }
            Err(e) => {
                let err = e.to_string();
                note(&app, &format!("update check failed: {err}"));
                record_check_error(&app, err)
            }
        },
        Err(e) => {
            note(&app, &format!("no updater: {e}"));
            record_check_error(&app, e)
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

fn show_update_error_toast(app: &AppHandle, lang: crate::i18n::Lang, reason: &str) {
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
    let is_enabled = enabled(app.config());
    let state = app.state::<UpdateState>();
    // Acquire synchronously so two rapid tray or UI clicks cannot both spawn an install.
    let Some(guard) = begin_install(&state, is_enabled, supervisor::APP_VERSION, |transition| {
        apply_transition(app, lang, transition);
    }) else {
        note(app, "install already in progress, ignoring");
        return;
    };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        finish_guarded_install(
            guard,
            is_enabled,
            supervisor::APP_VERSION,
            install_now(&app),
            |transition| apply_transition(&app, lang, transition),
            |outcome| {
                if let Err(e) = outcome {
                    note(&app, &format!("update failed: {e}"));
                    show_update_error_toast(&app, lang, &e);
                }
            },
        )
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
        use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
        use tokio::sync::Notify;

        let state = Arc::new(UpdateState::default());
        {
            let mut machine = state.0.lock().unwrap();
            machine.apply_offer(true, "1.3.1", "1.4.0");
        }
        let starts = Arc::new(AtomicUsize::new(0));
        let failures: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        let transitions: Arc<Mutex<Vec<Transition>>> = Arc::new(Mutex::new(Vec::new()));
        let guard_dropped_before_outcome = Arc::new(AtomicBool::new(false));
        let started = Arc::new(Notify::new());
        let release = Arc::new(Notify::new());

        let (s_state, st1, f1, tr1, dropped1, s1, rel1) = (
            Arc::clone(&state),
            Arc::clone(&starts),
            Arc::clone(&failures),
            Arc::clone(&transitions),
            Arc::clone(&guard_dropped_before_outcome),
            Arc::clone(&started),
            Arc::clone(&release),
        );
        let first = tokio::spawn(async move {
            let guard = begin_install(&s_state, true, "1.3.1", |transition| {
                st1.fetch_add(1, Ordering::SeqCst);
                tr1.lock().unwrap().push(transition.clone());
            })
            .expect("first install must acquire gate");
            finish_guarded_install(
                guard,
                true,
                "1.3.1",
                async move {
                    s1.notify_one();
                    rel1.notified().await;
                    Err("network timeout".to_owned())
                },
                |transition| {
                    tr1.lock().unwrap().push(transition.clone());
                },
                |outcome| {
                    // Guard and mutex must already be released before notifying `on_outcome`,
                    // so an immediate retry triggered by the failure notification can proceed.
                    dropped1.store(!s_state.is_installing(), Ordering::SeqCst);
                    if let Err(err) = outcome {
                        f1.lock().unwrap().push(err);
                    }
                },
            )
            .await;
        });

        started.notified().await;
        assert!(state.is_installing());

        // Second click while the first install is still in flight must be ignored.
        let second = begin_install(&state, true, "1.3.1", |_| {
            starts.fetch_add(1, Ordering::SeqCst);
        });
        assert!(
            second.is_none(),
            "second concurrent install must be ignored"
        );
        assert_eq!(starts.load(Ordering::SeqCst), 1);

        release.notify_one();
        first.await.unwrap();
        assert!(!state.is_installing());
        assert!(
            guard_dropped_before_outcome.load(Ordering::SeqCst),
            "install guard must be released before on_outcome runs"
        );
        assert_eq!(
            *failures.lock().unwrap(),
            vec!["network timeout".to_owned()],
            "install failure must be surfaced"
        );
        {
            let recorded = transitions.lock().unwrap();
            assert_eq!(recorded.len(), 2);
            assert_eq!(recorded[0].tray, Some(TrayAction::Installing));
            assert!(recorded[0].emit);
            assert!(recorded[0].status.installing);
            assert_eq!(recorded[1].tray, Some(TrayAction::InstallFailed));
            assert!(recorded[1].emit);
            assert!(!recorded[1].status.installing);
            assert_eq!(recorded[1].status.error, None);
            assert_eq!(
                recorded[1].status.available_version.as_deref(),
                Some("1.4.0")
            );
        }

        // Once the failed install finishes, a retry must be allowed.
        let retry = begin_install(&state, true, "1.3.1", |_| {
            starts.fetch_add(1, Ordering::SeqCst);
        })
        .expect("retry after failure must acquire gate");
        let mut no_update_transition = None;
        finish_guarded_install(
            retry,
            true,
            "1.3.1",
            async { Ok(InstallOutcome::NoUpdate) },
            |transition| {
                no_update_transition = Some(transition.clone());
            },
            |_| {},
        )
        .await;
        assert_eq!(starts.load(Ordering::SeqCst), 2);
        assert!(!state.is_installing());
        let final_t = no_update_transition.expect("NoUpdate must emit transition");
        assert_eq!(final_t.tray, Some(TrayAction::UpToDate));
        assert!(final_t.emit);
        assert_eq!(final_t.status.available_version, None);
        assert!(final_t.status.checked);
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
        let mut machine = UpdaterMachine::default();

        let disabled = machine.snapshot(false, "1.3.1", Some("ignored".to_owned()));
        assert_eq!(disabled, UpdateStatus::disabled("1.3.1"));

        let initial = machine.snapshot(true, "1.3.1", None);
        assert!(initial.enabled);
        assert_eq!(initial.current_version, "1.3.1");
        assert_eq!(initial.available_version, None);
        assert!(!initial.checked);
        assert!(!initial.installing);
        assert_eq!(initial.error, None);

        let offered = machine.apply_offer(true, "1.3.1", "1.4.0");
        assert_eq!(offered.tray, Some(TrayAction::Offer("1.4.0".to_owned())));
        assert!(offered.emit);
        assert_eq!(offered.status.available_version.as_deref(), Some("1.4.0"));
        assert!(offered.status.checked);

        let json = serde_json::to_value(&offered.status).unwrap();
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

        let cleared = machine.apply_up_to_date(true, "1.3.1");
        assert_eq!(cleared.tray, Some(TrayAction::UpToDate));
        assert!(cleared.emit);
        assert_eq!(cleared.status.available_version, None);
        assert!(cleared.status.checked);

        let still_up_to_date = machine.apply_up_to_date(true, "1.3.1");
        assert_eq!(still_up_to_date.tray, None);
        assert!(still_up_to_date.emit);
        assert_eq!(still_up_to_date.status.available_version, None);
        assert!(still_up_to_date.status.checked);
    }

    #[test]
    fn offer_up_to_date_and_check_error_do_not_alter_state_while_installing() {
        let mut machine = UpdaterMachine::default();
        let offered = machine.apply_offer(true, "1.3.1", "1.4.0");
        assert_eq!(offered.status.available_version.as_deref(), Some("1.4.0"));

        let started = machine
            .try_begin_install(true, "1.3.1")
            .expect("install should start");
        assert!(started.status.installing);
        assert_eq!(started.tray, Some(TrayAction::Installing));
        assert!(started.emit);

        // Concurrent offer for another version must not overwrite available_version, tray, or emit.
        let offer_during_install = machine.apply_offer(true, "1.3.1", "1.5.0");
        assert_eq!(offer_during_install.tray, None);
        assert!(!offer_during_install.emit);
        assert!(offer_during_install.status.installing);
        assert_eq!(
            offer_during_install.status.available_version.as_deref(),
            Some("1.4.0")
        );
        assert_eq!(offer_during_install.status.error, None);

        // Concurrent Ok(None) check must not clear available_version, touch tray, or emit.
        let up_to_date_during_install = machine.apply_up_to_date(true, "1.3.1");
        assert_eq!(up_to_date_during_install.tray, None);
        assert!(!up_to_date_during_install.emit);
        assert!(up_to_date_during_install.status.installing);
        assert_eq!(
            up_to_date_during_install
                .status
                .available_version
                .as_deref(),
            Some("1.4.0")
        );
        assert_eq!(up_to_date_during_install.status.error, None);

        // Concurrent check error must not surface an error, touch tray, or emit while installing.
        let err_during_install =
            machine.apply_check_error(true, "1.3.1", "network timeout".to_owned());
        assert_eq!(err_during_install.tray, None);
        assert!(!err_during_install.emit);
        assert!(err_during_install.status.installing);
        assert_eq!(
            err_during_install.status.available_version.as_deref(),
            Some("1.4.0")
        );
        assert_eq!(err_during_install.status.error, None);

        // Internal state remained completely untouched during the install.
        let snap = machine.snapshot(true, "1.3.1", Some("ignored".to_owned()));
        assert!(snap.installing);
        assert_eq!(snap.available_version.as_deref(), Some("1.4.0"));
        assert!(snap.checked);
        assert_eq!(snap.error, None);

        // Once the install finishes with an error, check errors can surface normally again.
        let failed = machine.finish_install(true, "1.3.1", &Err("boom".to_owned()));
        assert!(!failed.status.installing);
        assert_eq!(failed.tray, Some(TrayAction::InstallFailed));
        assert!(failed.emit);
        assert_eq!(failed.status.available_version.as_deref(), Some("1.4.0"));
        assert_eq!(failed.status.error, None);

        let err_after_install =
            machine.apply_check_error(true, "1.3.1", "network timeout".to_owned());
        assert_eq!(
            err_after_install.status.error.as_deref(),
            Some("network timeout")
        );
        assert!(!err_after_install.status.installing);
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
