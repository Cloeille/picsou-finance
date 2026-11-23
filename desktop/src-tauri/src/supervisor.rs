//! Starts the backend (`picsou`, linked in) backed by embedded SQLite (`<data>/picsou.db`) on
//! its own tokio runtime in this process, plus on-demand bank sidecars and the native WebView
//! bank bridge, and stops all of them cleanly on exit.
//!
//! The runtime directory holds what `desktop/scripts/fetch_runtime.py` assembled: `spa/`,
//! `python/`, and the remaining Chromium sidecars. The data directory is the OS app-data folder:
//! `picsou.db` (embedded SQLite), `secrets/` (0600 files), `backups/`, `logs/`.

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::{mpsc, Arc};
use std::thread::sleep;
use std::time::{Duration, Instant};

use picsou::clock::SystemClock;
use picsou::config::{Config, Env};
use tokio::sync::oneshot;

use crate::i18n::{self, Lang};
use crate::{sidecars, upgrade, webview_bank};

pub const LOOPBACK: &str = "127.0.0.1";
const STOP_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Clone)]
pub struct Layout {
    pub runtime: PathBuf,
    pub data: PathBuf,
}

impl Layout {
    pub fn database(&self) -> PathBuf {
        self.data.join("picsou.db")
    }
    pub fn legacy_postgres(&self) -> PathBuf {
        self.data.join("postgres")
    }
    fn secrets(&self) -> PathBuf {
        self.data.join("secrets")
    }
    pub fn log(&self, name: &str) -> std::io::Result<File> {
        fs::create_dir_all(self.data.join("logs"))?;
        OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.data.join("logs").join(name))
    }
}

/// The running backend, sidecars and WebView bridge, kept so `stop` can end them cleanly.
#[derive(Default)]
pub struct Stack {
    backend: Option<Backend>,
    /// Bank sidecars running right now, by service; `sidecars.rs` starts and stops them.
    pub sidecars: std::collections::HashMap<String, Child>,
    /// Native Tauri WebView bank bridge (BNP Paribas / Hello bank!, Amex, Revolut).
    webview_bridge: Option<webview_bank::WebviewBridge>,
    pub stopping: bool,
}

pub type Shared = std::sync::Arc<std::sync::Mutex<Stack>>;

/// Held by the tests that free a port and expect it back, and by those that start sidecars on
/// ports they just freed: run together, one could take the other's port.
#[cfg(test)]
pub static TEST_PORTS: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// The version this shell and its linked backend are built from.
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

/// A start that did not reach the dashboard. `can_restore` offers the copy taken before the update.
#[derive(Debug, Clone, PartialEq)]
pub struct Failed {
    pub message: String,
    pub can_restore: bool,
}

impl From<String> for Failed {
    fn from(message: String) -> Self {
        Failed {
            message,
            can_restore: false,
        }
    }
}

