//! Starts PostgreSQL and the backend as child processes on 127.0.0.1, and stops them.
//!
//! The runtime directory holds what `desktop/scripts/fetch_runtime.py` assembled from the
//! Docker release: `jre/`, `postgres/`, `app.jar` and `spa/`. The data directory is the OS
//! app-data folder: `postgres/` (the cluster), `secrets/` (0600 files), `logs/`.

use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread::sleep;
use std::time::{Duration, Instant};

pub const LOOPBACK: &str = "127.0.0.1";
const DB_USER: &str = "picsou";
const DB_NAME: &str = "picsou";
const STARTUP_TIMEOUT: Duration = Duration::from_secs(180);
const STOP_TIMEOUT: Duration = Duration::from_secs(20);

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
    fn log(&self, name: &str) -> std::io::Result<File> {
        fs::create_dir_all(self.data.join("logs"))?;
        OpenOptions::new().create(true).append(true).open(self.data.join("logs").join(name))
    }
}

/// The child processes, kept so `stop` can end whichever were started, even mid-startup.
#[derive(Default)]
pub struct Stack {
    postgres: Option<Child>,
    backend: Option<Child>,
    stopping: bool,
}

pub type Shared = std::sync::Arc<std::sync::Mutex<Stack>>;

/// A child that dies with the app on Linux, even when the app is killed or crashes, so no
/// orphan keeps the cluster's lock. PostgreSQL gets SIGINT, its fast shutdown. The signal is
/// tied to the spawning thread, so `start` must run on a thread that lives as long as the app.
fn child(program: PathBuf, on_parent_death: Signal) -> Command {
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
    #[cfg(not(target_os = "linux"))]
    // ponytail: Linux only; macOS and Windows get a job object or kqueue watch with #591.
    let _ = on_parent_death;
    command
}

/// Starts both processes and returns the URL the window loads once the backend is ready.
pub fn start(layout: &Layout, stack: &Shared) -> Result<String, String> {
    let secrets = layout.secrets();
    private_dir(&layout.data).and_then(|_| private_dir(&secrets)).map_err(|e| format!("data folder: {e}"))?;
    let password = secret(&secrets.join("postgres_password")).map_err(|e| format!("database password: {e}"))?;
    if !layout.cluster().join("PG_VERSION").exists() {
        init_cluster(layout)?;
    }

    let db_port = free_port().map_err(|e| format!("no free port: {e}"))?;
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
    wait_until(ready, "PostgreSQL", stack, |s| &mut s.postgres)?;

    let port = preferred_port(&layout.data.join("desktop-port")).map_err(|e| format!("no free port: {e}"))?;
    let backend = child(layout.exe("jre", "java"), Signal::Terminate)
        // ponytail: fixed heap, sized for one household; read it from settings if users hit it.
        .args(["-Xmx768m", "-XX:+UseSerialGC", "-jar"])
        .arg(layout.runtime.join("app.jar"))
        .envs(backend_env(layout, db_port, port, &password))
        .stdin(Stdio::null())
        .stdout(layout.log("backend.log").map_err(|e| e.to_string())?)
        .stderr(layout.log("backend.log").map_err(|e| e.to_string())?)
        .spawn()
        .map_err(|e| format!("the backend did not start: {e}"))?;
    keep(stack, backend, Signal::Terminate, |s| &mut s.backend)?;
    wait_until(|| http_ok(port, "/actuator/health/readiness"), "The backend", stack, |s| &mut s.backend)?;
    Ok(format!("http://{LOOPBACK}:{port}/"))
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
        ("SECURE_COOKIES", "false".to_owned()),
        ("APP_STARTUP_SYNC_ENABLED", "false".to_owned()),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v))
    .collect()
}

/// Stops the backend, then PostgreSQL with a fast shutdown, so the next start needs no recovery.
pub fn stop(stack: &Shared) {
    let (backend, postgres) = {
        let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
        s.stopping = true;
        (s.backend.take(), s.postgres.take())
    };
    if let Some(child) = backend {
        end(child, Signal::Terminate);
    }
    if let Some(child) = postgres {
        // SIGINT is PostgreSQL's fast shutdown: SIGTERM would wait for every client to leave.
        end(child, Signal::Interrupt);
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
        sleep(Duration::from_millis(250));
    }
    Err(format!("{what} did not answer within {} s. See the logs folder.", STARTUP_TIMEOUT.as_secs()))
}

