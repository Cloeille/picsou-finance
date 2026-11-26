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
use tokio::sync::mpsc;

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
enum TrayState {
    UpToDate,
    Offer(String),
    Installing,
    InstallFailed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct RenderSnapshot {
    status: UpdateStatus,
    tray: TrayState,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Transition {
    status: UpdateStatus,
    notify: bool,
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
    install_failed: bool,
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

    fn tray_state(&self) -> TrayState {
        if self.installing {
            TrayState::Installing
        } else if self.install_failed {
            TrayState::InstallFailed
        } else if let Some(version) = &self.available_version {
            TrayState::Offer(version.clone())
        } else {
            TrayState::UpToDate
        }
    }

    fn render_snapshot(&self, enabled: bool, current_version: &str) -> RenderSnapshot {
        RenderSnapshot {
            status: self.snapshot(enabled, current_version, None),
            tray: if enabled {
                self.tray_state()
            } else {
                TrayState::UpToDate
            },
        }
    }

    fn apply_offer(&mut self, enabled: bool, current_version: &str, version: &str) -> Transition {
        if !enabled {
            return Transition {
                status: UpdateStatus::disabled(current_version),
                notify: false,
            };
        }
        if self.installing {
            return Transition {
                status: self.snapshot(true, current_version, None),
                notify: false,
            };
        }
        self.available_version = Some(version.to_owned());
        self.checked = true;
        self.install_failed = false;
        Transition {
            status: self.snapshot(true, current_version, None),
            notify: true,
        }
    }

    fn apply_up_to_date(&mut self, enabled: bool, current_version: &str) -> Transition {
        if !enabled {
            return Transition {
                status: UpdateStatus::disabled(current_version),
                notify: false,
            };
        }
        if self.installing {
            return Transition {
                status: self.snapshot(true, current_version, None),
                notify: false,
            };
        }
        self.available_version = None;
        self.checked = true;
        self.install_failed = false;
        Transition {
            status: self.snapshot(true, current_version, None),
            notify: true,
        }
    }

    fn apply_check_error(&self, enabled: bool, current_version: &str, error: String) -> Transition {
        if !enabled {
            return Transition {
                status: UpdateStatus::disabled(current_version),
                notify: false,
            };
        }
        if self.installing {
            return Transition {
                status: self.snapshot(true, current_version, None),
                notify: false,
            };
        }
        Transition {
            status: self.snapshot(true, current_version, Some(error)),
            notify: false,
        }
    }

    fn try_begin_install(&mut self, enabled: bool, current_version: &str) -> Option<Transition> {
        if !enabled || self.installing {
            return None;
        }
        self.installing = true;
        self.install_failed = false;
        Some(Transition {
            status: self.snapshot(true, current_version, None),
            notify: true,
        })
    }

    fn finish_install(
        &mut self,
        enabled: bool,
        current_version: &str,
        outcome: &Result<InstallOutcome, String>,
    ) -> Transition {
        self.installing = false;
        if !enabled {
            self.install_failed = false;
            return Transition {
                status: UpdateStatus::disabled(current_version),
                notify: false,
            };
        }
        match outcome {
            Ok(InstallOutcome::NoUpdate) => {
                self.available_version = None;
                self.checked = true;
                self.install_failed = false;
                Transition {
                    status: self.snapshot(true, current_version, None),
                    notify: true,
                }
            }
            Ok(InstallOutcome::Started) => {
                self.install_failed = false;
                Transition {
                    status: self.snapshot(true, current_version, None),
                    notify: false,
                }
            }
            Err(_) => {
                // `show_update_error_toast` surfaces the localized install failure toast;
                // emit `error: None` with `installing: false` so the UI re-enables retrying
                // without showing a misleading check-failed banner.
                self.install_failed = true;
                Transition {
                    status: self.snapshot(true, current_version, None),
                    notify: true,
                }
            }
        }
    }
}

/// Serializes updater state transitions behind a short mutex (never held across I/O,
/// tray updates, or event emission) and signals a single serialized renderer over a
/// non-blocking channel.
pub struct UpdateState {
    machine: Arc<Mutex<UpdaterMachine>>,
    render_tx: mpsc::UnboundedSender<()>,
    render_rx: Mutex<Option<mpsc::UnboundedReceiver<()>>>,
}

impl Default for UpdateState {
    fn default() -> Self {
        let (render_tx, render_rx) = mpsc::unbounded_channel();
        Self {
            machine: Arc::new(Mutex::new(UpdaterMachine::default())),
            render_tx,
            render_rx: Mutex::new(Some(render_rx)),
        }
    }
}

impl UpdateState {
    fn snapshot(
        &self,
        enabled: bool,
        current_version: &str,
        error: Option<String>,
    ) -> UpdateStatus {
        let machine = self.machine.lock().unwrap_or_else(|e| e.into_inner());
        machine.snapshot(enabled, current_version, error)
    }

    fn notify_render(&self) {
        let _ = self.render_tx.send(());
    }

    fn take_render_rx(&self) -> Option<mpsc::UnboundedReceiver<()>> {
        self.render_rx
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
    }

    fn arm_guard(&self) -> InstallGuard {
        InstallGuard {
            state: Arc::clone(&self.machine),
            render_tx: self.render_tx.clone(),
            armed: true,
        }
    }

    fn offer(&self, enabled: bool, current_version: &str, version: &str) -> UpdateStatus {
        let transition = {
            let mut machine = self.machine.lock().unwrap_or_else(|e| e.into_inner());
            machine.apply_offer(enabled, current_version, version)
        };
        if transition.notify {
            self.notify_render();
        }
        transition.status
    }

    fn mark_up_to_date(&self, enabled: bool, current_version: &str) -> UpdateStatus {
        let transition = {
            let mut machine = self.machine.lock().unwrap_or_else(|e| e.into_inner());
            machine.apply_up_to_date(enabled, current_version)
        };
        if transition.notify {
            self.notify_render();
        }
        transition.status
    }

    fn record_check_error(
        &self,
        enabled: bool,
        current_version: &str,
        error: String,
    ) -> UpdateStatus {
        let machine = self.machine.lock().unwrap_or_else(|e| e.into_inner());
        machine
            .apply_check_error(enabled, current_version, error)
            .status
    }

    #[cfg(test)]
    fn is_installing(&self) -> bool {
        self.machine
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .installing
    }
}

/// The tray entry, disabled until an update is found.
#[derive(Default)]
pub struct TrayItem(pub Mutex<Option<MenuItem<tauri::Wry>>>);

/// Releases `installing` if an install task is cancelled or panics before `finish_guarded_install`
/// transitions the state normally.
struct InstallGuard {
    state: Arc<Mutex<UpdaterMachine>>,
    render_tx: mpsc::UnboundedSender<()>,
    armed: bool,
}

impl Drop for InstallGuard {
    fn drop(&mut self) {
        if self.armed {
            {
                let mut machine = self.state.lock().unwrap_or_else(|e| e.into_inner());
                machine.installing = false;
            }
            let _ = self.render_tx.send(());
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
    let (transition, guard) = {
        let mut machine = state.machine.lock().unwrap_or_else(|e| e.into_inner());
        let transition = machine.try_begin_install(enabled, current_version)?;
        let guard = state.arm_guard();
        (transition, guard)
    };
    if transition.notify {
        on_transition(&transition);
    }
    Some(guard)
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
    let transition = {
        let mut machine = guard.state.lock().unwrap_or_else(|e| e.into_inner());
        let transition = machine.finish_install(enabled, current_version, &outcome);
        guard.armed = false;
        transition
    };
    drop(guard);
    if transition.notify {
        on_transition(&transition);
    }
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
    let item = app
        .state::<TrayItem>()
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    if let Some(item) = item {
        let _ = item.set_text(text);
        let _ = item.set_enabled(enabled);
    }
}

fn apply_render_snapshot(app: &AppHandle, lang: crate::i18n::Lang, snapshot: &RenderSnapshot) {
    if !snapshot.status.enabled {
        return;
    }
    match &snapshot.tray {
        TrayState::Offer(version) => {
            let text = crate::i18n::tray_install(lang, version);
            set_tray(app, &text, true);
        }
        TrayState::UpToDate => {
            set_tray(app, crate::i18n::tray_up_to_date(lang), false);
        }
        TrayState::Installing => {
            set_tray(app, crate::i18n::tray_installing(lang), false);
        }
        TrayState::InstallFailed => {
            set_tray(app, crate::i18n::tray_install_failed(lang), true);
        }
    }
    emit_status(app, &snapshot.status);
}

async fn run_render_loop<F, Fut>(
    state: Arc<Mutex<UpdaterMachine>>,
    mut rx: mpsc::UnboundedReceiver<()>,
    enabled: bool,
    current_version: &'static str,
    mut render: F,
) where
    F: FnMut(RenderSnapshot) -> Fut,
    Fut: std::future::Future<Output = ()>,
{
    while rx.recv().await.is_some() {
        // Coalesce burst signals into one idempotent render of the latest state.
        while rx.try_recv().is_ok() {}
        let snapshot = {
            let machine = state.lock().unwrap_or_else(|e| e.into_inner());
            machine.render_snapshot(enabled, current_version)
        };
        render(snapshot).await;
    }
}

async fn run_blocking_render<F, E>(render: F, on_error: E)
where
    F: FnOnce() + Send + 'static,
    E: FnOnce(String),
{
    if let Err(err) = tauri::async_runtime::spawn_blocking(render).await {
        on_error(format!("update renderer task failed: {err}"));
    }
}

/// Starts the single serialized task that mirrors `UpdateState` snapshots to the tray menu
/// and the main window without holding any mutex during side effects.
pub fn start_renderer(app: &AppHandle) {
    let state = app.state::<UpdateState>();
    let Some(rx) = state.take_render_rx() else {
        return;
    };
    let machine = Arc::clone(&state.machine);
    let is_enabled = enabled(app.config());
    let lang = crate::i18n::Lang::system();
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        run_render_loop(
            machine,
            rx,
            is_enabled,
            supervisor::APP_VERSION,
            |snapshot| {
                let app = app.clone();
                async move {
                    let render_app = app.clone();
                    run_blocking_render(
                        move || apply_render_snapshot(&render_app, lang, &snapshot),
                        |line| note(&app, &line),
                    )
                    .await;
                }
            },
        )
        .await;
    });
}

fn offer(app: &AppHandle, version: &str) -> UpdateStatus {
    app.state::<UpdateState>()
        .offer(enabled(app.config()), supervisor::APP_VERSION, version)
}

fn mark_up_to_date(app: &AppHandle) -> UpdateStatus {
    app.state::<UpdateState>()
        .mark_up_to_date(enabled(app.config()), supervisor::APP_VERSION)
}

fn record_check_error(app: &AppHandle, err: String) -> UpdateStatus {
    app.state::<UpdateState>().record_check_error(
        enabled(app.config()),
        supervisor::APP_VERSION,
        err,
    )
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
  box.style.cssText = "position:fixed;right:1rem;bottom:1rem;z-index:2147483647;max-width:24rem;padding:0.875rem 1rem;border-radius:0.5rem;display:flex;gap:0.75rem;align-items:flex-start;font:0.875rem/1.45 \"Plus Jakarta Sans Variable\",system-ui,-apple-system,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,0.14);border:1px solid " + (dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.12)") + ";background:" + (dark ? "#171717" : "#ffffff") + ";color:" + (dark ? "#fafafa" : "#0a0a0a") + ";";
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
    let Some(guard) = begin_install(&state, is_enabled, supervisor::APP_VERSION, |_| {
        state.notify_render();
    }) else {
        if is_enabled {
            note(app, "install already in progress, ignoring");
        }
        return;
    };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let render_tx = app.state::<UpdateState>().render_tx.clone();
        finish_guarded_install(
            guard,
            is_enabled,
            supervisor::APP_VERSION,
            install_now(&app),
            move |_| {
                let _ = render_tx.send(());
            },
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
        state.offer(true, "1.3.1", "1.4.0");
        let starts = Arc::new(AtomicUsize::new(0));
        let failures: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        let transitions: Arc<Mutex<Vec<RenderSnapshot>>> = Arc::new(Mutex::new(Vec::new()));
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
            let guard = begin_install(&s_state, true, "1.3.1", |_| {
                st1.fetch_add(1, Ordering::SeqCst);
                let snap = s_state
                    .machine
                    .lock()
                    .unwrap()
                    .render_snapshot(true, "1.3.1");
                tr1.lock().unwrap().push(snap);
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
                |_| {
                    let snap = s_state
                        .machine
                        .lock()
                        .unwrap()
                        .render_snapshot(true, "1.3.1");
                    tr1.lock().unwrap().push(snap);
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
            assert_eq!(recorded[0].tray, TrayState::Installing);
            assert!(recorded[0].status.installing);
            assert_eq!(recorded[1].tray, TrayState::InstallFailed);
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
        let mut no_update_snapshot = None;
        finish_guarded_install(
            retry,
            true,
            "1.3.1",
            async { Ok(InstallOutcome::NoUpdate) },
            |_| {
                no_update_snapshot =
                    Some(state.machine.lock().unwrap().render_snapshot(true, "1.3.1"));
            },
            |_| {},
        )
        .await;
        assert_eq!(starts.load(Ordering::SeqCst), 2);
        assert!(!state.is_installing());
        let final_snap = no_update_snapshot.expect("NoUpdate must notify renderer");
        assert_eq!(final_snap.tray, TrayState::UpToDate);
        assert_eq!(final_snap.status.available_version, None);
        assert!(final_snap.status.checked);
    }

    #[tokio::test]
    async fn lock_is_free_during_effects_and_renderer_avoids_deadlock_with_concurrent_install() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use tokio::sync::Notify;

        let state = Arc::new(UpdateState::default());
        let rx = state
            .take_render_rx()
            .expect("render receiver must be available once");
        assert!(
            state.take_render_rx().is_none(),
            "render receiver must only be taken once"
        );

        let rendered: Arc<Mutex<Vec<RenderSnapshot>>> = Arc::new(Mutex::new(Vec::new()));
        let first_render_entered = Arc::new(Notify::new());
        let release_first_render = Arc::new(Notify::new());
        let second_render_done = Arc::new(Notify::new());
        let third_render_done = Arc::new(Notify::new());
        let render_count = Arc::new(AtomicUsize::new(0));

        let r_machine = Arc::clone(&state.machine);
        let r_rendered = Arc::clone(&rendered);
        let r_entered = Arc::clone(&first_render_entered);
        let r_release = Arc::clone(&release_first_render);
        let r_second_done = Arc::clone(&second_render_done);
        let r_third_done = Arc::clone(&third_render_done);
        let r_count = Arc::clone(&render_count);

        let renderer = tokio::spawn(run_render_loop(
            Arc::clone(&state.machine),
            rx,
            true,
            "1.3.1",
            move |snapshot| {
                let m = Arc::clone(&r_machine);
                let out = Arc::clone(&r_rendered);
                let entered = Arc::clone(&r_entered);
                let release = Arc::clone(&r_release);
                let second_done = Arc::clone(&r_second_done);
                let third_done = Arc::clone(&r_third_done);
                let count = Arc::clone(&r_count);
                async move {
                    // The state mutex must be unlocked while the renderer executes side effects.
                    {
                        let machine_guard = m
                            .try_lock()
                            .expect("UpdateState mutex must be free during render");
                        let _ = machine_guard.snapshot(true, "1.3.1", None);
                    }

                    let idx = count.fetch_add(1, Ordering::SeqCst);
                    if idx == 0 {
                        // Simulate a slow main-thread `set_tray` call while holding no lock.
                        entered.notify_one();
                        release.notified().await;
                    }
                    out.lock().unwrap().push(snapshot);
                    if idx == 1 {
                        second_done.notify_one();
                    } else if idx == 2 {
                        third_done.notify_one();
                    }
                }
            },
        ));

        // 1. Trigger an offer and wait until the renderer is blocked mid-side-effect.
        let offered = state.offer(true, "1.3.1", "1.4.0");
        assert_eq!(offered.available_version.as_deref(), Some("1.4.0"));
        tokio::time::timeout(Duration::from_secs(2), first_render_entered.notified())
            .await
            .expect("renderer must start first render promptly");

        // 2. While the renderer is blocked (as if waiting on the main thread for `set_tray`),
        //    a concurrent `begin_install` (tray click on the main thread) must not deadlock,
        //    and its `on_transition` callback must also see `UpdateState` unlocked.
        //    Run the synchronous call on a blocking thread so a real deadlock cannot starve
        //    the `current_thread` test runtime and prevent `timeout` from firing.
        let install_state = Arc::clone(&state);
        let guard = tokio::time::timeout(
            Duration::from_secs(2),
            tokio::task::spawn_blocking(move || {
                begin_install(&install_state, true, "1.3.1", |_| {
                    assert!(
                        install_state.machine.try_lock().is_ok(),
                        "UpdateState mutex must be free during begin_install effect"
                    );
                    assert!(install_state.snapshot(true, "1.3.1", None).installing);
                    install_state.notify_render();
                })
            }),
        )
        .await
        .expect("begin_install must not deadlock while renderer is busy")
        .expect("begin_install task must not panic")
        .expect("install must start");

        // 3. Release the first render; the serialized renderer must then apply `Installing`.
        release_first_render.notify_one();
        tokio::time::timeout(Duration::from_secs(2), second_render_done.notified())
            .await
            .expect("renderer must render Installing snapshot");

        // 4. Finish the install; `finish_guarded_install`'s callback must also see the lock free.
        {
            let finish_state = Arc::clone(&state);
            tokio::time::timeout(
                Duration::from_secs(2),
                finish_guarded_install(
                    guard,
                    true,
                    "1.3.1",
                    async { Err("network error".to_owned()) },
                    |_| {
                        assert!(
                            finish_state.machine.try_lock().is_ok(),
                            "UpdateState mutex must be free during finish_guarded_install effect"
                        );
                        assert!(!finish_state.snapshot(true, "1.3.1", None).installing);
                        finish_state.notify_render();
                    },
                    |_| {},
                ),
            )
            .await
            .expect("finish_guarded_install must not deadlock");
        }

        tokio::time::timeout(Duration::from_secs(2), third_render_done.notified())
            .await
            .expect("renderer must render InstallFailed snapshot");

        {
            let snaps = rendered.lock().unwrap();
            assert_eq!(snaps.len(), 3);
            assert_eq!(snaps[0].tray, TrayState::Offer("1.4.0".to_owned()));
            assert!(!snaps[0].status.installing);
            assert_eq!(snaps[1].tray, TrayState::Installing);
            assert!(snaps[1].status.installing);
            assert_eq!(snaps[2].tray, TrayState::InstallFailed);
            assert!(!snaps[2].status.installing);
        }

        // Dropping the last sender lets the render loop exit cleanly.
        drop(state);
        tokio::time::timeout(Duration::from_secs(2), renderer)
            .await
            .expect("render loop must exit when senders drop")
            .unwrap();
    }

    #[test]
    fn panic_during_begin_install_transition_resets_installing() {
        let state = Arc::new(UpdateState::default());
        let mut rx = state
            .take_render_rx()
            .expect("render receiver must be available");
        state.offer(true, "1.3.1", "1.4.0");
        assert!(rx.try_recv().is_ok());

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _ = begin_install(&state, true, "1.3.1", |_| {
                panic!("simulated transition effect panic");
            });
        }));
        assert!(result.is_err(), "test closure must panic");
        assert!(
            !state.is_installing(),
            "InstallGuard must reset installing to false when begin_install effect panics"
        );
        assert!(
            rx.try_recv().is_ok(),
            "InstallGuard drop must notify renderer to recover UI and tray"
        );

        // Subsequent install must still be able to acquire the gate.
        let retry = begin_install(&state, true, "1.3.1", |_| {});
        assert!(retry.is_some());
        drop(retry);
        assert!(!state.is_installing());
    }

    #[test]
    fn try_begin_install_returns_none_when_disabled() {
        let mut machine = UpdaterMachine::default();
        assert!(machine.try_begin_install(false, "1.3.1").is_none());
        assert!(!machine.installing);

        let state = UpdateState::default();
        assert!(begin_install(&state, false, "1.3.1", |_| {}).is_none());
        assert!(!state.is_installing());
    }

    #[tokio::test]
    async fn run_render_loop_logs_blocking_render_join_error_and_continues() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use tokio::sync::Notify;

        let state = Arc::new(UpdateState::default());
        let rx = state
            .take_render_rx()
            .expect("render receiver must be available");
        let calls = Arc::new(AtomicUsize::new(0));
        let errors: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
        let rendered: Arc<Mutex<Vec<RenderSnapshot>>> = Arc::new(Mutex::new(Vec::new()));
        let error_logged = Arc::new(Notify::new());
        let second_rendered = Arc::new(Notify::new());

        let (c, e, r, e_notify, r_notify) = (
            Arc::clone(&calls),
            Arc::clone(&errors),
            Arc::clone(&rendered),
            Arc::clone(&error_logged),
            Arc::clone(&second_rendered),
        );
        let renderer = tokio::spawn(run_render_loop(
            Arc::clone(&state.machine),
            rx,
            true,
            "1.3.1",
            move |snapshot| {
                let calls = Arc::clone(&c);
                let errors = Arc::clone(&e);
                let rendered = Arc::clone(&r);
                let e_notify = Arc::clone(&e_notify);
                let r_notify = Arc::clone(&r_notify);
                async move {
                    let idx = calls.fetch_add(1, Ordering::SeqCst);
                    run_blocking_render(
                        move || {
                            if idx == 0 {
                                panic!("simulated render panic");
                            }
                            rendered.lock().unwrap().push(snapshot);
                            r_notify.notify_one();
                        },
                        move |line| {
                            errors.lock().unwrap().push(line);
                            e_notify.notify_one();
                        },
                    )
                    .await;
                }
            },
        ));

        state.offer(true, "1.3.1", "1.4.0");
        tokio::time::timeout(Duration::from_secs(2), error_logged.notified())
            .await
            .expect("blocking render panic must be logged");

        state.mark_up_to_date(true, "1.3.1");
        tokio::time::timeout(Duration::from_secs(2), second_rendered.notified())
            .await
            .expect("render loop must continue after a blocking render panic");

        {
            let logged = errors.lock().unwrap();
            assert_eq!(logged.len(), 1);
            assert!(
                logged[0].contains("update renderer task failed:"),
                "unexpected error log: {}",
                logged[0]
            );
        }
        {
            let snaps = rendered.lock().unwrap();
            assert_eq!(snaps.len(), 1);
            assert_eq!(snaps[0].tray, TrayState::UpToDate);
        }

        drop(state);
        tokio::time::timeout(Duration::from_secs(2), renderer)
            .await
            .expect("render loop must exit when senders drop")
            .unwrap();
    }