/// A child that dies with the app on Linux, even when the app is killed or crashes, so no
/// orphan sidecar lingers. The signal is tied to the spawning thread, so children must spawn
/// from a thread that lives as long as the app.
pub fn child(program: PathBuf, on_parent_death: Signal) -> Command {
    #[cfg(target_os = "macos")]
    let command = guarded(program, on_parent_death);
    #[cfg(not(target_os = "macos"))]
    let mut command = Command::new(program);
    #[cfg(target_os = "linux")]
    unsafe {
        use std::os::unix::process::CommandExt;
        let number = on_parent_death.number();
        command.pre_exec(move || {
            libc::prctl(libc::PR_SET_PDEATHSIG, number as libc::c_ulong);
            Ok(())
        });
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // No console window per child; the job object from `kill_children_with_the_app` ends them.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(windows)]
    let _ = on_parent_death;
    command
}

/// macOS has no parent-death signal: a shell starts a watcher, then `exec`s the program in its
/// place, so the child keeps the shell's PID. The watcher polls the app; once it is gone (quit,
/// crash or kill) it signals the program, and it exits by itself when the program does.
/// ponytail: a 2 s poll per child, and a PID reused within 2 s of the program's exit could be
/// signalled; kqueue's NOTE_EXIT in a helper would close both if it ever matters.
#[cfg(target_os = "macos")]
fn guarded(program: PathBuf, on_parent_death: Signal) -> Command {
    const WATCH: &str = r#"app=$1; sig=$2; shift 2; ( while kill -0 "$app" && kill -0 $$; do sleep 2; done; kill -0 "$app" || kill -"$sig" $$ ) </dev/null >/dev/null 2>&1 & exec "$@""#;
    let mut command = Command::new("/bin/sh");
    command
        .args(["-c", WATCH, "picsou"])
        .arg(std::process::id().to_string())
        .arg(on_parent_death.number().to_string())
        .arg(program);
    command
}

/// The job of `kill_children_with_the_app`, lifted by `let_the_installer_outlive_the_app`.
#[cfg(windows)]
static JOB: std::sync::atomic::AtomicPtr<std::ffi::c_void> =
    std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

/// Windows: puts Picsou in a job object that kills every process in it when the last handle
/// closes, which the system does when Picsou exits, crashes or is killed. Sidecar children
/// inherit the job, so none outlives the app.
#[cfg(windows)]
pub fn kill_children_with_the_app() {
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };
    use windows_sys::Win32::System::Threading::GetCurrentProcess;
    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            eprintln!(
                "picsou: no job object, sidecars may outlive a crash: {}",
                std::io::Error::last_os_error()
            );
            return;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let limited = SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const std::ffi::c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        );
        // The handle is never closed on purpose: it lives exactly as long as the process.
        if limited == 0 || AssignProcessToJobObject(job, GetCurrentProcess()) == 0 {
            eprintln!(
                "picsou: no job object, sidecars may outlive a crash: {}",
                std::io::Error::last_os_error()
            );
            return;
        }
        JOB.store(job, std::sync::atomic::Ordering::SeqCst);
    }
}

/// Windows, before the updater starts the installer and exits: the stack is stopped, so the job
/// no longer kills on close, and the installer starts outside it instead of dying with the app.
#[cfg(windows)]
pub fn let_the_installer_outlive_the_app() {
    use windows_sys::Win32::System::JobObjects::{
        JobObjectExtendedLimitInformation, SetInformationJobObject,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK,
    };
    let job = JOB.load(std::sync::atomic::Ordering::SeqCst);
    if job.is_null() {
        return;
    }
    unsafe {
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK;
        let set = SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const std::ffi::c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        );
        if set == 0 {
            eprintln!(
                "picsou: the installer may stop with the app: {}",
                std::io::Error::last_os_error()
            );
        }
    }
}

/// The backend, started and ready to serve: the window can load `url` now.
pub struct Ready {
    pub url: String,
    runtime: tokio::runtime::Runtime,
    state: picsou::AppState,
    listener: TcpListener,
    shutdown: oneshot::Receiver<()>,
    stopped: mpsc::Sender<()>,
}

impl Ready {
    /// Serves on the calling thread until `stop`, then tells `stop` the backend is gone.
    pub fn serve(self) {
        let Ready {
            runtime,
            state,
            listener,
            shutdown,
            stopped,
            ..
        } = self;
        let served = runtime.block_on(async move {
            listener.set_nonblocking(true)?;
            let listener = tokio::net::TcpListener::from_std(listener)?;
            picsou::run(state, listener, async {
                let _ = shutdown.await;
            })
            .await
        });
        if let Err(e) = served {
            tracing::error!("the backend stopped: {e:#}");
        }
        runtime.shutdown_timeout(Duration::from_secs(2));
        let _ = stopped.send(());
    }
}

/// The in-process backend as `stop` sees it: a signal to finish, and word that it has.
struct Backend {
    shutdown: oneshot::Sender<()>,
    stopped: mpsc::Receiver<()>,
}

/// True when the app-data folder holds a legacy embedded PostgreSQL directory from an older
/// Desktop release. The backend gates `legacyPostgresDetected` on setup not being complete, so
/// the onboarding notice persists across restarts until setup finishes.
pub fn has_legacy_postgres(layout: &Layout) -> bool {
    layout.legacy_postgres().is_dir()
}

