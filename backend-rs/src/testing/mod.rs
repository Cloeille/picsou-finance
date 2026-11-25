//! TestApp: the whole app in-process on a fresh database cloned from a template migrated once
//! per test run, with a fixed clock and cookie-carrying clients.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use chrono::{DateTime, TimeZone, Utc};
use reqwest::header::{COOKIE, HeaderMap, HeaderValue, SET_COOKIE};
use reqwest::{Method, StatusCode};
use serde_json::Value;
use sha2::Digest;
use sqlx::{Connection, PgConnection};
use tokio::sync::OnceCell;

use crate::AppState;
use crate::clock::FixedClock;
use crate::config::{Config, Env};

pub const DB_HOST: &str = "127.0.0.1";
pub const DB_PORT: u16 = 55432;
pub const DB_USER: &str = "picsou";
pub const JWT_SECRET: &str = "test-jwt-secret-0123456789-abcdefghijklmnopqrstu";
pub const CRYPTO_KEY: &str = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
pub const ADMIN_USERNAME: &str = "admin";
pub const ADMIN_PASSWORD: &str = "correct-horse-battery";
pub const DESKTOP_TOKEN: &str = "test-desktop-token-0123456789abcdef0123456789abcdef0123456789abcd";
/// The discard port on loopback: a connection there is refused at once.
const UNREACHABLE: &str = "http://127.0.0.1:9";

static TEMPLATE: OnceCell<String> = OnceCell::const_new();
static COUNTER: AtomicU64 = AtomicU64::new(0);

/// 2026-01-15T10:00:00Z, the instant every TestApp clock starts at.
pub fn epoch() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 1, 15, 10, 0, 0)
        .single()
        .expect("valid instant")
}

async fn admin_connection() -> PgConnection {
    let url = format!("postgres://{DB_USER}@{DB_HOST}:{DB_PORT}/postgres");
    PgConnection::connect(&url)
        .await
        .expect("the test PostgreSQL at 127.0.0.1:55432")
}

/// The template's name follows the migrations, so a changed migration gets a fresh template.
async fn template() -> &'static str {
    TEMPLATE
        .get_or_init(|| async {
            let mut digest = sha2::Sha256::new();
            for m in crate::db::flyway::migrations().expect("embedded migrations") {
                digest.update(m.script.as_bytes());
                digest.update(m.checksum.to_be_bytes());
            }
            let name = format!("picsou_tpl_{}", &hex::encode(digest.finalize())[..16]);
            let mut admin = admin_connection().await;
            let exists: bool =
                sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = $1)")
                    .bind(&name)
                    .fetch_one(&mut admin)
                    .await
                    .expect("pg_database");
            if !exists {
                let building = format!("{name}_b{}", std::process::id());
                sqlx::raw_sql(sqlx::AssertSqlSafe(format!(
                    "DROP DATABASE IF EXISTS {building}"
                )))
                .execute(&mut admin)
                .await
                .expect("drop");
                sqlx::raw_sql(sqlx::AssertSqlSafe(format!("CREATE DATABASE {building}")))
                    .execute(&mut admin)
                    .await
                    .expect("create template");
                {
                    let url = format!("postgres://{DB_USER}@{DB_HOST}:{DB_PORT}/{building}");
                    let mut conn = PgConnection::connect(&url)
                        .await
                        .expect("template connection");
                    crate::db::flyway::migrate(&mut conn)
                        .await
                        .expect("migrate template");
                    conn.close().await.expect("close template connection");
                }
                // A concurrent run may have published the same template first.
                let renamed = sqlx::raw_sql(sqlx::AssertSqlSafe(format!(
                    "ALTER DATABASE {building} RENAME TO {name}"
                )))
                .execute(&mut admin)
                .await;
                if renamed.is_err() {
                    let _ = sqlx::raw_sql(sqlx::AssertSqlSafe(format!(
                        "DROP DATABASE IF EXISTS {building}"
                    )))
                    .execute(&mut admin)
                    .await;
                }
            }
            name
        })
        .await
}

/// A database the harness created; dropped when this value is.
pub struct TestDatabase {
    name: String,
}

impl TestDatabase {
    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn url(&self) -> String {
        format!("postgres://{DB_USER}@{DB_HOST}:{DB_PORT}/{}", self.name)
    }
}

impl Drop for TestDatabase {
    fn drop(&mut self) {
        drop_database(std::mem::take(&mut self.name));
    }
}

