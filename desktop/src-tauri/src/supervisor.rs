//! Starts PostgreSQL as a child process on 127.0.0.1, then the backend (`picsou`, linked in) on
//! its own tokio runtime in this process, and stops both.
//!
//! The runtime directory holds what `desktop/scripts/fetch_runtime.py` assembled: `postgres/`,
//! `pgtools/` (`pg_dump`, `pg_restore`), `spa/` and the sidecars. The data directory is the OS
//! app-data folder: `postgres/` (the cluster), `secrets/` (0600 files), `backups/`, `logs/`.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{mpsc, Arc};
use std::thread::sleep;
use std::time::{Duration, Instant};

use picsou::clock::SystemClock;
use picsou::config::{Config, Env};
use tokio::sync::oneshot;

use crate::{sidecars, upgrade, webview_bank};

pub const LOOPBACK: &str = "127.0.0.1";
const DB_USER: &str = "picsou";
#[cfg(not(windows))]
const DB_NAME: &str = "picsou";
// ponytail: Windows uses initdb's own database. Creating one needs single-user mode, which
// PostgreSQL refuses to an elevated user, or client tools the bundle does not ship.
#[cfg(windows)]
const DB_NAME: &str = "postgres";
// macOS and Windows have no C.UTF-8 locale; C with UTF8 encoding sorts by code point there.
#[cfg(target_os = "linux")]
const LOCALE: &str = "C.UTF-8";
#[cfg(not(target_os = "linux"))]
const LOCALE: &str = "C";
const STARTUP_TIMEOUT: Duration = Duration::from_secs(180);
const STOP_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Clone)]
pub struct Layout {
    pub runtime: PathBuf,
    pub data: PathBuf,
}

impl Layout {
    fn exe(&self, dir: &str, name: &str) -> PathBuf {
        self.runtime.join(dir).join("bin").join(format!("{name}{}", std::env::consts::EXE_SUFFIX))
    }
    fn cluster(&self) -> PathBuf {
        self.data.join("postgres")
    }
    fn secrets(&self) -> PathBuf {
        self.data.join("secrets")
    }
    fn log_path(&self, name: &str) -> PathBuf {
        self.data.join("logs").join(name)
    }
    pub fn log(&self, name: &str) -> std::io::Result<File> {
        fs::create_dir_all(self.data.join("logs"))?;
        OpenOptions::new().create(true).append(true).open(self.data.join("logs").join(name))
    }
}

/// The child processes, kept so `stop` can end whichever were started, even mid-startup.
#[derive(Default)]
pub struct Stack {
    postgres: Option<Child>,
    backend: Option<Backend>,
    /// Windows: PostgreSQL runs under `pg_ctl` (program, cluster), which also stops it.
    pg_ctl: Option<(PathBuf, PathBuf)>,
    /// Bank sidecars running right now, by service; `sidecars.rs` starts and stops them.
    pub sidecars: std::collections::HashMap<String, Child>,
    /// Native Tauri WebView bank bridge (BNP Paribas / Hello bank!, etc.).
    webview_bridge: Option<webview_bank::WebviewBridge>,
    pub stopping: bool,
}

pub type Shared = std::sync::Arc<std::sync::Mutex<Stack>>;

/// Held by the tests that free a port and expect it back, and by those that start sidecars on
/// ports they just freed: run together, one could take the other's port.
#[cfg(test)]
pub static TEST_PORTS: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// The version this shell and its linked backend are built from (Cargo.toml follows backend/pom.xml).
pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");

/// A start that did not reach the dashboard. `can_restore` offers the copy taken before the update.
#[derive(Debug, Clone, PartialEq)]
pub struct Failed {
    pub message: String,
    pub can_restore: bool,
}

impl From<String> for Failed {
    fn from(message: String) -> Self {
        Failed { message, can_restore: false }
    }
}

/// A child that dies with the app on Linux, even when the app is killed or crashes, so no
/// orphan keeps the cluster's lock. PostgreSQL gets SIGINT, its fast shutdown. The signal is
/// tied to the spawning thread, so `start` must run on a thread that lives as long as the app.
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
    command.args(["-c", WATCH, "picsou"]).arg(std::process::id().to_string()).arg(on_parent_death.number().to_string()).arg(program);
    command
}