/// Starts the embedded SQLite backend in this process and returns once it is ready.
pub fn start(
    layout: &Layout,
    stack: &Shared,
    app: Option<tauri::AppHandle>,
) -> Result<Ready, Failed> {
    let secrets = layout.secrets();
    private_dir(&layout.data)
        .and_then(|_| private_dir(&secrets))
        .map_err(|e| format!("data folder: {e}"))?;
    let db_path = layout.database();
    if let Some(parent) = db_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("database folder: {e}"))?;
    }
    let sidecar_key =
        secret(&secrets.join("sidecar_api_key")).map_err(|e| format!("sidecar key: {e}"))?;
    let fresh = !db_path.exists();
    if has_legacy_postgres(layout) {
        tracing::info!(
            "legacy PostgreSQL data folder detected at {}; \
             leaving the legacy postgres/ folder untouched and using the SQLite database at {}",
            layout.legacy_postgres().display(),
            db_path.display()
        );
    }
    let upgrade_from =
        match upgrade::compare(upgrade::recorded(&layout.data).as_deref(), APP_VERSION) {
            _ if fresh => None,
            upgrade::Before::Downgrade { from } => {
                return Err(i18n::newer_data(Lang::system(), Some(&from), APP_VERSION).into())
            }
            upgrade::Before::Upgrade { from } => {
                // The backend is not running yet, so copying picsou.db (+ -wal/-shm) is consistent.
                upgrade::backup(&layout.data, &from, APP_VERSION, now_secs())
                    .map_err(|e| i18n::backup_failed(Lang::system(), e))?;
                Some(from)
            }
            upgrade::Before::Nothing => None,
        };

    let listener = preferred_listener(&layout.data.join("desktop-port"))
        .map_err(|e| format!("no free port: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("no free port: {e}"))?
        .port();
    let desktop_token = ephemeral_token().map_err(|e| format!("desktop token: {e}"))?;
    // vars() panics on a variable that is not UTF-8: such a variable is skipped instead.
    let user_env: Vec<(String, String)> = std::env::vars_os()
        .filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?)))
        .collect();
    let mut env = merge_backend_env(user_env, layout, port, &desktop_token);
    env.extend(
        sidecars::serve(layout, stack, &sidecar_key, sidecars::IDLE)
            .map_err(|e| format!("sidecar ports: {e}"))?,
    );
    if let Some(app_handle) = app {
        let mut bridge = webview_bank::start(app_handle, sidecar_key.clone())
            .map_err(|e| format!("webview bank bridge: {e}"))?;
        let bridge_url = bridge.url().to_owned();
        let amex_url = bridge.amex_url();
        let revolut_url = bridge.revolut_url();
        {
            let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
            if s.stopping {
                drop(s);
                bridge.stop();
                return Err("Picsou is quitting".to_owned().into());
            }
            s.webview_bridge = Some(bridge);
        }
        env.push(("BNP_AUTH_URL".to_owned(), bridge_url));
        env.push(("AMEX_AUTH_URL".to_owned(), amex_url));
        env.push(("REVOLUT_AUTH_URL".to_owned(), revolut_url));
        env.push(("APP_SIDECAR_API_KEY".to_owned(), sidecar_key.clone()));
    }
    let config = Config::load(&Env::from_map(env))
        .map_err(|e| format!("the backend settings are invalid: {e}"))?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("picsou-backend")
        .build()
        .map_err(|e| format!("the backend did not start: {e}"))?;
    let state = runtime
        .block_on(picsou::start(config, Arc::new(SystemClock)))
        .map_err(|e| {
            tracing::error!("the backend did not start: {e:#}");
            let lang = Lang::system();
            explain(
                lang,
                i18n::did_not_start(lang),
                upgrade::classify(&e),
                upgrade_from.as_deref(),
            )
        })?;
    // Only a start that reached the dashboard moves the recorded version.
    upgrade::record(&layout.data, APP_VERSION).map_err(|e| format!("data folder: {e}"))?;
    let (shutdown_tx, shutdown) = oneshot::channel();
    let (stopped_tx, stopped) = mpsc::channel();
    {
        let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
        if s.stopping {
            return Err("Picsou is quitting".to_owned().into());
        }
        s.backend = Some(Backend {
            shutdown: shutdown_tx,
            stopped,
        });
    }
    // After readiness, so the download never slows the start; before any sync can need it.
    sidecars::prefetch_browser(layout, stack);
    Ok(Ready {
        url: format!("http://{LOOPBACK}:{port}/api/auth/desktop-bootstrap?token={desktop_token}"),
        runtime,
        state,
        listener,
        shutdown,
        stopped: stopped_tx,
    })
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