/// A fresh database cloned from the migrated template.
pub async fn fresh_database() -> TestDatabase {
    let template = template().await;
    let name = format!(
        "picsou_t_{}_{}",
        std::process::id(),
        COUNTER.fetch_add(1, Ordering::Relaxed)
    );
    let mut admin = admin_connection().await;
    for attempt in 0.. {
        match sqlx::raw_sql(sqlx::AssertSqlSafe(format!(
            "CREATE DATABASE {name} TEMPLATE {template}"
        )))
        .execute(&mut admin)
        .await
        {
            Ok(_) => break,
            Err(e) if attempt < 50 && e.to_string().contains("being accessed by other users") => {
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            }
            Err(e) => panic!("cannot create test database: {e}"),
        }
    }
    TestDatabase { name }
}

fn drop_database(name: String) {
    if name.is_empty() {
        return;
    }
    let _ = std::thread::spawn(move || {
        let Ok(runtime) = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        else {
            return;
        };
        runtime.block_on(async {
            let mut admin = admin_connection().await;
            let _ = sqlx::raw_sql(sqlx::AssertSqlSafe(format!(
                "DROP DATABASE IF EXISTS {name} WITH (FORCE)"
            )))
            .execute(&mut admin)
            .await;
        });
    })
    .join();
}

/// Returns `true` when integration tests should run against embedded SQLite instead of PostgreSQL.
pub fn is_sqlite_test_db() -> bool {
    std::env::var("PICSOU_TEST_DB")
        .ok()
        .is_some_and(|v| v.trim().eq_ignore_ascii_case("sqlite"))
}

enum TestAppDb {
    Postgres(#[allow(dead_code)] TestDatabase),
    Sqlite(std::path::PathBuf),
}

impl Drop for TestAppDb {
    fn drop(&mut self) {
        if let Self::Sqlite(dir) = self {
            let _ = std::fs::remove_dir_all(dir);
        }
    }
}

/// Environment for a test app; any variable can be overridden, an external base URL included
/// (point it at a wiremock server's uri()).
pub struct TestAppBuilder {
    env: Env,
    seed_admin: bool,
    force_postgres: bool,
}

impl TestAppBuilder {
    pub fn env(mut self, key: &str, value: impl Into<String>) -> Self {
        self.env.set(key, value.into());
        self
    }

    /// Completes the setup wizard with ADMIN_USERNAME / ADMIN_PASSWORD after start.
    pub fn with_admin(mut self) -> Self {
        self.seed_admin = true;
        self
    }

    /// Forces PostgreSQL even when `PICSOU_TEST_DB=sqlite` is set (for PG-specific tools like
    /// `export_pg_to_sqlite`).
    pub fn postgres(mut self) -> Self {
        self.force_postgres = true;
        self
    }

