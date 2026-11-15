//! Bank connector sidecars on demand (#589).
//!
//! Each sidecar shipped in `runtime/sidecars.json` gets a loopback port that the backend reaches
//! through `<SERVICE>_URL`. Nothing runs behind it at idle: the first connection starts the
//! sidecar (`python -m uvicorn main:app`) on a private port and the bytes are piped to it. Once no
//! byte has moved for the idle time, the sidecar is stopped: bytes, not open connections, because
//! the backend's client keeps idle connections pooled. The idle time outlives the
//! sidecars' pending-login TTL (10 min), so a login waiting for its SMS code is not dropped.
//!
//! Chromium is not in the installer: once the backend is ready, `prefetch_browser` downloads
//! Playwright's headless shell (the bundled Playwright's version) into the app's data folder in
//! the background, before any sync needs it; every Chromium sidecar shares it. A sync that comes
//! before the download ends gets a 503 "retry", never a queued request.

use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::{self, sleep};
use std::time::{Duration, Instant};

use crate::supervisor::{child, end, free_port, Layout, Shared, Signal, LOOPBACK};

pub const IDLE: Duration = Duration::from_secs(15 * 60);
const START_TIMEOUT: Duration = Duration::from_secs(60);
const TICK: Duration = Duration::from_millis(200);
/// A first Chromium download on a slow link.
const BROWSER_TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// Chromium for the `chromium` sidecars, downloaded once in the background.
#[derive(Clone, PartialEq, Debug)]
enum Browser {
    Unknown,
    Downloading,
    Ready,
    Failed(String),
}

static BROWSER: std::sync::Mutex<Browser> = std::sync::Mutex::new(Browser::Unknown);

fn browsers(layout: &Layout) -> PathBuf {
    layout.data.join("browsers")
}

/// `Ready`, or why not yet. Never blocks: a missing browser starts its download in the
/// background, so a backend request is answered at once instead of being queued (and replayed
/// after the caller gave up, which would start a bank login nobody waits for).
fn browser(layout: &Layout, stack: &Shared) -> Browser {
    let mut state = BROWSER.lock().unwrap_or_else(|e| e.into_inner());
    if matches!(*state, Browser::Unknown | Browser::Failed(_)) {
        *state = Browser::Downloading;
        let (layout, stack) = (layout.clone(), stack.clone());
        thread::spawn(move || {
            let outcome = download_browser(&layout, &stack);
            *BROWSER.lock().unwrap_or_else(|e| e.into_inner()) = match outcome {
                Ok(()) => Browser::Ready,
                Err(e) => Browser::Failed(e),
            };
        });
    }
    state.clone()
}

/// Runs `playwright install`; a no-op (seconds) when the browser is already in place.
fn download_browser(layout: &Layout, stack: &Shared) -> Result<(), String> {
    let log = || layout.log("browser-download.log").map_err(|e| e.to_string());
    let mut command = child(python(&layout.runtime), Signal::Terminate);
    command
        .args(["-m", "playwright", "install", "chromium-headless-shell"])
        .env("PYTHONPATH", layout.runtime.join("sidecars").join("site"))
        .env("PYTHONNOUSERSITE", "1")
        .env("PLAYWRIGHT_BROWSERS_PATH", browsers(layout))
        .stdin(Stdio::null())
        .stdout(log()?)
        .stderr(log()?);
    // Its own process group: Playwright's node downloader is a grandchild, ended with it.
    #[cfg(unix)]
    std::os::unix::process::CommandExt::process_group(&mut command, 0);
    let mut install = command.spawn().map_err(|e| format!("the Chromium download did not start: {e}"))?;
    let deadline = Instant::now() + BROWSER_TIMEOUT;
    loop {
        match install.try_wait() {
            Ok(Some(status)) if status.success() => return Ok(()),
            Ok(Some(status)) => return Err(format!("the Chromium download failed ({status}); see logs/browser-download.log")),
            _ if Instant::now() > deadline || stack.lock().unwrap_or_else(|e| e.into_inner()).stopping => {
                #[cfg(unix)]
                unsafe {
                    libc::kill(-(install.id() as libc::pid_t), libc::SIGTERM);
                }
                end(install, Signal::Terminate);
                return Err("the Chromium download was stopped".to_owned());
            }
            _ => sleep(TICK),
        }
    }
}