/// The message for a backend that did not start, from why its migrations failed, in `lang`.
pub fn explain(
    lang: Lang,
    stopped: &str,
    failure: Option<upgrade::Failure>,
    upgrade_from: Option<&str>,
) -> Failed {
    match (failure, upgrade_from) {
        (Some(upgrade::Failure::NewerData), _) => i18n::newer_data(lang, None, APP_VERSION).into(),
        (Some(upgrade::Failure::Migration(reason)), Some(from)) => Failed {
            message: i18n::update_failed_with_copy(lang, from, APP_VERSION, &reason),
            can_restore: true,
        },
        (Some(upgrade::Failure::Migration(reason)), None) => {
            i18n::data_update_failed(lang, &reason).into()
        }
        // A timeout or a crash mid-update may have touched the data too.
        (None, Some(from)) => Failed {
            message: i18n::stopped_during_update(lang, stopped, from),
            can_restore: true,
        },
        (None, None) => stopped.to_owned().into(),
    }
}

/// Stops everything and puts back the copy taken before the update to this version.
pub fn restore_backup(layout: &Layout, stack: &Shared) -> Result<(), String> {
    let copy = upgrade::latest_backup(&layout.data, APP_VERSION)
        .ok_or("no copy from before the update")?;
    stop(stack);
    upgrade::restore(&layout.data, &copy, now_secs()).map_err(|e| format!("restore failed: {e}"))
}

/// Formats a database file path as a `DATABASE_URL` accepted by `picsou::config::DatabaseConfig::parse`.
///
/// Forward-slashes Windows separators so `C:\Users\...\picsou.db` becomes `sqlite://C:/Users/.../picsou.db`
/// and Unix `/Users/.../picsou.db` becomes `sqlite:///Users/.../picsou.db`, while percent-encoding
/// URI-significant characters (`%`, `?`, `#`, and spaces) so special characters in OS user paths
/// never truncate or corrupt the path.
pub fn sqlite_url(path: &Path) -> String {
    let normalized = path.to_string_lossy().replace('\\', "/");
    let mut encoded = String::with_capacity(normalized.len());
    for ch in normalized.chars() {
        match ch {
            '%' => encoded.push_str("%25"),
            '?' => encoded.push_str("%3F"),
            '#' => encoded.push_str("%23"),
            ' ' => encoded.push_str("%20"),
            _ => encoded.push(ch),
        }
    }
    format!("sqlite://{encoded}")
}

/// Pure-HTTP bank sidecars compiled natively into `picsou` (`backend-rs/src/native_sidecars/`).
/// Desktop routes all 6 in-process by default so zero Python processes are needed for them; a
/// non-blank user environment variable still overrides any individual bank.
pub const NATIVE_SIDECARS: &[(&str, &str)] = &[
    ("DEGIRO_AUTH_URL", "native://degiro-auth"),
    ("CMES_AUTH_URL", "native://cmes-auth"),
    ("SOFIDY_AUTH_URL", "native://sofidy-auth"),
    ("CIC_AUTH_URL", "native://cic-auth"),
    ("NATIXIS_AUTH_URL", "native://natixis-auth"),
    ("BOURSO_AUTH_URL", "native://bourso-auth"),
];

