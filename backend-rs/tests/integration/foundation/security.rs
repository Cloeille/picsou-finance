use picsou::testing::{ADMIN_PASSWORD, ADMIN_USERNAME, TestApp};
use reqwest::StatusCode;
use serde_json::json;

#[tokio::test]
async fn security_headers_on_every_response() {
    let app = TestApp::with_admin().await;
    let response = app.client().get("/api/auth/sessions").send().await;
    for (name, value) in [
        ("x-content-type-options", "nosniff"),
        ("x-xss-protection", "0"),
        ("x-frame-options", "DENY"),
        ("referrer-policy", "strict-origin-when-cross-origin"),
        (
            "permissions-policy",
            "camera=(), microphone=(), geolocation=()",
        ),
        (
            "cache-control",
            "no-cache, no-store, max-age=0, must-revalidate",
        ),
        ("pragma", "no-cache"),
        ("expires", "0"),
    ] {
        assert_eq!(response.header(name), Some(value), "{name}");
    }
    assert!(
        response
            .header("content-security-policy")
            .unwrap()
            .starts_with("default-src 'self'; script-src 'self';")
    );
    assert!(response.header("strict-transport-security").is_none());
}

#[tokio::test]
async fn hsts_only_when_enabled_and_secure() {
    let app = TestApp::builder()
        .with_admin()
        .env("HSTS_ENABLED", " Yes ")
        .spawn()
        .await;
    let client = app.client();
    assert!(
        client
            .get("/actuator/health")
            .send()
            .await
            .header("strict-transport-security")
            .is_none()
    );
    let secure = client
        .get("/actuator/health")
        .header("x-forwarded-proto", "https")
        .send()
        .await;
    assert_eq!(
        secure.header("strict-transport-security"),
        Some("max-age=31536000 ; includeSubDomains")
    );
}

#[tokio::test]
async fn spring_404_and_405_bodies() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let missing = client.get("/api/nope").send().await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(
        missing.text(),
        r#"{"type":"about:blank","title":"Not Found","status":404,"detail":"No static resource api/nope.","instance":"/api/nope"}"#
    );
    let wrong_method = client.delete("/api/auth/mfa/status").send().await;
    assert_eq!(wrong_method.status, StatusCode::METHOD_NOT_ALLOWED);
    assert_eq!(wrong_method.header("allow"), Some("GET,HEAD"));
    assert_eq!(
        wrong_method.json()["detail"],
        "Method 'DELETE' is not supported."
    );
    assert_eq!(
        app.client().get("/api/nope").send().await.status,
        StatusCode::UNAUTHORIZED,
        "authentication first"
    );
}

#[tokio::test]
async fn cross_site_cookie_requests_are_refused() {
    let app = TestApp::with_admin().await;
    let client = app.login_admin().await;
    let cross = client
        .post("/api/auth/logout")
        .header("sec-fetch-site", "cross-site")
        .send()
        .await;
    assert_eq!(cross.status, StatusCode::FORBIDDEN);
    assert_eq!(
        cross.text(),
        "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Cross-site request rejected\"}\n"
    );
    let foreign = client
        .post("/api/auth/logout")
        .header("origin", "http://evil.example")
        .send()
        .await;
    assert_eq!(foreign.status, StatusCode::FORBIDDEN);
    let same = client
        .post("/api/auth/refresh")
        .header("origin", &app.base_url)
        .send()
        .await;
    assert_eq!(same.status, StatusCode::OK);
    // Without cookies a cross-site request carries no ambient authority.
    let anonymous = app
        .client()
        .post("/api/auth/logout")
        .header("sec-fetch-site", "cross-site")
        .send()
        .await;
    assert_eq!(anonymous.status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn an_allow_listed_origin_may_post_to_the_sso_and_oauth_endpoints() {
    const REJECTED: &str =
        "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Cross-site request rejected\"}\n";
    let app = TestApp::builder()
        .with_admin()
        .env("ALLOWED_ORIGINS", "https://app.example")
        .spawn()
        .await;
    let client = app.login_admin().await;

    for path in ["/oauth2/sso/authorization/oidc", "/oauth2/authorize"] {
        let listed = client
            .post(path)
            .header("origin", "https://app.example")
            .send()
            .await;
        assert_ne!(listed.text(), REJECTED, "{path} from the listed origin");
        let foreign = client
            .post(path)
            .header("origin", "https://evil.example")
            .send()
            .await;
        assert_eq!(foreign.status, StatusCode::FORBIDDEN, "{path}");
        assert_eq!(foreign.text(), REJECTED, "{path} from another origin");
    }
}

#[tokio::test]
async fn cors_follows_the_allow_list() {
    let app = TestApp::builder()
        .with_admin()
        .env("ALLOWED_ORIGINS", "https://app.example, *")
        .spawn()
        .await;
    let client = app.client();
    let preflight = client
        .request(reqwest::Method::OPTIONS, "/api/auth/login")
        .header("origin", "https://app.example")
        .header("access-control-request-method", "POST")
        .header("access-control-request-headers", "content-type")
        .send()
        .await;
    assert_eq!(preflight.status, StatusCode::OK);
    assert_eq!(
        preflight.header("access-control-allow-origin"),
        Some("https://app.example")
    );
    assert_eq!(
        preflight.header("access-control-allow-credentials"),
        Some("true")
    );
    assert_eq!(preflight.header("access-control-max-age"), Some("3600"));
    let refused = client
        .post("/api/auth/login")
        .header("origin", "https://evil.example")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(refused.status, StatusCode::FORBIDDEN);
    assert_eq!(refused.text(), "Invalid CORS request");
    let allowed = app.login_admin().await;
    let cross = allowed
        .post("/api/auth/refresh")
        .header("origin", "https://app.example")
        .send()
        .await;
    assert_eq!(
        cross.status,
        StatusCode::OK,
        "an allow-listed origin passes the cross-site check"
    );
    assert_eq!(
        cross.header("access-control-allow-origin"),
        Some("https://app.example")
    );
}

async fn raw_http_exchange(base_url: &str, request: &[u8]) -> (String, String) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let addr = base_url
        .strip_prefix("http://")
        .expect("http base_url for TestApp");
    let mut stream = tokio::net::TcpStream::connect(addr)
        .await
        .expect("connect to TestApp");
    stream.write_all(request).await.expect("write raw request");
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        match stream.read(&mut chunk).await {
            Ok(0) => break,
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            // Hyper closes with `Connection: close` without draining unread body bytes; on macOS
            // loopback that can emit ECONNRESET after the full 413 response has been delivered.
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset && !buf.is_empty() => break,
            Err(e) => panic!("read raw response: {e}"),
        }
    }
    let raw = String::from_utf8(buf).expect("UTF-8 HTTP response");
    let (head, body) = raw
        .split_once("\r\n\r\n")
        .expect("HTTP response header/body separator");
    (head.to_owned(), body.to_owned())
}