/// Starts the Chromium download in the background when a Chromium sidecar ships, so the first
/// sync finds it ready. A no-op (seconds) when it is already there.
pub fn prefetch_browser(layout: &Layout, stack: &Shared) {
    if shipped(&layout.runtime).iter().any(|(_, base)| base == "chromium") {
        browser(layout, stack);
    }
}

/// Answers a request the sidecar cannot serve yet, without starting it.
fn unavailable(mut client: TcpStream, detail: &str) {
    let body = serde_json::json!({ "detail": detail }).to_string();
    let _ = client.set_nonblocking(false);
    let _ = write!(
        client,
        "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nRetry-After: 30\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = client.shutdown(Shutdown::Both);
}

/// The (service, base) pairs listed by `fetch_runtime.py`; none when the runtime ships no sidecar.
pub fn shipped(runtime: &Path) -> Vec<(String, String)> {
    let Ok(text) = std::fs::read_to_string(runtime.join("sidecars.json")) else { return Vec::new() };
    let Ok(serde_json::Value::Array(entries)) = serde_json::from_str(&text) else { return Vec::new() };
    entries
        .iter()
        .filter_map(|e| Some((e.get("service")?.as_str()?, e.get("base")?.as_str()?)))
        // The name becomes a folder and a variable: only the compose service shape is accepted.
        .filter(|(s, b)| well_formed(s) && well_formed(b))
        .map(|(s, b)| (s.to_owned(), b.to_owned()))
        .collect()
}

fn well_formed(name: &str) -> bool {
    !name.is_empty() && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// The sidecars of the Docker release this desktop build does not ship (`fetch_runtime.py`).
pub fn unavailable_services(runtime: &Path) -> Vec<String> {
    let text = std::fs::read_to_string(runtime.join("sidecars-unavailable.json")).unwrap_or_default();
    serde_json::from_str::<Vec<String>>(&text).unwrap_or_default().into_iter().filter(|s| well_formed(s)).collect()
}

/// `bourso-auth` → `BOURSO_AUTH_URL`, the variable `application.yml` reads.
pub fn url_variable(service: &str) -> String {
    format!("{}_URL", service.to_uppercase().replace('-', "_"))
}

fn python(runtime: &Path) -> PathBuf {
    if cfg!(windows) {
        runtime.join("python").join("python.exe")
    } else {
        runtime.join("python").join("bin").join("python3")
    }
}

/// Opens one port per shipped sidecar and returns the backend's variables for them.
pub fn serve(layout: &Layout, stack: &Shared, key: &str, idle: Duration) -> io::Result<Vec<(String, String)>> {
    // Surfaced by /actuator/info (`info.app.desktop`, `info.app.desktop-unavailable`): the SPA marks those banks unavailable.
    let mut env = vec![
        ("PICSOU_DESKTOP".to_owned(), "true".to_owned()),
        ("PICSOU_DESKTOP_UNAVAILABLE".to_owned(), unavailable_services(&layout.runtime).join(",")),
    ];
    let services = shipped(&layout.runtime);
    if !services.is_empty() {
        env.push(("APP_SIDECAR_API_KEY".to_owned(), key.to_owned()));
    }
    for (service, base) in services {
        let listener = TcpListener::bind((LOOPBACK, 0))?;
        env.push((url_variable(&service), format!("http://{LOOPBACK}:{}", listener.local_addr()?.port())));
        let (layout, stack, key) = (layout.clone(), stack.clone(), key.to_owned());
        // A thread per sidecar for the app's lifetime: Linux ties PR_SET_PDEATHSIG to the
        // spawning thread, so the sidecar must be started from a thread that does not end.
        thread::spawn(move || run(listener, &service, &base, &layout, &stack, &key, idle));
    }
    // For diagnostics (and the CI smoke test): which loopback port serves which sidecar.
    let ports: serde_json::Map<_, _> =
        env.iter().filter(|(k, _)| k.ends_with("_URL")).map(|(k, v)| (k.clone(), v.clone().into())).collect();
    // Diagnostics only: a failed write must not stop Picsou.
    let _ = std::fs::create_dir_all(&layout.data)
        .and_then(|_| std::fs::write(layout.data.join("sidecar-ports.json"), serde_json::Value::Object(ports).to_string()));
    Ok(env)
}

/// Milliseconds since `epoch` of the last byte piped either way.
struct Activity {
    epoch: Instant,
    last: AtomicU64,
}

impl Activity {
    fn touch(&self) {
        self.last.store(self.epoch.elapsed().as_millis() as u64, Ordering::SeqCst);
    }
    fn quiet_for(&self) -> Duration {
        Duration::from_millis((self.epoch.elapsed().as_millis() as u64).saturating_sub(self.last.load(Ordering::SeqCst)))
    }
}

fn run(listener: TcpListener, service: &str, base: &str, layout: &Layout, stack: &Shared, key: &str, idle: Duration) {
    if listener.set_nonblocking(true).is_err() {
        return;
    }
    let activity = Arc::new(Activity { epoch: Instant::now(), last: AtomicU64::new(0) });
    let mut upstream: Option<u16> = None;
    loop {
        if stack.lock().unwrap_or_else(|e| e.into_inner()).stopping {
            return; // `stop` ends the sidecars
        }
        match listener.accept() {
            Ok((client, _)) => {
                if upstream.is_some() && !running(stack, service) {
                    upstream = None;
                }
                if base == "chromium" && upstream.is_none() {
                    match browser(layout, stack) {
                        Browser::Ready => {}
                        Browser::Failed(e) => {
                            unavailable(client, &format!("Chromium is missing: {e}. Retry to download it again."));
                            continue;
                        }
                        _ => {
                            unavailable(client, "Chromium is downloading for its first use (about 150 MB). Retry in a few minutes.");
                            continue;
                        }
                    }
                }
                let port = match upstream {
                    Some(port) => port,
                    None => match launch(service, base, layout, stack, key) {
                        Ok(port) => *upstream.insert(port),
                        Err(e) => {
                            // In its log, where a user (and CI) looks, not only on a hidden stderr.
                            if let Ok(mut log) = layout.log(&format!("{service}.log")) {
                                let _ = writeln!(log, "picsou: {service} did not start: {e}");
                            }
                            eprintln!("picsou: {service} did not start: {e}");
                            continue; // dropping the client refuses the connection
                        }
                    },
                };
                activity.touch();
                let activity = activity.clone();
                thread::spawn(move || pipe(client, port, &activity));
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                if upstream.is_some() && activity.quiet_for() >= idle {
                    forget(stack, service);
                    upstream = None;
                }
                sleep(TICK);
            }
            Err(_) => sleep(TICK),
        }
    }
}

fn running(stack: &Shared, service: &str) -> bool {
    let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
    let alive = s.sidecars.get_mut(service).is_some_and(|c| matches!(c.try_wait(), Ok(None)));
    if !alive {
        s.sidecars.remove(service);
    }
    alive
}

fn forget(stack: &Shared, service: &str) {
    let taken = stack.lock().unwrap_or_else(|e| e.into_inner()).sidecars.remove(service);
    if let Some(sidecar) = taken {
        end(sidecar, Signal::Terminate);
    }
}

fn launch(service: &str, base: &str, layout: &Layout, stack: &Shared, key: &str) -> Result<u16, String> {
    let port = free_port().map_err(|e| format!("no free port: {e}"))?;
    let root = layout.runtime.join("sidecars");
    let path = std::env::join_paths([root.join("lib"), root.join("site")]).map_err(|e| e.to_string())?;
    let log = || layout.log(&format!("{service}.log")).map_err(|e| e.to_string());
    let sidecar = child(python(&layout.runtime), Signal::Terminate)
        .args(["-m", "uvicorn", "main:app", "--host", LOOPBACK, "--port", &port.to_string(), "--no-access-log"])
        // Longer than the backend's pooled-connection idle time, so a reused one is not reset.
        .args(["--timeout-keep-alive", "65"])
        .current_dir(root.join(service))
        .env("PYTHONPATH", path)
        // The runtime is read-only once installed: use the bytecode compiled at build time.
        .env("PYTHONDONTWRITEBYTECODE", "1")
        .env("PYTHONNOUSERSITE", "1")
        .env("PYTHONUTF8", "1")
        .env("PICSOU_SIDECAR_BASE", format!("sidecar-{base}"))
        .env("PLAYWRIGHT_BROWSERS_PATH", browsers(layout))
        .env("APP_SIDECAR_API_KEY", key)
        .stdin(Stdio::null())
        .stdout(log()?)
        .stderr(log()?)
        .spawn()
        .map_err(|e| e.to_string())?;
    {
        let mut s = stack.lock().unwrap_or_else(|e| e.into_inner());
        if s.stopping {
            drop(s);
            end(sidecar, Signal::Terminate);
            return Err("Picsou is quitting".to_owned());
        }
        s.sidecars.insert(service.to_owned(), sidecar);
    }
    let deadline = Instant::now() + START_TIMEOUT;
    while Instant::now() < deadline {
        // Alive after the probe too: the port answering must be this sidecar's.
        if TcpStream::connect((LOOPBACK, port)).is_ok() && running(stack, service) {
            return Ok(port);
        }
        if !running(stack, service) {
            return Err("it exited; see its log in the logs folder".to_owned());
        }
        sleep(TICK);
    }
    forget(stack, service);
    Err(format!("no answer within {}s", START_TIMEOUT.as_secs()))
}

fn pipe(client: TcpStream, port: u16, activity: &Arc<Activity>) -> io::Result<()> {
    client.set_nonblocking(false)?;
    let upstream = TcpStream::connect((LOOPBACK, port))?;
    for socket in [&client, &upstream] {
        socket.set_nodelay(true)?;
    }
    let (mut client_in, mut upstream_out) = (client.try_clone()?, upstream.try_clone()?);
    let forward_activity = activity.clone();
    let forward = thread::spawn(move || {
        let _ = copy(&mut client_in, &mut upstream_out, &forward_activity);
        let _ = upstream_out.shutdown(Shutdown::Write);
    });
    let (mut upstream_in, mut client_out) = (upstream, client);
    let _ = copy(&mut upstream_in, &mut client_out, activity);
    let _ = client_out.shutdown(Shutdown::Both);
    let _ = forward.join();
    Ok(())
}

fn copy(from: &mut TcpStream, to: &mut TcpStream, activity: &Activity) -> io::Result<()> {
    let mut buffer = [0u8; 16 * 1024];
    loop {
        let n = from.read(&mut buffer)?;
        if n == 0 {
            return Ok(());
        }
        activity.touch();
        to.write_all(&buffer[..n])?;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn services_map_to_the_backend_variables() {
        assert_eq!(url_variable("bourso-auth"), "BOURSO_AUTH_URL");
        assert_eq!(url_variable("caisse-epargne-auth"), "CAISSE_EPARGNE_AUTH_URL");
    }

    #[test]
    fn the_unavailable_sidecars_reach_the_backend_info() {
        let dir = std::env::temp_dir().join(format!("picsou-sidecars-unavailable-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("runtime")).unwrap();
        std::fs::write(dir.join("runtime/sidecars-unavailable.json"), r#"["amex-auth","bnp-auth","../x"]"#).unwrap();
        let layout = Layout { runtime: dir.join("runtime"), data: dir.join("data") };
        let env = serve(&layout, &Shared::default(), "k", IDLE).unwrap();
        assert!(env.contains(&("PICSOU_DESKTOP".to_owned(), "true".to_owned())));
        assert!(env.contains(&("PICSOU_DESKTOP_UNAVAILABLE".to_owned(), "amex-auth,bnp-auth".to_owned())));
    }

    #[test]
    fn only_well_formed_services_of_the_manifest_are_served() {
        let dir = std::env::temp_dir().join(format!("picsou-sidecars-manifest-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(shipped(&dir).is_empty());
        std::fs::write(
            dir.join("sidecars.json"),
            r#"[{"service":"cic-auth","base":"python"},{"service":"../etc","base":"python"},{"service":"Bad","base":"python"},{"service":"x-auth"},{}]"#,
        )
        .unwrap();
        assert_eq!(shipped(&dir), [("cic-auth".to_owned(), "python".to_owned())]);
    }

    /// A fake runtime: the system Python, and a `uvicorn` module that answers every GET.
    #[cfg(unix)]
    fn fake_runtime(name: &str) -> Layout {
        let root = std::env::temp_dir().join(format!("picsou-sidecars-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let runtime = root.join("runtime");
        std::fs::create_dir_all(runtime.join("python/bin")).unwrap();
        std::os::unix::fs::symlink("/usr/bin/python3", runtime.join("python/bin/python3")).unwrap();
        std::fs::create_dir_all(runtime.join("sidecars/lib")).unwrap();
        std::fs::create_dir_all(runtime.join("sidecars/cic-auth")).unwrap();
        std::fs::write(runtime.join("sidecars.json"), r#"[{"service":"cic-auth","base":"python"}]"#).unwrap();
        std::fs::write(
            runtime.join("sidecars/lib/uvicorn.py"),
            r#"import http.server, os, sys
port = int(sys.argv[sys.argv.index("--port") + 1])
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = (os.environ["APP_SIDECAR_API_KEY"] + " " + os.environ["PICSOU_SIDECAR_BASE"]).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", port), H).serve_forever()
"#,
        )
        .unwrap();
        Layout { runtime, data: root.join("data") }
    }

    #[cfg(unix)]
    fn get(url: &str) -> String {
        let address = url.trim_start_matches("http://");
        let mut stream = TcpStream::connect(address).unwrap();
        write!(stream, "GET /health HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n\r\n").unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).unwrap();
        answer
    }

    #[cfg(unix)]
    fn count(stack: &Shared) -> usize {
        stack.lock().unwrap().sidecars.len()
    }

    #[cfg(unix)]
    #[test]
    fn a_chromium_sidecar_answers_503_while_its_browser_downloads_and_does_not_start() {
        let layout = fake_runtime("chromium");
        std::fs::write(layout.runtime.join("sidecars.json"), r#"[{"service":"cic-auth","base":"chromium"}]"#).unwrap();
        // A download that never ends: `python -m playwright` blocks.
        std::fs::create_dir_all(layout.runtime.join("sidecars/site/playwright")).unwrap();
        std::fs::write(layout.runtime.join("sidecars/site/playwright/__init__.py"), "").unwrap();
        std::fs::write(layout.runtime.join("sidecars/site/playwright/__main__.py"), "import time\ntime.sleep(60)\n").unwrap();
        let stack = Shared::default();
        let env = serve(&layout, &stack, "k3y", Duration::from_secs(60)).unwrap();
        let url = env.iter().find(|(k, _)| k == "CIC_AUTH_URL").map(|(_, v)| v.clone()).unwrap();

        let answer = get(&url);
        assert!(answer.starts_with("HTTP/1.1 503") && answer.contains("downloading"), "{answer}");
        assert!(get(&url).starts_with("HTTP/1.1 503"), "still downloading");
        assert_eq!(count(&stack), 0, "the sidecar is not started meanwhile");
        crate::supervisor::stop(&stack);
    }

    #[cfg(unix)]
    #[test]
    fn a_sidecar_runs_only_while_it_is_used() {
        let layout = fake_runtime("idle");
        let stack = Shared::default();
        let env = serve(&layout, &stack, "k3y", Duration::from_secs(1)).unwrap();
        let url = env.iter().find(|(k, _)| k == "CIC_AUTH_URL").map(|(_, v)| v.clone()).unwrap();
        assert!(env.contains(&("APP_SIDECAR_API_KEY".to_owned(), "k3y".to_owned())));
        sleep(Duration::from_millis(500));
        assert_eq!(count(&stack), 0, "nothing runs before the first call");

        let answer = get(&url);
        assert!(answer.starts_with("HTTP/1.0 200") && answer.ends_with("k3y sidecar-python"), "{answer}");
        assert_eq!(count(&stack), 1);
        assert!(get(&url).ends_with("k3y sidecar-python"), "a second call reuses the running sidecar");

        sleep(Duration::from_secs(3));
        assert_eq!(count(&stack), 0, "stopped once idle");
        // A connection held open without traffic, like a pooled keep-alive one, does not keep it alive.
        let _pooled = TcpStream::connect(url.trim_start_matches("http://")).unwrap();
        sleep(Duration::from_secs(3));
        assert_eq!(count(&stack), 0, "an idle open connection does not hold it");
        assert!(get(&url).ends_with("k3y sidecar-python"), "started again on the next call");
        crate::supervisor::stop(&stack);
        assert_eq!(count(&stack), 0, "quitting ends it");
    }
}