/// The backend's environment: loopback only, embedded SQLite under the app-data folder, plain
/// HTTP cookies, per-launch desktop session token, and the 6 pure-HTTP bank connectors routed to
/// their native Rust implementations.
pub fn backend_env(layout: &Layout, port: u16, desktop_token: &str) -> Vec<(String, String)> {
    let path = |p: PathBuf| p.to_string_lossy().into_owned();
    let mut vars: Vec<(String, String)> = [
        ("SERVER_ADDRESS", LOOPBACK.to_owned()),
        ("SERVER_PORT", port.to_string()),
        ("DATABASE_URL", sqlite_url(&layout.database())),
        ("PICSOU_SECRETS_DIR", path(layout.secrets())),
        (
            "APP_CRYPTO_KEY_PATH",
            path(layout.secrets().join("crypto_key")),
        ),
        (
            "ENABLEBANKING_PRIVATE_KEY_PATH",
            path(layout.secrets().join("enablebanking-private.pem")),
        ),
        ("SPA_LOCATION", path(layout.runtime.join("spa"))),
        ("APP_VERSION", APP_VERSION.to_owned()),
        ("SECURE_COOKIES", "false".to_owned()),
        ("APP_STARTUP_SYNC_ENABLED", "false".to_owned()),
        ("APP_DESKTOP_MODE", "true".to_owned()),
        ("APP_DESKTOP_TOKEN", desktop_token.to_owned()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v))
    .collect();
    if has_legacy_postgres(layout) {
        vars.push(("APP_DESKTOP_LEGACY_POSTGRES".to_owned(), "true".to_owned()));
    }
    vars.extend(
        NATIVE_SIDECARS
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned())),
    );
    vars
}

/// Combines the caller's environment with `backend_env`, keeping any non-blank user override for
/// the native sidecar URL variables while never allowing user env to override desktop security or
/// storage controls.
pub fn merge_backend_env(
    user_env: Vec<(String, String)>,
    layout: &Layout,
    port: u16,
    desktop_token: &str,
) -> Vec<(String, String)> {
    let overrides: std::collections::HashSet<String> = user_env
        .iter()
        .filter(|(k, v)| !v.trim().is_empty() && NATIVE_SIDECARS.iter().any(|(nk, _)| nk == k))
        .map(|(k, _)| k.clone())
        .collect();
    let mut env: Vec<(String, String)> = user_env
        .into_iter()
        .filter(|(k, _)| k != "APP_DESKTOP_TOKEN" && k != "APP_DESKTOP_LEGACY_POSTGRES")
        .collect();
    for (k, v) in backend_env(layout, port, desktop_token) {
        if !overrides.contains(&k) {
            env.push((k, v));
        }
    }
    env
}

/// Stops the WebView bridge, any running sidecars, and the backend once its requests in flight
/// are answered so SQLite closes cleanly.
pub fn stop(stack: &Shared) {
    let (backend, running, webview_bridge) = {
        let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
        s.stopping = true;
        (
            s.backend.take(),
            std::mem::take(&mut s.sidecars),
            s.webview_bridge.take(),
        )
    };
    if let Some(mut bridge) = webview_bridge {
        bridge.stop();
    }
    // In parallel: each may take up to STOP_TIMEOUT to finish a request in flight.
    let ending: Vec<_> = running
        .into_values()
        .map(|s| std::thread::spawn(move || end(s, Signal::Terminate)))
        .collect();
    for handle in ending {
        let _ = handle.join();
    }
    if let Some(Backend { shutdown, stopped }) = backend {
        let _ = shutdown.send(());
        let _ = stopped.recv_timeout(STOP_TIMEOUT);
    }
}

/// A fresh 32-byte CSPRNG hex token kept in memory only (never persisted to disk).
pub fn ephemeral_token() -> std::io::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(std::io::Error::other)?;
    Ok(hex::encode(bytes))
}

/// A random hex secret, written 0600 on first use and read back afterwards.
pub fn secret(path: &Path) -> std::io::Result<String> {
    if let Ok(existing) = fs::read_to_string(path) {
        if !existing.trim().is_empty() {
            return Ok(existing.trim().to_owned());
        }
        // Empty after a crash between create and write: regenerate rather than use "".
        fs::remove_file(path)?;
    }
    let mut bytes = [0u8; 24];
    getrandom::fill(&mut bytes).map_err(std::io::Error::other)?;
    let value: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    options.open(path)?.write_all(value.as_bytes())?;
    Ok(value)
}