/// The job of `kill_children_with_the_app`, lifted by `let_the_installer_outlive_the_app`.
#[cfg(windows)]
static JOB: std::sync::atomic::AtomicPtr<std::ffi::c_void> = std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

/// Windows: puts Picsou in a job object that kills every process in it when the last handle
/// closes, which the system does when Picsou exits, crashes or is killed. Children (pg_ctl,
/// PostgreSQL, the sidecars) inherit the job, so none outlives the app.
#[cfg(windows)]
pub fn kill_children_with_the_app() {
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation, SetInformationJobObject,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };
    use windows_sys::Win32::System::Threading::GetCurrentProcess;
    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            eprintln!("picsou: no job object, PostgreSQL may outlive a crash: {}", std::io::Error::last_os_error());
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
            eprintln!("picsou: no job object, PostgreSQL may outlive a crash: {}", std::io::Error::last_os_error());
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
        JobObjectExtendedLimitInformation, SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK,
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
            eprintln!("picsou: the installer may stop with the app: {}", std::io::Error::last_os_error());
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
        let Ready { runtime, state, listener, shutdown, stopped, .. } = self;
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

/// Starts PostgreSQL, then the backend in this process, and returns once the backend is ready.
pub fn start(layout: &Layout, stack: &Shared, app: Option<tauri::AppHandle>) -> Result<Ready, Failed> {
    let secrets = layout.secrets();
    private_dir(&layout.data).and_then(|_| private_dir(&secrets)).map_err(|e| format!("data folder: {e}"))?;
    let password = secret(&secrets.join("postgres_password")).map_err(|e| format!("database password: {e}"))?;
    let sidecar_key = secret(&secrets.join("sidecar_api_key")).map_err(|e| format!("sidecar key: {e}"))?;
    let fresh = !layout.cluster().join("PG_VERSION").exists();
    let upgrade_from = match upgrade::compare(upgrade::recorded(&layout.data).as_deref(), APP_VERSION) {
        _ if fresh => {
            init_cluster(layout)?;
            None
        }
        upgrade::Before::Downgrade { from } => return Err(newer_data(&from).into()),
        upgrade::Before::Upgrade { from } => {
            // PostgreSQL is not running yet, so the copy is consistent.
            upgrade::backup(&layout.data, &from, APP_VERSION, now_secs())
                .map_err(|e| format!("Picsou could not copy your data before the update, so it did not start: {e}"))?;
            Some(from)
        }
        upgrade::Before::Nothing => None,
    };

    let db_port = free_port().map_err(|e| format!("no free port: {e}"))?;
    let postgres_started = Instant::now();
    start_postgres(layout, stack, db_port)?;
    tracing::info!("PostgreSQL ready in {} ms", postgres_started.elapsed().as_millis());

    let listener = preferred_listener(&layout.data.join("desktop-port")).map_err(|e| format!("no free port: {e}"))?;
    let port = listener.local_addr().map_err(|e| format!("no free port: {e}"))?.port();
    // vars() panics on a variable that is not UTF-8: such a variable is skipped instead.
    let mut env: Vec<(String, String)> =
        std::env::vars_os().filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?))).collect();
    env.extend(backend_env(layout, db_port, port, &password));
    env.extend(sidecars::serve(layout, stack, &sidecar_key, sidecars::IDLE).map_err(|e| format!("sidecar ports: {e}"))?);
    if let Some(app_handle) = app {
        let mut bridge = webview_bank::start(app_handle, sidecar_key.clone())
            .map_err(|e| format!("webview bank bridge: {e}"))?;
        let bridge_url = bridge.url().to_owned();
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
        env.push(("APP_SIDECAR_API_KEY".to_owned(), sidecar_key.clone()));
    }
    let config = Config::load(&Env::from_map(env)).map_err(|e| format!("the backend settings are invalid: {e}"))?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("picsou-backend")
        .build()
        .map_err(|e| format!("the backend did not start: {e}"))?;
    if !fresh {
        let pending = runtime.block_on(pending_migrations(&config)).map_err(|e| {
            tracing::error!("could not read the migration history: {e:#}");
            "Picsou could not read your data. See the logs folder.".to_owned()
        })?;
        if let Some(first) = pending.first() {
            backup_database(layout, db_port, &password, first)?;
        }
    }
    let state = runtime.block_on(picsou::start(config, Arc::new(SystemClock))).map_err(|e| {
        tracing::error!("the backend did not start: {e:#}");
        explain("The backend did not start. See the logs folder.", upgrade::classify(&e), upgrade_from.as_deref())
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
        s.backend = Some(Backend { shutdown: shutdown_tx, stopped });
    }
    // After readiness, so the download never slows the start; before any sync can need it.
    sidecars::prefetch_browser(layout, stack);
    Ok(Ready { url: format!("http://{LOOPBACK}:{port}/"), runtime, state, listener, shutdown, stopped: stopped_tx })
}