fn init_cluster(layout: &Layout) -> Result<(), String> {
    let staging = layout.data.join("postgres.init");
    let _ = fs::remove_dir_all(&staging);
    let log = || layout.log("postgres.log").map_err(|e| e.to_string());
    let status = child(layout.exe("postgres", "initdb"), Signal::Terminate)
        .arg("-D").arg(&staging)
        .args(["-U", DB_USER, "--auth=scram-sha-256", "--encoding=UTF8", "--locale=C.UTF-8"])
        .arg(format!("--pwfile={}", layout.secrets().join("postgres_password").display()))
        .stdout(log()?)
        .stderr(log()?)
        .status()
        .map_err(|e| format!("initdb did not start: {e}"))?;
    if !status.success() {
        return Err(format!("initdb failed ({status}). See the logs folder."));
    }
    // The bundle ships no client tools: single-user mode creates the database before any port opens.
    let mut single = child(layout.exe("postgres", "postgres"), Signal::Terminate)
        .args(["--single", "-D"]).arg(&staging).arg("postgres")
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
    // A cluster appears only complete: an interrupted first start is redone, never half-used.
    fs::rename(&staging, layout.cluster()).map_err(|e| format!("database folder: {e}"))
}

/// A random hex secret, written 0600 on first use and read back afterwards.
pub fn secret(path: &Path) -> std::io::Result<String> {
    if let Ok(existing) = fs::read_to_string(path) {
        return Ok(existing.trim().to_owned());
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

/// The port of the last start when it is still free, so the window keeps its origin
/// (and its local storage) across launches; a new free one otherwise.
pub fn preferred_port(file: &Path) -> std::io::Result<u16> {
    let saved = fs::read_to_string(file).ok().and_then(|s| s.trim().parse::<u16>().ok());
    if let Some(port) = saved.filter(|p| TcpListener::bind((LOOPBACK, *p)).is_ok()) {
        return Ok(port);
    }
    let port = free_port()?;
    fs::write(file, port.to_string())?;
    Ok(port)
}

fn http_ok(port: u16, path: &str) -> bool {
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    let Ok(mut stream) = TcpStream::connect_timeout(&address, Duration::from_secs(2)) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let request = format!("GET {path} HTTP/1.1\r\nHost: {LOOPBACK}:{port}\r\nConnection: close\r\n\r\n");
    let mut head = [0u8; 12];
    stream.write_all(request.as_bytes()).is_ok()
        && stream.read_exact(&mut head).is_ok()
        && head.starts_with(b"HTTP/1.1 200")
}

#[derive(Clone, Copy)]
enum Signal {
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

fn end(mut child: Child, signal: Signal) {
    #[cfg(unix)]
    unsafe {
        libc::kill(child.id() as libc::pid_t, signal.number());
    }
    #[cfg(not(unix))]
    {
        // ponytail: hard stop off Unix; a console control event comes with the Windows build (#591).
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
    fn the_backend_binds_loopback_and_keeps_its_data_in_the_app_folder() {
        let root = PathBuf::from("/picsou");
        let env: std::collections::HashMap<_, _> = backend_env(&layout(&root), 5433, 8123, "pw").into_iter().collect();

        assert_eq!(env["SERVER_ADDRESS"], "127.0.0.1");
        assert_eq!(env["SERVER_PORT"], "8123");
        assert_eq!(env["SPRING_DATASOURCE_URL"], "jdbc:postgresql://127.0.0.1:5433/picsou");
        assert_eq!(env["SPRING_DATASOURCE_PASSWORD"], "pw");
        assert_eq!(PathBuf::from(&env["PICSOU_SECRETS_DIR"]), root.join("data").join("secrets"));
        assert_eq!(PathBuf::from(&env["SPA_LOCATION"]), root.join("runtime").join("spa"));
        assert_eq!(env["SECURE_COOKIES"], "false");
        assert_eq!(env["APP_STARTUP_SYNC_ENABLED"], "false");
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
        let dir = scratch("port");
        let file = dir.join("desktop-port");
        let first = preferred_port(&file).unwrap();
        assert_eq!(preferred_port(&file).unwrap(), first);

        let taken = TcpListener::bind((LOOPBACK, first)).unwrap();
        let second = preferred_port(&file).unwrap();
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

        assert_eq!(keep(&stack, sleeper, Signal::Terminate, |s| &mut s.backend), Err("Picsou is quitting".to_owned()));
        assert!(stack.lock().unwrap().backend.is_none());
        // Reaped by `end`: the pid no longer exists.
        assert_eq!(unsafe { libc::kill(pid, 0) }, -1);
    }
}