fn private_dir(path: &Path) -> std::io::Result<()> {
    fs::create_dir_all(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

pub fn free_port() -> std::io::Result<u16> {
    Ok(TcpListener::bind((LOOPBACK, 0))?.local_addr()?.port())
}

/// A listener on the port of the last start when it is still free, so the window keeps its
/// origin (and its local storage) across launches; on a new free port otherwise.
pub fn preferred_listener(file: &Path) -> std::io::Result<TcpListener> {
    let saved = fs::read_to_string(file)
        .ok()
        .and_then(|s| s.trim().parse::<u16>().ok());
    if let Some(listener) = saved.and_then(|p| TcpListener::bind((LOOPBACK, p)).ok()) {
        return Ok(listener);
    }
    let listener = TcpListener::bind((LOOPBACK, 0))?;
    fs::write(file, listener.local_addr()?.port().to_string())?;
    Ok(listener)
}

#[derive(Clone, Copy)]
pub enum Signal {
    Terminate,
}

impl Signal {
    #[cfg(unix)]
    fn number(self) -> libc::c_int {
        match self {
            Signal::Terminate => libc::SIGTERM,
        }
    }
}

pub fn end(mut child: Child, signal: Signal) {
    #[cfg(unix)]
    unsafe {
        libc::kill(child.id() as libc::pid_t, signal.number());
    }
    #[cfg(not(unix))]
    {
        // Sidecars on Windows keep no state: terminate them directly.
        let _ = signal;
        let _ = child.kill();
    }
    let deadline = Instant::now() + STOP_TIMEOUT;
    while Instant::now() < deadline {
        if let Ok(Some(_)) = child.try_wait() {
            return;
        }
        sleep(Duration::from_millis(100));
    }
    let _ = child.kill();
    let _ = child.wait();
}

#[cfg(test)]
mod tests {
    use super::*;
    use picsou::config::{DatabaseConfig, SqliteTarget};

    fn layout(root: &Path) -> Layout {
        Layout {
            runtime: root.join("runtime"),
            data: root.join("data"),
        }
    }

    fn scratch(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("picsou-desktop-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_failed_migration_after_an_update_offers_the_copy_and_newer_data_never_does() {
        let failed = explain(
            Lang::En,
            "The backend stopped",
            Some(upgrade::Failure::Migration("V150 failed".into())),
            Some("1.0.0"),
        );
        assert!(failed.can_restore);
        assert!(failed.message.contains("from 1.0.0 to") && failed.message.contains("V150 failed"));

        assert!(
            !explain(
                Lang::En,
                "x",
                Some(upgrade::Failure::Migration("V150 failed".into())),
                None
            )
            .can_restore
        );
        let newer = explain(
            Lang::En,
            "x",
            Some(upgrade::Failure::NewerData),
            Some("1.0.0"),
        );
        assert!(!newer.can_restore && newer.message.contains("was not changed"));
        assert!(explain(Lang::En, "The backend stopped.", None, Some("1.0.0")).can_restore);
        assert_eq!(
            explain(Lang::En, "The backend stopped", None, None),
            Failed::from("The backend stopped".to_owned())
        );
    }

    #[test]
    fn the_backend_binds_loopback_uses_sqlite_and_keeps_its_data_in_the_app_folder() {
        let root = PathBuf::from("/picsou");
        let env: std::collections::HashMap<_, _> =
            backend_env(&layout(&root), 8123, "launch-token-hex")
                .into_iter()
                .collect();

        assert_eq!(env["SERVER_ADDRESS"], "127.0.0.1");
        assert_eq!(env["SERVER_PORT"], "8123");
        assert_eq!(env["DATABASE_URL"], "sqlite:///picsou/data/picsou.db");
        assert!(!env.contains_key("SPRING_DATASOURCE_URL"));
        assert!(!env.contains_key("SPRING_DATASOURCE_USERNAME"));
        assert!(!env.contains_key("SPRING_DATASOURCE_PASSWORD"));
        assert_eq!(
            PathBuf::from(&env["PICSOU_SECRETS_DIR"]),
            root.join("data").join("secrets")
        );
        assert_eq!(
            PathBuf::from(&env["SPA_LOCATION"]),
            root.join("runtime").join("spa")
        );
        assert_eq!(env["SECURE_COOKIES"], "false");
        assert_eq!(env["APP_STARTUP_SYNC_ENABLED"], "false");
        assert_eq!(env["APP_DESKTOP_MODE"], "true");
        assert_eq!(env["APP_DESKTOP_TOKEN"], "launch-token-hex");
        assert!(!env.contains_key("APP_DESKTOP_LEGACY_POSTGRES"));
        assert_eq!(env["APP_VERSION"], APP_VERSION);
        for (var, expected) in NATIVE_SIDECARS {
            assert_eq!(env[*var], *expected, "{var}");
        }
        assert!(env
            .values()
            .all(|v| !v.contains("0.0.0.0") && !v.starts_with("/data")));
    }

    #[test]
    fn sqlite_url_roundtrips_with_backend_config_parser_for_unix_and_windows_paths() {
        let unix = sqlite_url(Path::new(
            "/Users/alice/Library/Application Support/com.picsou.desktop/picsou.db",
        ));
        assert_eq!(
            unix,
            "sqlite:///Users/alice/Library/Application%20Support/com.picsou.desktop/picsou.db"
        );
        assert_eq!(
            DatabaseConfig::parse(&unix, "", "").unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "/Users/alice/Library/Application Support/com.picsou.desktop/picsou.db"
            )))
        );

        let special = sqlite_url(Path::new("/Users/alice?bob#home/100% real/picsou.db"));
        assert_eq!(
            special,
            "sqlite:///Users/alice%3Fbob%23home/100%25%20real/picsou.db"
        );
        assert_eq!(
            DatabaseConfig::parse(&special, "", "").unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "/Users/alice?bob#home/100% real/picsou.db"
            )))
        );

        let windows = sqlite_url(Path::new(
            r"C:\Users\alice\AppData\Local\com.picsou.desktop\picsou.db",
        ));
        assert_eq!(
            windows,
            "sqlite://C:/Users/alice/AppData/Local/com.picsou.desktop/picsou.db"
        );
        assert_eq!(
            DatabaseConfig::parse(&windows, "", "").unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "C:/Users/alice/AppData/Local/com.picsou.desktop/picsou.db"
            )))
        );

        // Standard 3-slash Windows URI parses cleanly too.
        assert_eq!(
            DatabaseConfig::parse("sqlite:///C:/Users/Alice/picsou.db", "", "").unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "C:/Users/Alice/picsou.db"
            )))
        );
    }

    #[test]
    fn backend_env_loads_into_picsou_config_with_sqlite_target() {
        let root = scratch("config");
        let l = layout(&root);
        let token = ephemeral_token().unwrap();
        let env = backend_env(&l, 8123, &token);
        let config = Config::load(&Env::from_map(env)).unwrap();

        assert!(config.desktop_mode);
        assert_eq!(config.desktop_token.as_deref(), Some(token.as_str()));
        assert!(!config.desktop_legacy_postgres);
        assert!(config.database.is_sqlite());
        let expected_path = PathBuf::from(l.database().to_string_lossy().replace('\\', "/"));
        assert_eq!(
            config.database,
            DatabaseConfig::Sqlite(SqliteTarget::File(expected_path))
        );
    }

    #[test]
    fn user_env_can_override_a_native_sidecar_url_but_desktop_controls_security_and_storage() {
        let root = PathBuf::from("/picsou");
        let user_env = vec![
            (
                "BOURSO_AUTH_URL".to_owned(),
                "http://127.0.0.1:8004".to_owned(),
            ),
            ("CIC_AUTH_URL".to_owned(), "   ".to_owned()),
            (
                "DATABASE_URL".to_owned(),
                "postgres://other:pw@host:5432/db".to_owned(),
            ),
            (
                "APP_DESKTOP_TOKEN".to_owned(),
                "attacker-chosen-token".to_owned(),
            ),
            ("APP_DESKTOP_LEGACY_POSTGRES".to_owned(), "true".to_owned()),
        ];
        let env: std::collections::HashMap<_, _> =
            merge_backend_env(user_env, &layout(&root), 8123, "real-ephemeral-token")
                .into_iter()
                .collect();

        assert_eq!(env["BOURSO_AUTH_URL"], "http://127.0.0.1:8004");
        assert_eq!(env["CIC_AUTH_URL"], "native://cic-auth");
        assert_eq!(env["DEGIRO_AUTH_URL"], "native://degiro-auth");
        assert_eq!(env["CMES_AUTH_URL"], "native://cmes-auth");
        assert_eq!(env["SOFIDY_AUTH_URL"], "native://sofidy-auth");
        assert_eq!(env["NATIXIS_AUTH_URL"], "native://natixis-auth");
        assert_eq!(env["DATABASE_URL"], "sqlite:///picsou/data/picsou.db");
        assert_eq!(env["APP_DESKTOP_TOKEN"], "real-ephemeral-token");
        assert!(!env.contains_key("APP_DESKTOP_LEGACY_POSTGRES"));
    }

    #[test]
    fn ephemeral_token_is_32_random_bytes_hex_and_unique_per_call() {
        let first = ephemeral_token().unwrap();
        let second = ephemeral_token().unwrap();
        assert_eq!(first.len(), 64);
        assert!(first.bytes().all(|b| b.is_ascii_hexdigit()));
        assert_ne!(first, second);
    }

    #[test]
    fn legacy_postgres_folder_is_detected_whenever_present() {
        let root = scratch("legacy-pg");
        let l = layout(&root);
        assert!(!has_legacy_postgres(&l));
        let env_without_legacy: std::collections::HashMap<_, _> =
            backend_env(&l, 8123, "tok").into_iter().collect();
        assert!(!env_without_legacy.contains_key("APP_DESKTOP_LEGACY_POSTGRES"));

        // A regular file named `postgres` is not a legacy cluster directory.
        fs::create_dir_all(&l.data).unwrap();
        fs::write(l.legacy_postgres(), "not-a-dir").unwrap();
        assert!(!has_legacy_postgres(&l));
        fs::remove_file(l.legacy_postgres()).unwrap();

        fs::create_dir_all(l.legacy_postgres()).unwrap();
        assert!(has_legacy_postgres(&l));

        let env_with_legacy: std::collections::HashMap<_, _> =
            backend_env(&l, 8123, "tok").into_iter().collect();
        assert_eq!(
            env_with_legacy
                .get("APP_DESKTOP_LEGACY_POSTGRES")
                .map(String::as_str),
            Some("true")
        );

        // Once picsou.db is created on first launch, postgres/ is still reported so the onboarding
        // notice remains visible across restarts until setup completes.
        fs::write(l.database(), "sqlite").unwrap();
        assert!(has_legacy_postgres(&l));
        assert!(l.legacy_postgres().is_dir());

        let env_after_sqlite: std::collections::HashMap<_, _> =
            backend_env(&l, 8123, "tok").into_iter().collect();
        assert_eq!(
            env_after_sqlite
                .get("APP_DESKTOP_LEGACY_POSTGRES")
                .map(String::as_str),
            Some("true")
        );
    }

    #[test]
    fn a_secret_is_generated_once_and_private() {
        let dir = scratch("secret");
        let first = secret(&dir.join("s")).unwrap();

        assert_eq!(first.len(), 48);
        assert_eq!(secret(&dir.join("s")).unwrap(), first);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(dir.join("s")).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }

    #[test]
    fn the_last_port_is_reused_while_free_and_replaced_once_taken() {
        let _ports = TEST_PORTS.lock().unwrap_or_else(|e| e.into_inner());
        let dir = scratch("port");
        let file = dir.join("desktop-port");
        let port = |l: TcpListener| l.local_addr().unwrap().port();
        let first = port(preferred_listener(&file).unwrap());
        assert_eq!(port(preferred_listener(&file).unwrap()), first);

        let taken = TcpListener::bind((LOOPBACK, first)).unwrap();
        let second = port(preferred_listener(&file).unwrap());
        drop(taken);

        assert_ne!(second, first);
        assert_eq!(fs::read_to_string(&file).unwrap(), second.to_string());
    }
}