#[tokio::test]
async fn anonymous_bodies_are_capped() {
    let app = TestApp::with_admin().await;
    let expected_body =
        "{\"status\":413,\"title\":\"Payload Too Large\",\"code\":\"payload_too_large\"}\n";

    // Oversized Content-Length: rejected on headers with 413 + Connection: close before draining.
    let (head, body) = raw_http_exchange(
        &app.base_url,
        b"POST /api/auth/login HTTP/1.1\r\n\
          Host: localhost\r\n\
          Content-Type: application/json\r\n\
          Content-Length: 1048599\r\n\
          \r\n\
          {\"username\":\"partial",
    )
    .await;
    assert!(
        head.starts_with("HTTP/1.1 413 Payload Too Large\r\n"),
        "unexpected status line: {head}"
    );
    assert!(
        head.lines()
            .any(|l| l.eq_ignore_ascii_case("connection: close")),
        "missing Connection: close header: {head}"
    );
    assert_eq!(body, expected_body);

    // Chunked Transfer-Encoding on anonymous endpoints is also rejected with 413.
    let (head, body) = raw_http_exchange(
        &app.base_url,
        b"POST /api/auth/login HTTP/1.1\r\n\
          Host: localhost\r\n\
          Content-Type: application/json\r\n\
          Transfer-Encoding: chunked\r\n\
          \r\n\
          2\r\n{}\r\n0\r\n\r\n",
    )
    .await;
    assert!(
        head.starts_with("HTTP/1.1 413 Payload Too Large\r\n"),
        "unexpected status line for chunked: {head}"
    );
    assert!(
        head.lines()
            .any(|l| l.eq_ignore_ascii_case("connection: close")),
        "missing Connection: close header for chunked: {head}"
    );
    assert_eq!(body, expected_body);
}

#[tokio::test]
async fn the_client_address_comes_from_the_trusted_proxy_only() {
    let app = TestApp::builder()
        .with_admin()
        .env("PICSOU_PROXY_IP", "127.0.0.1")
        .spawn()
        .await;
    let client = app.client();
    client
        .post("/api/auth/login")
        .header("x-forwarded-for", "203.0.113.7")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD, "rememberMe": true}))
        .send()
        .await;
    let sessions = client.get("/api/auth/sessions").send().await.json();
    assert_eq!(sessions[0]["ipPrefix"], "203.0.113.");

    let untrusted = TestApp::with_admin().await;
    let other = untrusted.client();
    other
        .post("/api/auth/login")
        .header("x-forwarded-for", "203.0.113.7")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD, "rememberMe": true}))
        .send()
        .await;
    assert_eq!(
        other.get("/api/auth/sessions").send().await.json()[0]["ipPrefix"],
        "127.0.0."
    );
}