async fn pending_migrations(config: &Config) -> anyhow::Result<Vec<String>> {
    let pool = picsou::db::connect(&config.datasource).await?;
    let pending = {
        let mut conn = pool.acquire().await?;
        picsou::db::flyway::pending(&mut conn).await?
    };
    pool.close().await;
    Ok(pending)
}

/// A `pg_dump` of the database before the backend applies `first_pending` and the ones after it.
fn backup_database(layout: &Layout, db_port: u16, password: &str, first_pending: &str) -> Result<(), Failed> {
    let started = Instant::now();
    let log = || layout.log("pg_dump.log").map_err(io::Error::other);
    let dump = upgrade::dump(&layout.data, first_pending, now_secs(), |target| {
        child(layout.exe("pgtools", "pg_dump"), Signal::Terminate)
            .args(["--host", LOOPBACK, "--port", &db_port.to_string(), "--username", DB_USER, "--dbname", DB_NAME])
            .args(["--format=custom", "--no-password", "--file"])
            .arg(target)
            .env("PGPASSWORD", password)
            .stdin(Stdio::null())
            .stdout(log()?)
            .stderr(log()?)
            .status()
    })
    .map_err(|e| format!("Picsou could not back up your data before updating it, so it did not start: {e}. See the logs folder."))?;
    tracing::info!("database backed up to {} in {} ms, before {first_pending}", dump.display(), started.elapsed().as_millis());
    Ok(())
}

#[cfg(not(windows))]
fn start_postgres(layout: &Layout, stack: &Shared, db_port: u16) -> Result<(), String> {
    let pg_log = layout.log_path("postgres.log");
    let log_start = fs::metadata(&pg_log).map(|m| m.len() as usize).unwrap_or(0);
    let postgres = child(layout.exe("postgres", "postgres"), Signal::Interrupt)
        .arg("-D").arg(layout.cluster())
        .args(["-p", &db_port.to_string(), "-c", &format!("listen_addresses={LOOPBACK}")])
        .args(["-c", "unix_socket_directories="])
        .stdin(Stdio::null())
        .stdout(layout.log("postgres.log").map_err(|e| e.to_string())?)
        .stderr(layout.log("postgres.log").map_err(|e| e.to_string())?)
        .spawn()
        .map_err(|e| format!("PostgreSQL did not start: {e}"))?;
    keep(stack, postgres, Signal::Interrupt, |s| &mut s.postgres)?;
    // An open port is not enough: during crash recovery PostgreSQL listens but refuses sessions.
    let ready = || fs::read(&pg_log).is_ok_and(|log| {
        log.get(log_start..).is_some_and(|new| String::from_utf8_lossy(new).contains("ready to accept connections"))
    });
    wait_until(ready, "PostgreSQL", stack, |s| &mut s.postgres)
}

