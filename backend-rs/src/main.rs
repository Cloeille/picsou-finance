use std::net::SocketAddr;
use std::time::{Duration, Instant};

use picsou::config::{Config, Env};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

const HEALTHCHECK_TIMEOUT: Duration = Duration::from_secs(2);

#[tokio::main]
async fn main() {
    let _ = picsou::PROCESS_START.set(Instant::now());
    // `picsou healthcheck [--port <PORT>]` and database maintenance subcommands
    // (`picsou migrate|verify|export-sqlite <output.db>`).
    if let Some(command) = std::env::args().nth(1) {
        let args: Vec<String> = std::env::args().skip(2).collect();
        let env = Env::from_process();
        let code = if command == "healthcheck" {
            run_healthcheck(&args, &env).await
        } else {
            picsou::db::release_cli::run_with_args(&command, &args, &env).await
        };
        std::process::exit(code);
    }
    let env = Env::from_process();
    picsou::logging::init(Config::log_file_of(&env).as_deref());
    let config = match Config::load(&env) {
        Ok(config) => config,
        Err(e) => {
            eprintln!("Application failed to start: {e}");
            std::process::exit(1);
        }
    };
    let addr = SocketAddr::new(config.server_address, config.server_port);
    let outcome = match tokio::net::TcpListener::bind(addr).await {
        Ok(listener) => picsou::serve(config, listener).await,
        Err(e) => Err(anyhow::anyhow!("cannot listen on {addr}: {e}")),
    };
    if let Err(e) = outcome {
        tracing::error!("Application failed to start: {e:#}");
        std::process::exit(1);
    }
}

fn parse_port_str(raw: &str) -> Result<u16, &'static str> {
    raw.trim()
        .parse::<u16>()
        .ok()
        .filter(|p| *p >= 1)
        .ok_or("Invalid port number; expected 1..=65535.")
}

fn parse_healthcheck_port(args: &[String], env: &Env) -> Result<u16, &'static str> {
    match args {
        [] => match env
            .get("SERVER_PORT")
            .map(str::trim)
            .filter(|v| !v.is_empty())
        {
            Some(raw) => parse_port_str(raw),
            None => Ok(8080),
        },
        [flag, value] if flag == "--port" => parse_port_str(value),
        [arg] if arg.starts_with("--port=") => parse_port_str(&arg["--port=".len()..]),
        _ => Err("Usage: picsou healthcheck [--port <PORT>]"),
    }
}

fn healthcheck_host(env: &Env) -> String {
    let raw = env.get("SERVER_ADDRESS").map(str::trim).unwrap_or_default();
    if raw.is_empty() || matches!(raw, "0.0.0.0" | "::" | "[::]" | "*") {
        return "127.0.0.1".to_owned();
    }
    if let Some(inner) = raw.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
        if inner == "::" {
            return "127.0.0.1".to_owned();
        }
        return format!("[{inner}]");
    }
    if raw.contains(':') {
        format!("[{raw}]")
    } else {
        raw.to_owned()
    }
}

async fn probe_health(host: &str, port: u16, timeout: Duration) -> bool {
    let target = format!("{host}:{port}");
    let probe = async {
        let mut stream = tokio::net::TcpStream::connect(&target).await.ok()?;
        let request =
            format!("GET /actuator/health HTTP/1.1\r\nHost: {target}\r\nConnection: close\r\n\r\n");
        stream.write_all(request.as_bytes()).await.ok()?;
        let mut buf = Vec::with_capacity(256);
        let mut chunk = [0u8; 256];
        while buf.len() < 1024 {
            let n = stream.read(&mut chunk).await.ok()?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
            if buf.windows(2).any(|w| w == b"\r\n") {
                break;
            }
        }
        let head = std::str::from_utf8(&buf).ok()?;
        let status_line = head.lines().next()?;
        let mut parts = status_line.split_whitespace();
        let version = parts.next()?;
        let status = parts.next()?;
        Some(version.starts_with("HTTP/1.") && status == "200")
    };
    tokio::time::timeout(timeout, probe)
        .await
        .ok()
        .flatten()
        .unwrap_or(false)
}

async fn run_healthcheck(args: &[String], env: &Env) -> i32 {
    let port = match parse_healthcheck_port(args, env) {
        Ok(port) => port,
        Err(msg) => {
            eprintln!("{msg}");
            return 1;
        }
    };
    let host = healthcheck_host(env);
    if probe_health(&host, port, HEALTHCHECK_TIMEOUT).await {
        0
    } else {
        1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn healthcheck_port_parsing_respects_flag_then_env_then_default() {
        let empty = Env::default();
        assert_eq!(parse_healthcheck_port(&[], &empty).unwrap(), 8080);

        let env = Env::from_map([("SERVER_PORT", "9090")]);
        assert_eq!(parse_healthcheck_port(&[], &env).unwrap(), 9090);
        assert_eq!(
            parse_healthcheck_port(&["--port".to_owned(), "7070".to_owned()], &env).unwrap(),
            7070
        );
        assert_eq!(
            parse_healthcheck_port(&["--port=7071".to_owned()], &env).unwrap(),
            7071
        );

        assert!(parse_healthcheck_port(&["--port".to_owned(), "0".to_owned()], &empty).is_err());
        assert!(parse_healthcheck_port(&["--port".to_owned(), "abc".to_owned()], &empty).is_err());
        assert!(parse_healthcheck_port(&["--unknown".to_owned()], &empty).is_err());
    }

    #[test]
    fn healthcheck_host_uses_specific_server_address_and_defaults_wildcards() {
        assert_eq!(healthcheck_host(&Env::default()), "127.0.0.1");
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "   ")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "0.0.0.0")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "::")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "[::]")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "*")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "127.0.0.1")])),
            "127.0.0.1"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "::1")])),
            "[::1]"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "[::1]")])),
            "[::1]"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "10.0.0.5")])),
            "10.0.0.5"
        );
        assert_eq!(
            healthcheck_host(&Env::from_map([("SERVER_ADDRESS", "2001:db8::1")])),
            "[2001:db8::1]"
        );
    }

    #[tokio::test]
    async fn healthcheck_returns_zero_only_for_http_200() {
        // 200 OK -> exit 0.
        let ok_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let ok_port = ok_listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut sock, _) = ok_listener.accept().await.unwrap();
            let mut req = [0u8; 512];
            let _ = sock.read(&mut req).await;
            let req_text = std::str::from_utf8(&req).unwrap_or("");
            assert!(req_text.starts_with("GET /actuator/health HTTP/1.1\r\n"));
            let _ = sock
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\n{\"status\":\"UP\"}")
                .await;
        });
        assert_eq!(
            run_healthcheck(&["--port".to_owned(), ok_port.to_string()], &Env::default()).await,
            0
        );

        // 503 Service Unavailable -> exit 1.
        let bad_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let bad_port = bad_listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut sock, _) = bad_listener.accept().await.unwrap();
            let mut req = [0u8; 256];
            let _ = sock.read(&mut req).await;
            let _ = sock
                .write_all(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n")
                .await;
        });
        assert_eq!(
            run_healthcheck(
                &["--port".to_owned(), bad_port.to_string()],
                &Env::default()
            )
            .await,
            1
        );

        // Stalled server -> times out and returns false.
        let slow_listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let slow_port = slow_listener.local_addr().unwrap().port();
        let _hold = tokio::spawn(async move {
            let (_sock, _) = slow_listener.accept().await.unwrap();
            tokio::time::sleep(Duration::from_secs(5)).await;
        });
        assert!(!probe_health("127.0.0.1", slow_port, Duration::from_millis(100)).await);
    }
}