    #[test]
    fn update_failed_script_escapes_message_and_uses_text_content() {
        let script = update_failed_script("bad \"sig\" <script>\nline2");
        assert!(script.contains(r#"msg.textContent = "bad \"sig\" <script>\nline2";"#));
        assert!(script.contains(r#"setAttribute("role", "alert")"#));
        assert!(!script.contains("innerHTML"));
    }

    #[test]
    fn desktop_ui_and_toast_use_plus_jakarta_sans_font() {
        let script = update_failed_script("install error");
        assert!(script.contains(r#"\"Plus Jakarta Sans Variable\""#));
        assert!(!script.contains("Geist"));

        let shell_html = include_str!("../../ui/index.html");
        assert!(shell_html.contains(r#""Plus Jakarta Sans Variable""#));
        assert!(!shell_html.contains("Geist"));
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
        assert_eq!(machine.tray_state(), TrayState::UpToDate);

        let offered = machine.apply_offer(true, "1.3.1", "1.4.0");
        assert!(offered.notify);
        assert_eq!(offered.status.available_version.as_deref(), Some("1.4.0"));
        assert!(offered.status.checked);
        assert_eq!(
            machine.render_snapshot(true, "1.3.1").tray,
            TrayState::Offer("1.4.0".to_owned())
        );

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
        assert!(cleared.notify);
        assert_eq!(cleared.status.available_version, None);
        assert!(cleared.status.checked);
        assert_eq!(
            machine.render_snapshot(true, "1.3.1").tray,
            TrayState::UpToDate
        );

        let still_up_to_date = machine.apply_up_to_date(true, "1.3.1");
        assert!(still_up_to_date.notify);
        assert_eq!(still_up_to_date.status.available_version, None);
        assert!(still_up_to_date.status.checked);
        assert_eq!(
            machine.render_snapshot(true, "1.3.1").tray,
            TrayState::UpToDate
        );
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
        assert!(started.notify);
        assert_eq!(machine.tray_state(), TrayState::Installing);

        // Concurrent offer for another version must not overwrite available_version, tray, or notify.
        let offer_during_install = machine.apply_offer(true, "1.3.1", "1.5.0");
        assert!(!offer_during_install.notify);
        assert!(offer_during_install.status.installing);
        assert_eq!(
            offer_during_install.status.available_version.as_deref(),
            Some("1.4.0")
        );
        assert_eq!(offer_during_install.status.error, None);
        assert_eq!(machine.tray_state(), TrayState::Installing);

        // Concurrent Ok(None) check must not clear available_version, touch tray, or notify.
        let up_to_date_during_install = machine.apply_up_to_date(true, "1.3.1");
        assert!(!up_to_date_during_install.notify);
        assert!(up_to_date_during_install.status.installing);
        assert_eq!(
            up_to_date_during_install
                .status
                .available_version
                .as_deref(),
            Some("1.4.0")
        );
        assert_eq!(up_to_date_during_install.status.error, None);
        assert_eq!(machine.tray_state(), TrayState::Installing);

        // Concurrent check error must not surface an error, touch tray, or notify while installing.
        let err_during_install =
            machine.apply_check_error(true, "1.3.1", "network timeout".to_owned());
        assert!(!err_during_install.notify);
        assert!(err_during_install.status.installing);
        assert_eq!(
            err_during_install.status.available_version.as_deref(),
            Some("1.4.0")
        );
        assert_eq!(err_during_install.status.error, None);
        assert_eq!(machine.tray_state(), TrayState::Installing);

        // Internal state remained completely untouched during the install.
        let snap = machine.snapshot(true, "1.3.1", Some("ignored".to_owned()));
        assert!(snap.installing);
        assert_eq!(snap.available_version.as_deref(), Some("1.4.0"));
        assert!(snap.checked);
        assert_eq!(snap.error, None);

        // Once the install finishes with an error, check errors can surface normally again.
        let failed = machine.finish_install(true, "1.3.1", &Err("boom".to_owned()));
        assert!(!failed.status.installing);
        assert!(failed.notify);
        assert_eq!(machine.tray_state(), TrayState::InstallFailed);
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