/// Windows has no signals: `pg_ctl` starts PostgreSQL (with a restricted token, so an elevated
/// user works too), waits until it accepts sessions, and later stops it with a fast shutdown.
#[cfg(windows)]
fn start_postgres(layout: &Layout, stack: &Shared, db_port: u16) -> Result<(), String> {
    let pg_ctl = layout.exe("postgres", "pg_ctl");
    {
        let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
        if s.stopping {
            return Err("Picsou is quitting".to_owned());
        }
        s.pg_ctl = Some((pg_ctl.clone(), layout.cluster()));
    }
    let log = || layout.log("pg_ctl.log").map_err(|e| e.to_string());
    let status = child(pg_ctl, Signal::Terminate)
        .args(["start", "-w", "-t", &STARTUP_TIMEOUT.as_secs().to_string(), "-D"])
        .arg(layout.cluster())
        .arg("-l")
        .arg(layout.log_path("postgres.log"))
        .arg("-o")
        .arg(format!("-p {db_port} -c listen_addresses={LOOPBACK}"))
        .stdin(Stdio::null())
        .stdout(log()?)
        .stderr(log()?)
        .status()
        .map_err(|e| format!("PostgreSQL did not start: {e}"))?;
    if !status.success() {
        return Err(format!("PostgreSQL did not start ({status}). See the logs folder."));
    }
    Ok(())
}

fn now_secs() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn newer_data(from: &str) -> String {
    format!(
        "Your data was last opened by Picsou {from}, which is newer than this one ({APP_VERSION}). \
         Install Picsou {from} or later. Your data was not changed."
    )
}

/// The message for a backend that did not start, from why its migrations failed.
pub fn explain(stopped: &str, failure: Option<upgrade::Failure>, upgrade_from: Option<&str>) -> Failed {
    match (failure, upgrade_from) {
        (Some(upgrade::Failure::NewerData), _) => newer_data("a newer version").into(),
        (Some(upgrade::Failure::Migration(reason)), Some(from)) => Failed {
            message: format!(
                "The update from {from} to {APP_VERSION} could not update your data ({reason}). \
                 A copy from before the update is kept: restore it, then reinstall Picsou {from}."
            ),
            can_restore: true,
        },
        (Some(upgrade::Failure::Migration(reason)), None) => format!("Your data could not be updated ({reason}). See the logs folder.").into(),
        // A timeout or a crash mid-update may have touched the data too.
        (None, Some(from)) => Failed {
            message: format!("{stopped} This happened during the update from {from}: a copy from before it is kept."),
            can_restore: true,
        },
        (None, None) => stopped.to_owned().into(),
    }
}

/// Stops everything and puts back the copy taken before the update to this version.
pub fn restore_backup(layout: &Layout, stack: &Shared) -> Result<(), String> {
    let copy = upgrade::latest_backup(&layout.data, APP_VERSION).ok_or("no copy from before the update")?;
    stop(stack);
    upgrade::restore(&layout.data, &copy, now_secs()).map_err(|e| format!("restore failed: {e}"))
}

/// The backend's environment: loopback only, plain HTTP cookies, data under the app-data folder.
pub fn backend_env(layout: &Layout, db_port: u16, port: u16, password: &str) -> Vec<(String, String)> {
    let path = |p: PathBuf| p.to_string_lossy().into_owned();
    [
        ("SERVER_ADDRESS", LOOPBACK.to_owned()),
        ("SERVER_PORT", port.to_string()),
        ("SPRING_DATASOURCE_URL", format!("jdbc:postgresql://{LOOPBACK}:{db_port}/{DB_NAME}")),
        ("SPRING_DATASOURCE_USERNAME", DB_USER.to_owned()),
        ("SPRING_DATASOURCE_PASSWORD", password.to_owned()),
        ("PICSOU_SECRETS_DIR", path(layout.secrets())),
        ("APP_CRYPTO_KEY_PATH", path(layout.secrets().join("crypto_key"))),
        ("ENABLEBANKING_PRIVATE_KEY_PATH", path(layout.secrets().join("enablebanking-private.pem"))),
        ("SPA_LOCATION", path(layout.runtime.join("spa"))),
        ("APP_VERSION", APP_VERSION.to_owned()),
        ("SECURE_COOKIES", "false".to_owned()),
        ("APP_STARTUP_SYNC_ENABLED", "false".to_owned()),
        ("APP_DESKTOP_MODE", "true".to_owned()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v))
    .collect()
}