    pub async fn spawn(self) -> TestApp {
        crate::logging::init(None);
        let mut env = self.env;
        let database = if is_sqlite_test_db() && !self.force_postgres {
            let dir = std::env::temp_dir().join(format!(
                "picsou_sqlite_t_{}_{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&dir).expect("create sqlite test dir");
            let db_path = dir.join("picsou.db");
            let path_str = db_path.to_string_lossy().replace('\\', "/");
            env.set(
                "DATABASE_URL",
                format!("sqlite:///{}", path_str.trim_start_matches('/')),
            );
            TestAppDb::Sqlite(dir)
        } else {
            let db = fresh_database().await;
            env.set(
                "SPRING_DATASOURCE_URL",
                format!("jdbc:postgresql://{DB_HOST}:{DB_PORT}/{}", db.name()),
            );
            env.set("SPRING_DATASOURCE_USERNAME", DB_USER);
            TestAppDb::Postgres(db)
        };
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let bound_addr = listener.local_addr().expect("addr");
        let desktop_enabled = env.get("APP_DESKTOP_MODE").is_some_and(|v| {
            matches!(
                v.trim().to_ascii_lowercase().as_str(),
                "true" | "on" | "yes" | "1"
            )
        });
        if desktop_enabled {
            if env.raw("SERVER_ADDRESS").is_none() {
                env.set("SERVER_ADDRESS", "127.0.0.1");
            }
            if env.raw("SERVER_PORT").is_none() {
                env.set("SERVER_PORT", bound_addr.port().to_string());
            }
            if env.raw("APP_DESKTOP_TOKEN").is_none() {
                env.set("APP_DESKTOP_TOKEN", DESKTOP_TOKEN);
            }
        }
        let config = Config::load(&env).expect("test configuration");
        let clock = Arc::new(FixedClock::new(epoch()));
        let state = crate::start(config, clock.clone())
            .await
            .expect("app start");
        let base_url = format!("http://{bound_addr}");
        let router = crate::app(state.clone());
        let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
        tokio::spawn(async move {
            let _ = axum::serve(
                listener,
                router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
            )
            .with_graceful_shutdown(async {
                let _ = stopped.await;
            })
            .await;
        });
        let app = TestApp {
            base_url,
            state,
            clock,
            _database: database,
            _stop: stop,
        };
        if self.seed_admin {
            app.complete_setup(ADMIN_USERNAME, ADMIN_PASSWORD).await;
        }
        app
    }
}

pub struct TestApp {
    pub base_url: String,
    pub state: AppState,
    pub clock: Arc<FixedClock>,
    _stop: tokio::sync::oneshot::Sender<()>,
    _database: TestAppDb,
}

impl TestApp {
    pub fn builder() -> TestAppBuilder {
        TestAppBuilder {
            env: Env::from_map([
                ("JWT_SECRET", JWT_SECRET),
                ("CRYPTO_ENCRYPTION_KEY", CRYPTO_KEY),
                ("SECURE_COOKIES", "false"),
                ("APP_STARTUP_SYNC_ENABLED", "false"),
                ("APP_UPDATE_CHECK_ENABLED", "false"),
                ("INSTRUMENT_LOGOS_ENABLED", "false"),
                // Price and market-data hosts: refused unless a test points them at a stub.
                ("YAHOO_FINANCE_BASE_URL", UNREACHABLE),
                ("YAHOO_QUOTE_PAGE_BASE_URL", UNREACHABLE),
                ("YAHOO_LOGO_IMAGE_BASE_URL", UNREACHABLE),
                ("COINGECKO_BASE_URL", UNREACHABLE),
                ("PRECIOUS_METALS_BASE_URL", UNREACHABLE),
                ("OPENFIGI_BASE_URL", UNREACHABLE),
                ("JUSTETF_BASE_URL", UNREACHABLE),
                ("NASDAQ_BASE_URL", UNREACHABLE),
                ("CEREMA_API_URL", UNREACHABLE),
                ("INSEE_API_URL", UNREACHABLE),
                ("GEOCODING_API_URL", UNREACHABLE),
            ]),
            seed_admin: false,
            force_postgres: false,
        }
    }

    /// A fresh app before setup.
    pub async fn spawn() -> Self {
        Self::builder().spawn().await
    }

    /// A fresh app whose setup is complete, with the admin account.
    pub async fn with_admin() -> Self {
        Self::builder().with_admin().spawn().await
    }

    /// A client without cookies.
    pub fn client(&self) -> Client {
        Client::new(&self.base_url)
    }

    /// The configured per-launch Desktop token, if any.
    pub fn desktop_token(&self) -> Option<&str> {
        self.state.config.desktop_token.as_deref()
    }

    /// A client authorized with the app's `X-Picsou-Desktop-Token` header.
    pub fn desktop_client(&self) -> Client {
        let mut client = self.client();
        if let Some(token) = self.desktop_token() {
            client.add_default_header(crate::setup_auth::desktop::DESKTOP_TOKEN_HEADER, token);
        }
        client
    }

    /// The wizard's request sequence: admin, security, complete.
    pub async fn complete_setup(&self, username: &str, password: &str) {
        let client = self.client();
        let admin = client
            .post("/api/setup/admin")
            .json(&serde_json::json!({"username": username, "password": password}))
            .send()
            .await;
        assert_eq!(
            admin.status,
            StatusCode::OK,
            "setup admin: {}",
            admin.text()
        );
        let complete = client.post("/api/setup/complete").send().await;
        assert_eq!(complete.status, StatusCode::NO_CONTENT, "setup complete");
    }

    /// Signs in and returns the client holding the session cookies.
    pub async fn login(&self, username: &str, password: &str) -> Client {
        let client = self.client();
        let response = client
            .post("/api/auth/login")
            .json(&serde_json::json!({"username": username, "password": password}))
            .send()
            .await;
        assert_eq!(
            response.status,
            StatusCode::OK,
            "login: {}",
            response.text()
        );
        client
    }

    pub async fn login_admin(&self) -> Client {
        self.login(ADMIN_USERNAME, ADMIN_PASSWORD).await
    }

    /// Creates a member with a login (role MEMBER, activated) and returns (user id, member id).
    pub async fn create_member(&self, username: &str, password: &str) -> (i64, i64) {
        let hash = bcrypt::hash(password, 4).expect("bcrypt");
        let member_id: i64 = crate::db::query_scalar(
            "INSERT INTO family_member (display_name, is_managed) VALUES ($1, false) RETURNING id",
        )
        .bind(username)
        .fetch_one(&self.state.db)
        .await
        .expect("member");
        let user_id: i64 = crate::db::query_scalar(
            "INSERT INTO app_user (username, password_hash, member_id, role, is_activated, acknowledged_warning) \
             VALUES ($1, $2, $3, 'MEMBER', true, true) RETURNING id",
        )
        .bind(username)
        .bind(hash)
        .bind(member_id)
        .fetch_one(&self.state.db)
        .await
        .expect("user");
        (user_id, member_id)
    }

    /// Creates a managed profile without a login (an admin may impersonate it); returns its member id.
    pub async fn create_managed_member(&self, display_name: &str) -> i64 {
        crate::db::query_scalar(
            "INSERT INTO family_member (display_name, is_managed) VALUES ($1, true) RETURNING id",
        )
        .bind(display_name)
        .fetch_one(&self.state.db)
        .await
        .expect("managed member")
    }

    /// Stores a personal access key for the user and returns the raw psk_ secret.
    pub async fn create_access_key(&self, user_id: i64, scopes: &[&str]) -> String {
        let body: String = (0..32)
            .map(|i| {
                char::from(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"[(i * 7 + user_id as usize) % 32])
            })
            .collect();
        let raw = format!("psk_{body}");
        crate::db::query(
            "INSERT INTO access_key (member_id, created_by, name, key_prefix, key_hash, scopes) \
             SELECT member_id, id, 'test', $2, $3, $4 FROM app_user WHERE id = $1",
        )
        .bind(user_id)
        .bind(&raw[..12])
        .bind(crate::auth::persistent::sha256_hex(&raw))
        .bind(scopes.join(" "))
        .execute(&self.state.db)
        .await
        .expect("access key");
        raw
    }
}

/// An HTTP client that keeps the cookies the server sets, like a browser.
#[derive(Clone)]
pub struct Client {
    base_url: String,
    http: reqwest::Client,
    cookies: Arc<Mutex<BTreeMap<String, String>>>,
    default_headers: Vec<(String, String)>,
}

impl Client {
    pub fn new(base_url: &str) -> Self {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .no_gzip()
            .build()
            .expect("client");
        Self {
            base_url: base_url.to_owned(),
            http,
            cookies: Arc::default(),
            default_headers: Vec::new(),
        }
    }

    pub fn add_default_header(&mut self, name: &str, value: &str) {
        self.default_headers
            .push((name.to_owned(), value.to_owned()));
    }

    pub fn cookie(&self, name: &str) -> Option<String> {
        self.cookies
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(name)
            .cloned()
    }

    pub fn set_cookie(&self, name: &str, value: &str) {
        self.cookies
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(name.to_owned(), value.to_owned());
    }

    pub fn remove_cookie(&self, name: &str) {
        self.cookies
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(name);
    }

    pub fn request(&self, method: Method, path: &str) -> Request {
        let mut builder = self
            .http
            .request(method, format!("{}{path}", self.base_url));
        for (k, v) in &self.default_headers {
            builder = builder.header(k.as_str(), v.as_str());
        }
        Request {
            allow_decimal_strings: false,
            client: self.clone(),
            builder,
        }
    }

    pub fn get(&self, path: &str) -> Request {
        self.request(Method::GET, path)
    }

    pub fn post(&self, path: &str) -> Request {
        self.request(Method::POST, path)
    }

    pub fn put(&self, path: &str) -> Request {
        self.request(Method::PUT, path)
    }

    pub fn patch(&self, path: &str) -> Request {
        self.request(Method::PATCH, path)
    }

    pub fn delete(&self, path: &str) -> Request {
        self.request(Method::DELETE, path)
    }

    fn absorb(&self, headers: &HeaderMap) {
        let mut jar = self.cookies.lock().unwrap_or_else(|e| e.into_inner());
        for value in headers.get_all(SET_COOKIE) {
            let Ok(value) = value.to_str() else { continue };
            let Some((name, rest)) = value.split_once('=') else {
                continue;
            };
            let cookie_value = rest.split(';').next().unwrap_or_default();
            let expired = rest.split(';').any(|a| a.trim() == "Max-Age=0");
            if expired || cookie_value.is_empty() {
                jar.remove(name);
            } else {
                jar.insert(name.to_owned(), cookie_value.to_owned());
            }
        }
    }
}

pub struct Request {
    client: Client,
    builder: reqwest::RequestBuilder,
    allow_decimal_strings: bool,
}

/// Jackson writes `BigDecimal` as a JSON number, while `rust_decimal` defaults to a string, so a
/// response field that forgot `rust_decimal::serde::arbitrary_precision` would silently break the
/// frontend's arithmetic. Every JSON response in the tests is checked for it.
fn assert_no_decimal_strings(headers: &HeaderMap, body: &[u8]) {
    let is_json = headers
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.contains("json"));
    if !is_json {
        return;
    }
    let Ok(value) = serde_json::from_slice::<Value>(body) else {
        return;
    };
    fn walk(value: &Value, path: &mut String) {
        match value {
            Value::String(s) => {
                let digits = s.strip_prefix('-').unwrap_or(s);
                let decimal = digits.split_once('.').is_some_and(|(int, frac)| {
                    !int.is_empty()
                        && !frac.is_empty()
                        && int.bytes().all(|b| b.is_ascii_digit())
                        && frac.bytes().all(|b| b.is_ascii_digit())
                });
                assert!(
                    !decimal,
                    "{path} is the string \"{s}\"; Java sends a JSON number. Serialize Decimal fields with rust_decimal::serde::arbitrary_precision, or call allow_decimal_strings() if Java really sends a string"
                );
            }
            Value::Array(items) => {
                for (i, item) in items.iter().enumerate() {
                    let len = path.len();
                    path.push_str(&format!("[{i}]"));
                    walk(item, path);
                    path.truncate(len);
                }
            }
            Value::Object(map) => {
                for (key, item) in map {
                    let len = path.len();
                    path.push('.');
                    path.push_str(key);
                    walk(item, path);
                    path.truncate(len);
                }
            }
            _ => {}
        }
    }
    walk(&value, &mut String::from("$"));
}

impl Request {
    /// For a response field Java declares as a `String` that can hold a decimal.
    pub fn allow_decimal_strings(mut self) -> Self {
        self.allow_decimal_strings = true;
        self
    }

    pub fn json(mut self, body: &Value) -> Self {
        self.builder = self.builder.json(body);
        self
    }

    pub fn body(mut self, content_type: &str, body: impl Into<reqwest::Body>) -> Self {
        self.builder = self
            .builder
            .header(reqwest::header::CONTENT_TYPE, content_type)
            .body(body);
        self
    }

    pub fn header(mut self, name: &str, value: &str) -> Self {
        self.builder = self.builder.header(name, value);
        self
    }

    pub fn bearer(mut self, token: &str) -> Self {
        self.builder = self.builder.bearer_auth(token);
        self
    }

    pub async fn send(self) -> Response {
        let cookie_header = {
            let jar = self
                .client
                .cookies
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            jar.iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join("; ")
        };
        let mut builder = self.builder;
        if !cookie_header.is_empty() {
            builder = builder.header(
                COOKIE,
                HeaderValue::from_str(&cookie_header).expect("cookie header"),
            );
        }
        let response = builder.send().await.expect("request sent");
        let status = response.status();
        let headers = response.headers().clone();
        self.client.absorb(&headers);
        let body = response.bytes().await.expect("body").to_vec();
        if !self.allow_decimal_strings {
            assert_no_decimal_strings(&headers, &body);
        }
        Response {
            status,
            headers,
            body,
        }
    }
}

#[derive(Debug)]
pub struct Response {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
}

impl Response {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    pub fn json(&self) -> Value {
        serde_json::from_slice(&self.body)
            .unwrap_or_else(|_| panic!("JSON body, got: {}", self.text()))
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(name).and_then(|v| v.to_str().ok())
    }

    /// Every Set-Cookie header, in order.
    pub fn set_cookies(&self) -> Vec<String> {
        self.headers
            .get_all(SET_COOKIE)
            .iter()
            .filter_map(|v| v.to_str().ok())
            .map(str::to_owned)
            .collect()
    }
}
