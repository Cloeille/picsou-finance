//! Bank connector sidecars on demand (#589).
//!
//! Each sidecar shipped in `runtime/sidecars.json` gets a loopback port that the backend reaches
//! through `<SERVICE>_URL`. Nothing runs behind it at idle: the first connection starts the
//! sidecar (`python -m uvicorn main:app`) on a private port and the bytes are piped to it. Once no
//! byte has moved for the idle time, the sidecar is stopped: bytes, not open connections, because
//! the backend's client keeps idle connections pooled. The idle time outlives the
//! sidecars' pending-login TTL (10 min), so a login waiting for its SMS code is not dropped.

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
    let mut env = Vec::new();
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
                let port = match upstream {
                    Some(port) => port,
                    None => match launch(service, base, layout, stack, key) {
                        Ok(port) => *upstream.insert(port),
                        Err(e) => {
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
        .current_dir(root.join(service))
        .env("PYTHONPATH", path)
        // The runtime is read-only once installed: use the bytecode compiled at build time.
        .env("PYTHONDONTWRITEBYTECODE", "1")
        .env("PYTHONNOUSERSITE", "1")
        .env("PYTHONUTF8", "1")
        .env("PICSOU_SIDECAR_BASE", format!("sidecar-{base}"))
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