#[tokio::test]
async fn json_responses_are_compressed() {
    let app = TestApp::spawn().await;
    let response = app
        .client()
        .get("/api/setup/status")
        .header("accept-encoding", "gzip")
        .send()
        .await;
    assert_eq!(response.header("content-encoding"), Some("gzip"));
}

#[test]
fn an_unknown_timezone_fails_startup() {
    let env = picsou::config::Env::from_map([
        ("JWT_SECRET", picsou::testing::JWT_SECRET),
        ("CRYPTO_ENCRYPTION_KEY", picsou::testing::CRYPTO_KEY),
        ("APP_TIMEZONE", "Mars/Olympus"),
    ]);
    let error = picsou::config::Config::load(&env).unwrap_err().to_string();
    assert_eq!(
        error,
        "Invalid app.timezone: 'Mars/Olympus'. Expected an IANA zone ID such as Europe/Paris or UTC."
    );
}

#[test]
fn a_short_jwt_secret_fails_startup() {
    let env = picsou::config::Env::from_map([
        ("JWT_SECRET", "short"),
        ("CRYPTO_ENCRYPTION_KEY", picsou::testing::CRYPTO_KEY),
    ]);
    assert!(picsou::config::Config::load(&env).is_err());
}

#[test]
fn secrets_are_generated_once_with_private_permissions() {
    let dir = std::env::temp_dir().join(format!("picsou-secrets-{}", uuid::Uuid::new_v4()));
    let env = picsou::config::Env::from_map([("PICSOU_SECRETS_DIR", dir.display().to_string())]);
    let first = picsou::config::Config::load(&env).unwrap();
    let second = picsou::config::Config::load(&env).unwrap();
    assert_eq!(first.jwt.secret, second.jwt.secret);
    assert_eq!(first.crypto_key, second.crypto_key);
    assert_eq!(first.jwt.secret.len(), 64, "48 random bytes in base64");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = |p: &std::path::Path| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&dir), 0o700);
        assert_eq!(mode(&dir.join("jwt_secret")), 0o600);
        assert_eq!(mode(&dir.join("crypto_key")), 0o600);
    }
    assert!(
        !dir.join("postgres_password").exists(),
        "the database password is never generated"
    );
    std::fs::write(dir.join("postgres_password"), "from-file\n").unwrap();
    assert_eq!(
        picsou::config::Config::load(&env)
            .unwrap()
            .datasource
            .password,
        "from-file"
    );
    let explicit = picsou::config::Env::from_map([
        ("PICSOU_SECRETS_DIR", dir.display().to_string()),
        ("SPRING_DATASOURCE_PASSWORD", String::new()),
        ("POSTGRES_PASSWORD", "from-env".to_owned()),
    ]);
    assert_eq!(
        picsou::config::Config::load(&explicit)
            .unwrap()
            .datasource
            .password,
        "from-env"
    );
    let _ = std::fs::remove_dir_all(dir);
}

/// The server binary itself. A warning raised while the configuration loads reaches the log, and
/// a value holding a line break stays on its own line: it cannot forge the next one.
#[test]
fn a_startup_warning_is_logged_on_one_line() {
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};
    use std::time::Duration;

    let dir = std::env::temp_dir().join(format!("picsou-log-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let port = std::net::TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let mut child = Command::new(env!("CARGO_BIN_EXE_picsou"))
        .env_clear()
        .env(
            "DATABASE_URL",
            format!("sqlite://{}", dir.join("picsou.db").display()),
        )
        .env("JWT_SECRET", picsou::testing::JWT_SECRET)
        .env("CRYPTO_ENCRYPTION_KEY", picsou::testing::CRYPTO_KEY)
        .env("SERVER_ADDRESS", "127.0.0.1")
        .env("SERVER_PORT", port.to_string())
        .env("APP_STARTUP_SYNC_ENABLED", "false")
        .env("APP_UPDATE_CHECK_ENABLED", "false")
        .env("NO_COLOR", "1")
        .env("HSTS_ENABLED", "t rue\nINFO forged")
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let stdout = child.stdout.take().unwrap();
    let (lines_tx, lines_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if lines_tx.send(line).is_err() {
                break;
            }
        }
    });
    let mut lines = Vec::new();
    while let Ok(line) = lines_rx.recv_timeout(Duration::from_secs(30)) {
        let started = line.contains("Started Picsou");
        lines.push(line);
        if started {
            break;
        }
    }
    child.kill().unwrap();
    child.wait().unwrap();
    std::fs::remove_dir_all(&dir).ok();

    assert!(
        lines.iter().any(|line| line.contains("Started Picsou")),
        "the backend did not start: {lines:#?}"
    );
    let warnings: Vec<_> = lines
        .iter()
        .filter(|line| line.contains("not understood, HSTS disabled."))
        .collect();
    assert_eq!(warnings.len(), 1, "{lines:#?}");
    assert!(warnings[0].contains("HSTS_ENABLED='t rue?INFO forged'"));
    assert!(lines.iter().all(|line| !line.starts_with("INFO forged")));
}