/// Stops the backend once its requests in flight are answered, then PostgreSQL with a fast
/// shutdown, so the next start needs no recovery.
pub fn stop(stack: &Shared) {
    let (backend, postgres, pg_ctl, running, webview_bridge) = {
        let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
        s.stopping = true;
        (
            s.backend.take(),
            s.postgres.take(),
            s.pg_ctl.take(),
            std::mem::take(&mut s.sidecars),
            s.webview_bridge.take(),
        )
    };
    if let Some(mut bridge) = webview_bridge {
        bridge.stop();
    }
    // In parallel: each may take up to STOP_TIMEOUT to finish a request in flight.
    let ending: Vec<_> = running.into_values().map(|s| std::thread::spawn(move || end(s, Signal::Terminate))).collect();
    for handle in ending {
        let _ = handle.join();
    }
    if let Some(Backend { shutdown, stopped }) = backend {
        let _ = shutdown.send(());
        let _ = stopped.recv_timeout(STOP_TIMEOUT);
    }
    if let Some(child) = postgres {
        // SIGINT is PostgreSQL's fast shutdown: SIGTERM would wait for every client to leave.
        end(child, Signal::Interrupt);
    }
    if let Some((program, cluster)) = pg_ctl {
        let _ = child(program, Signal::Terminate)
            .args(["stop", "-m", "fast", "-w", "-t", &STOP_TIMEOUT.as_secs().to_string(), "-D"])
            .arg(cluster)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

/// Hands a started child to `stop`, or ends it at once when quitting began meanwhile.
fn keep(stack: &Shared, child: Child, signal: Signal, slot: impl FnOnce(&mut Stack) -> &mut Option<Child>) -> Result<(), String> {
    let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
    if s.stopping {
        drop(s);
        end(child, signal);
        return Err("Picsou is quitting".to_owned());
    }
    *slot(&mut s) = Some(child);
    Ok(())
}

fn wait_until(
    ready: impl Fn() -> bool,
    what: &str,
    stack: &Shared,
    child: impl Fn(&mut Stack) -> &mut Option<Child>,
) -> Result<(), String> {
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    while Instant::now() < deadline {
        if ready() {
            return Ok(());
        }
        {
            let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
            if s.stopping {
                return Err("Picsou is quitting".to_owned());
            }
            if let Some(Ok(Some(status))) = child(&mut s).as_mut().map(Child::try_wait) {
                return Err(format!("{what} stopped ({status}). See the logs folder."));
            }
        }
        // Short: the window shows the loading page until PostgreSQL answers, and it starts in about a second.
        sleep(Duration::from_millis(20));
    }
    Err(format!("{what} did not answer within {} s. See the logs folder.", STARTUP_TIMEOUT.as_secs()))
}

fn init_cluster(layout: &Layout) -> Result<(), String> {
    let staging = layout.data.join("postgres.init");
    let _ = fs::remove_dir_all(&staging);
    let log = || layout.log("postgres.log").map_err(|e| e.to_string());
    let status = child(layout.exe("postgres", "initdb"), Signal::Terminate)
        .arg("-D").arg(&staging)
        .args(["-U", DB_USER, "--auth=scram-sha-256", "--encoding=UTF8", &format!("--locale={LOCALE}")])
        .arg(format!("--pwfile={}", layout.secrets().join("postgres_password").display()))
        .stdin(Stdio::null())
        .stdout(log()?)
        .stderr(log()?)
        .status()
        .map_err(|e| format!("initdb did not start: {e}"))?;
    if !status.success() {
        return Err(format!("initdb failed ({status}). See the logs folder."));
    }
    #[cfg(not(windows))]
    create_database(layout, &staging)?;
    // A cluster appears only complete: an interrupted first start is redone, never half-used.
    fs::rename(&staging, layout.cluster()).map_err(|e| format!("database folder: {e}"))
}

/// The bundle ships no client tools: single-user mode creates the database before any port opens.
#[cfg(not(windows))]
fn create_database(layout: &Layout, staging: &Path) -> Result<(), String> {
    let log = || layout.log("postgres.log").map_err(|e| e.to_string());
    let mut single = child(layout.exe("postgres", "postgres"), Signal::Terminate)
        .args(["--single", "-D"]).arg(staging).arg("postgres")
        .stdin(Stdio::piped())
        .stdout(log()?)
        .stderr(log()?)
        .spawn()
        .map_err(|e| format!("PostgreSQL did not start: {e}"))?;
    let sent = single.stdin.take().map(|mut input| input.write_all(format!("CREATE DATABASE {DB_NAME};\n").as_bytes()));
    let status = single.wait().map_err(|e| e.to_string())?;
    if !status.success() || !matches!(sent, Some(Ok(()))) {
        return Err(format!("the database could not be created ({status}). See the logs folder."));
    }
    Ok(())
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
    let saved = fs::read_to_string(file).ok().and_then(|s| s.trim().parse::<u16>().ok());
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
    Interrupt,
}

impl Signal {
    #[cfg(unix)]
    fn number(self) -> libc::c_int {
        match self {
            Signal::Terminate => libc::SIGTERM,
            Signal::Interrupt => libc::SIGINT,
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
        // The sidecars come here on Windows: they keep no state; pg_ctl stops PostgreSQL.
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

    fn layout(root: &Path) -> Layout {
        Layout { runtime: root.join("runtime"), data: root.join("data") }
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("picsou-desktop-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_failed_migration_after_an_update_offers_the_copy_and_newer_data_never_does() {
        let failed = explain("The backend stopped", Some(upgrade::Failure::Migration("V150 failed".into())), Some("1.0.0"));
        assert!(failed.can_restore);
        assert!(failed.message.contains("from 1.0.0 to") && failed.message.contains("V150 failed"));

        assert!(!explain("x", Some(upgrade::Failure::Migration("V150 failed".into())), None).can_restore);
        let newer = explain("x", Some(upgrade::Failure::NewerData), Some("1.0.0"));
        assert!(!newer.can_restore && newer.message.contains("was not changed"));
        assert!(explain("The backend stopped.", None, Some("1.0.0")).can_restore);
        assert_eq!(explain("The backend stopped", None, None), Failed::from("The backend stopped".to_owned()));
    }

    #[test]
    fn the_backend_binds_loopback_and_keeps_its_data_in_the_app_folder() {
        let root = PathBuf::from("/picsou");
        let env: std::collections::HashMap<_, _> = backend_env(&layout(&root), 5433, 8123, "pw").into_iter().collect();

        assert_eq!(env["SERVER_ADDRESS"], "127.0.0.1");
        assert_eq!(env["SERVER_PORT"], "8123");
        #[cfg(not(windows))]
        assert_eq!(env["SPRING_DATASOURCE_URL"], "jdbc:postgresql://127.0.0.1:5433/picsou");
        #[cfg(windows)]
        assert_eq!(env["SPRING_DATASOURCE_URL"], "jdbc:postgresql://127.0.0.1:5433/postgres");
        assert_eq!(env["SPRING_DATASOURCE_PASSWORD"], "pw");
        assert_eq!(PathBuf::from(&env["PICSOU_SECRETS_DIR"]), root.join("data").join("secrets"));
        assert_eq!(PathBuf::from(&env["SPA_LOCATION"]), root.join("runtime").join("spa"));
        assert_eq!(env["SECURE_COOKIES"], "false");
        assert_eq!(env["APP_STARTUP_SYNC_ENABLED"], "false");
        assert_eq!(env["APP_DESKTOP_MODE"], "true");
        assert_eq!(env["APP_VERSION"], APP_VERSION);
        assert!(env.values().all(|v| !v.contains("0.0.0.0") && !v.starts_with("/data")));
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
            assert_eq!(fs::metadata(dir.join("s")).unwrap().permissions().mode() & 0o777, 0o600);
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

    #[cfg(unix)]
    #[test]
    fn a_process_started_while_quitting_is_ended_not_kept() {
        let stack: Shared = Default::default();
        stop(&stack);
        let sleeper = Command::new("sleep").arg("30").spawn().unwrap();
        let pid = sleeper.id() as libc::pid_t;

        assert_eq!(keep(&stack, sleeper, Signal::Terminate, |s| &mut s.postgres), Err("Picsou is quitting".to_owned()));
        assert!(stack.lock().unwrap().postgres.is_none());
        // Reaped by `end`: the pid no longer exists.
        assert_eq!(unsafe { libc::kill(pid, 0) }, -1);
    }
}
