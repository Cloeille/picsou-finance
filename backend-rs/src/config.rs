//! Typed configuration read from the same environment variables as the Java
//! backend's application.yml, with the same defaults.

use std::collections::HashMap;
use std::net::IpAddr;
use std::path::PathBuf;
use std::time::Duration;

use chrono_tz::Tz;
use regex::Regex;

use crate::crypto::secrets;

/// Where configuration values come from: the process environment in production,
/// an explicit map in tests.
#[derive(Debug, Clone, Default)]
pub struct Env(HashMap<String, String>);

impl Env {
    pub fn from_process() -> Self {
        Self(std::env::vars().collect())
    }

    pub fn from_map<I, K, V>(vars: I) -> Self
    where
        I: IntoIterator<Item = (K, V)>,
        K: Into<String>,
        V: Into<String>,
    {
        Self(
            vars.into_iter()
                .map(|(k, v)| (k.into(), v.into()))
                .collect(),
        )
    }

    /// The raw value, present even when empty.
    pub fn raw(&self, key: &str) -> Option<&str> {
        self.0.get(key).map(String::as_str)
    }

    /// The value as set; Spring's placeholder defaults apply only to an absent variable.
    pub fn get(&self, key: &str) -> Option<&str> {
        self.raw(key)
    }

    pub fn set(&mut self, key: impl Into<String>, value: impl Into<String>) {
        self.0.insert(key.into(), value.into());
    }

    fn or<'a>(&'a self, key: &str, default: &'a str) -> &'a str {
        self.get(key).unwrap_or(default)
    }

    fn non_blank(&self, key: &str) -> Option<String> {
        self.get(key)
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("{0}")]
    Invalid(String),
}

fn invalid(message: impl Into<String>) -> ConfigError {
    ConfigError::Invalid(message.into())
}

/// Target for the embedded SQLite engine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SqliteTarget {
    File(PathBuf),
    InMemory,
}

/// Selected database engine configuration (`DATABASE_URL` or `SPRING_DATASOURCE_URL`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DatabaseConfig {
    Postgres(Datasource),
    Sqlite(SqliteTarget),
}

impl DatabaseConfig {
    /// Parses either a SQLite URL (`sqlite:///path`, `sqlite::memory:`, `sqlite://:memory:`,
    /// `jdbc:sqlite:...`) or a PostgreSQL URL (`jdbc:postgresql://...`, `postgres://...`,
    /// `postgresql://...`).
    pub fn parse(url: &str, username: &str, password: &str) -> Result<Self, ConfigError> {
        let trimmed = url.trim();
        if let Some(target) = parse_sqlite_url(trimmed)? {
            return Ok(Self::Sqlite(target));
        }
        if trimmed.starts_with("postgres://") || trimmed.starts_with("postgresql://") {
            let parsed = url::Url::parse(trimmed)
                .map_err(|_| invalid("DATABASE_URL is not a valid PostgreSQL URL"))?;
            let host = parsed
                .host_str()
                .filter(|h| !h.is_empty())
                .ok_or_else(|| invalid("DATABASE_URL must name a host"))?
                .trim_start_matches('[')
                .trim_end_matches(']')
                .to_owned();
            let port = parsed.port().unwrap_or(5432);
            let database = parsed.path().trim_start_matches('/').to_owned();
            if database.is_empty() {
                return Err(invalid("DATABASE_URL must name a database"));
            }
            let effective_user = if parsed.username().is_empty() {
                username.to_owned()
            } else {
                percent_encoding::percent_decode_str(parsed.username())
                    .decode_utf8_lossy()
                    .into_owned()
            };
            let effective_password = match parsed.password() {
                Some(p) => percent_encoding::percent_decode_str(p)
                    .decode_utf8_lossy()
                    .into_owned(),
                None => password.to_owned(),
            };
            let (ssl_mode, ssl_root_cert) = tls_parameters("DATABASE_URL", parsed.query())?;
            return Ok(Self::Postgres(Datasource {
                host,
                port,
                database,
                username: effective_user,
                password: effective_password,
                max_connections: 10,
                connect_timeout: Duration::from_millis(30_000),
                ssl_mode,
                ssl_root_cert,
            }));
        }
        Ok(Self::Postgres(Datasource::parse_jdbc(
            trimmed, username, password,
        )?))
    }

    pub fn is_sqlite(&self) -> bool {
        matches!(self, Self::Sqlite(_))
    }
}

fn is_windows_drive_path(path: &str) -> bool {
    let bytes = path.as_bytes();
    bytes.len() >= 3
        && bytes[0].is_ascii_alphabetic()
        && bytes[1] == b':'
        && (bytes[2] == b'/' || bytes[2] == b'\\')
}

fn parse_sqlite_url(raw: &str) -> Result<Option<SqliteTarget>, ConfigError> {
    let rest = if let Some(r) = raw.strip_prefix("jdbc:sqlite:") {
        r
    } else if let Some(r) = raw.strip_prefix("sqlite:") {
        r
    } else {
        return Ok(None);
    };

    // Strip RFC 3986 query (`?...`) and fragment (`#...`) before decoding the file path.
    let without_query_or_frag = rest.split(['?', '#']).next().unwrap_or_default();
    if without_query_or_frag == ":memory:" || without_query_or_frag == "//:memory:" {
        return Ok(Some(SqliteTarget::InMemory));
    }

    // Handle `sqlite:///abs/path.db`, standard 3-slash Windows URIs `sqlite:///C:/Users/...`,
    // `sqlite://./rel/path.db`, `sqlite://C:/Users/...`, `sqlite:/abs/path.db`, and
    // `jdbc:sqlite:/abs/path.db`.
    let has_triple_slash = without_query_or_frag.starts_with("///");
    let raw_path = if let Some(abs) = without_query_or_frag.strip_prefix("///") {
        if abs.is_empty() {
            return Err(invalid("SQLite URL must name a database file path"));
        }
        if is_windows_drive_path(abs) {
            abs.to_owned()
        } else {
            format!("/{abs}")
        }
    } else if let Some(rel) = without_query_or_frag.strip_prefix("//") {
        if rel.is_empty() {
            return Err(invalid("SQLite URL must name a database file path"));
        }
        rel.to_owned()
    } else {
        if without_query_or_frag.is_empty() {
            return Err(invalid("SQLite URL must name a database file path"));
        }
        without_query_or_frag.to_owned()
    };

    let decoded = percent_encoding::percent_decode_str(&raw_path)
        .decode_utf8_lossy()
        .into_owned();
    let normalized = if has_triple_slash
        && let Some(stripped) = decoded.strip_prefix('/')
        && is_windows_drive_path(stripped)
    {
        stripped.to_owned()
    } else {
        decoded
    };

    Ok(Some(SqliteTarget::File(PathBuf::from(normalized))))
}

/// The datasource, parsed from the JDBC-style SPRING_DATASOURCE_URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Datasource {
    pub host: String,
    pub port: u16,
    pub database: String,
    pub username: String,
    pub password: String,
    pub max_connections: u32,
    pub connect_timeout: Duration,
    /// `sslmode` of the URL (disable, allow, prefer, require, verify-ca, verify-full). None
    /// leaves the driver's default, prefer.
    pub ssl_mode: Option<String>,
    /// `sslrootcert` of the URL: the CA file the server certificate is checked against.
    pub ssl_root_cert: Option<String>,
}

const SSL_MODES: &[&str] = &[
    "disable",
    "allow",
    "prefer",
    "require",
    "verify-ca",
    "verify-full",
];

/// The TLS settings of a PostgreSQL URL's query string. The JDBC driver read them from the URL;
/// dropping them would connect without the encryption or the certificate check the operator
/// asked for. Other parameters are driver tuning and stay ignored.
fn tls_parameters(
    variable: &str,
    query: Option<&str>,
) -> Result<(Option<String>, Option<String>), ConfigError> {
    let (mut ssl_mode, mut ssl_root_cert, mut ssl) = (None, None, false);
    for (key, value) in url::form_urlencoded::parse(query.unwrap_or_default().as_bytes()) {
        match key.as_ref() {
            "sslmode" if !value.trim().is_empty() => {
                let mode = value.trim().to_ascii_lowercase();
                if !SSL_MODES.contains(&mode.as_str()) {
                    return Err(invalid(format!(
                        "{variable} has an unknown sslmode (expected one of {})",
                        SSL_MODES.join(", ")
                    )));
                }
                ssl_mode = Some(mode);
            }
            "sslrootcert" if !value.trim().is_empty() => {
                ssl_root_cert = Some(value.trim().to_owned());
            }
            "ssl" => ssl = value.trim().eq_ignore_ascii_case("true"),
            _ => {}
        }
    }
    // The JDBC driver reads ssl=true alone as sslmode=verify-full.
    if ssl_mode.is_none() && ssl {
        ssl_mode = Some("verify-full".to_owned());
    }
    Ok((ssl_mode, ssl_root_cert))
}

/// `host`, `host:port`, `[v6]` or `[v6]:port`.
fn host_and_port(authority: &str) -> Result<(String, u16), ConfigError> {
    let (host, port) = match authority.strip_prefix('[') {
        Some(bracketed) => {
            let (host, after) = bracketed
                .split_once(']')
                .ok_or_else(|| invalid("SPRING_DATASOURCE_URL has an unclosed IPv6 host"))?;
            match after.strip_prefix(':') {
                Some(port) => (host, Some(port)),
                None if after.is_empty() => (host, None),
                None => return Err(invalid("SPRING_DATASOURCE_URL has an invalid port")),
            }
        }
        None => match authority.rsplit_once(':') {
            Some((host, port)) => (host, Some(port)),
            None => (authority, None),
        },
    };
    let port = match port {
        Some(port) => port
            .parse::<u16>()
            .map_err(|_| invalid("SPRING_DATASOURCE_URL has an invalid port"))?,
        None => 5432,
    };
    Ok((host.to_owned(), port))
}

impl Default for Datasource {
    fn default() -> Self {
        Self {
            host: "localhost".to_owned(),
            port: 5432,
            database: "picsou".to_owned(),
            username: "picsou".to_owned(),
            password: String::new(),
            max_connections: 10,
            connect_timeout: Duration::from_millis(30_000),
            ssl_mode: None,
            ssl_root_cert: None,
        }
    }
}

impl Datasource {
    pub fn parse_jdbc(url: &str, username: &str, password: &str) -> Result<Self, ConfigError> {
        let rest = url
            .strip_prefix("jdbc:postgresql://")
            .ok_or_else(|| invalid("SPRING_DATASOURCE_URL must start with jdbc:postgresql://"))?;
        let (rest, query) = match rest.split_once('?') {
            Some((rest, query)) => (rest, Some(query)),
            None => (rest, None),
        };
        let (ssl_mode, ssl_root_cert) = tls_parameters("SPRING_DATASOURCE_URL", query)?;
        let (authority, database) = rest
            .split_once('/')
            .ok_or_else(|| invalid("SPRING_DATASOURCE_URL must name a database"))?;
        let (host, port) = host_and_port(authority)?;
        if host.is_empty() || database.is_empty() {
            return Err(invalid(
                "SPRING_DATASOURCE_URL must name a host and a database",
            ));
        }
        Ok(Self {
            host,
            port,
            database: database.to_owned(),
            username: username.to_owned(),
            password: password.to_owned(),
            max_connections: 10,
            connect_timeout: Duration::from_millis(30_000),
            ssl_mode,
            ssl_root_cert,
        })
    }
}

#[derive(Debug, Clone)]
pub struct JwtConfig {
    pub secret: String,
    pub access_expiry: Duration,
    pub refresh_expiry: Duration,
    pub mfa_challenge_expiry: Duration,
    pub reauth_proof_expiry: Duration,
}

#[derive(Debug, Clone)]
pub struct OAuthClientConfig {
    pub client_id: String,
    pub redirect_uri: String,
    pub access_token_ttl: Duration,
    pub refresh_token_ttl: Duration,
}

#[derive(Debug, Clone)]
pub struct SsoConfig {
    pub issuer_uri: String,
    pub client_id: String,
    pub client_secret: String,
    pub provider_name: String,
    pub scopes: Vec<String>,
    pub groups_claim: String,
    pub admin_group: String,
    pub session_revalidate_after: Duration,
    pub idp_logout: bool,
}

impl SsoConfig {
    /// SsoProperties.evaluate: the issuer must be an absolute http(s) URI with a host, the client
    /// id set and the revalidation window positive. Anything else leaves SSO off.
    pub fn enabled(&self) -> bool {
        let issuer = self.issuer_uri.trim();
        if issuer.is_empty() || self.client_id.trim().is_empty() {
            return false;
        }
        let absolute = url::Url::parse(issuer).is_ok_and(|u| {
            matches!(u.scheme(), "http" | "https") && u.host_str().is_some_and(|h| !h.is_empty())
        });
        absolute && !self.session_revalidate_after.is_zero()
    }

    /// Independent of `enabled`, as in SsoProperties: SSO settings that do not hold together must
    /// never reopen the password bootstrap of the first admin.
    pub fn admin_group_configured(&self) -> bool {
        !self.admin_group.trim().is_empty()
    }
}

#[derive(Debug, Clone)]
pub struct EnableBankingConfig {
    pub application_id: String,
    pub key_id: String,
    pub redirect_uri: String,
    pub private_key: String,
    pub private_key_path: String,
    pub base_url: String,
}

/// Which peers may set the client address through X-Forwarded-For.
#[derive(Debug, Clone)]
pub enum TrustedProxies {
    None,
    Exact(IpAddr),
    Pattern(Regex),
}

impl TrustedProxies {
    pub fn trusts(&self, peer: &IpAddr) -> bool {
        match self {
            Self::None => false,
            Self::Exact(ip) => ip == peer,
            Self::Pattern(re) => re.is_match(&crate::http::client_ip::java_ip_string(peer)),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Config {
    pub server_address: IpAddr,
    pub server_port: u16,
    pub datasource: Datasource,
    pub database: DatabaseConfig,
    pub timezone: Tz,
    pub app_version: String,
    pub desktop: String,
    pub desktop_unavailable: String,
    pub desktop_mode: bool,
    /// Per-launch secret generated by the Tauri shell (`APP_DESKTOP_TOKEN`, trimmed; None when blank).
    pub desktop_token: Option<String>,
    /// Set by the Desktop shell when a legacy embedded `postgres/` directory exists without `picsou.db`.
    pub desktop_legacy_postgres: bool,
    pub jwt: JwtConfig,
    pub crypto_key: String,
    pub crypto_key_path: PathBuf,
    pub secrets_dir: Option<PathBuf>,
    pub spa_location: Option<PathBuf>,
    pub log_file: Option<PathBuf>,
    pub cors_allowed_origins: String,
    pub secure_cookies: bool,
    pub hsts_enabled: bool,
    pub trusted_proxies: TrustedProxies,
    pub login_rate_limit_enabled: bool,
    pub admin_recovery_enabled: bool,
    pub public_base_url: String,
    pub app_username: String,
    pub app_password_hash: String,
    pub mfa_issuer: String,
    pub persistent_session_expiry: Duration,
    pub persistent_session_rotation_grace: Duration,
    pub access_key_touch_throttle: Duration,
    pub oauth: OAuthClientConfig,
    pub sso: SsoConfig,
    pub enablebanking: EnableBankingConfig,
    pub sidecars: HashMap<&'static str, String>,
    /// APP_NATIVE_SIDECARS: default the 6 pure-HTTP bank connectors to `native://<service>`.
    pub native_sidecars: bool,
    /// APP_SIDECAR_API_KEY (app.sidecar.api-key), trimmed; None when blank.
    pub sidecar_api_key: Option<String>,
    pub mcp_enabled: bool,
    pub mcp_protocol: String,
    pub mcp_keep_alive_interval: String,
    pub telemetry_dsn: String,
    pub telemetry_environment: String,
    pub valuation_enabled: bool,
    pub cerema_api_url: String,
    pub insee_api_url: String,
    pub geocoding_api_url: String,
    /// Price and market-data hosts Java hard-codes; overridable here so tests can stub them.
    pub yahoo_finance_base_url: String,
    pub coingecko_base_url: String,
    /// Optional COINGECKO_API_KEY (demo or pro); when unset, historical lookback is clamped to 364 days.
    pub coingecko_api_key: Option<String>,
    pub precious_metals_base_url: String,
    pub openfigi_base_url: String,
    pub justetf_base_url: String,
    pub nasdaq_base_url: String,
    pub update_check_enabled: bool,
    pub meria_base_url: String,
    /// The DuckDuckGo icon service Java hard-codes; overridable so tests can stub it.
    pub duckduckgo_icons_url: String,
    /// Base URLs of the providers Java hard-codes (sync module), overridable for tests.
    pub sync_urls: HashMap<&'static str, String>,
    /// Upstream bank base URLs for native in-process sidecars, overridable for wiremock tests.
    pub native_sidecar_urls: HashMap<&'static str, String>,
    pub startup_sync_enabled: bool,
    pub instrument_logos_enabled: bool,
    pub dev_profile: bool,
}

/// Provider name, its URL variable and the URL Java hard-codes. Java reads no variable for these;
/// the override exists so tests can point a provider at a stub.
pub const SYNC_URLS: &[(&str, &str, &str)] = &[
    ("binance", "BINANCE_BASE_URL", "https://api.binance.com"),
    ("wise", "WISE_API_URL", "https://api.wise.com"),
    (
        "ibkr-flex",
        "IBKR_FLEX_URL",
        "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService",
    ),
    ("louve", "LOUVE_ISSUER_URL", "https://app.louveinvest.com"),
    (
        "finary-clerk",
        "FINARY_CLERK_URL",
        "https://clerk.finary.com",
    ),
    ("finary-api", "FINARY_API_URL", "https://api.finary.com"),
    ("bitcoin", "BITCOIN_API_URL", "https://blockstream.info"),
    (
        "solana",
        "SOLANA_RPC_URL",
        "https://api.mainnet-beta.solana.com",
    ),
    (
        "evm-ethereum",
        "EVM_ETHEREUM_RPC_URL",
        "https://ethereum-rpc.publicnode.com",
    ),
    (
        "evm-bnb",
        "EVM_BNB_RPC_URL",
        "https://bsc-rpc.publicnode.com",
    ),
    (
        "evm-polygon",
        "EVM_POLYGON_RPC_URL",
        "https://polygon-bor-rpc.publicnode.com",
    ),
    (
        "evm-arbitrum",
        "EVM_ARBITRUM_RPC_URL",
        "https://arbitrum-one-rpc.publicnode.com",
    ),
    (
        "evm-optimism",
        "EVM_OPTIMISM_RPC_URL",
        "https://optimism-rpc.publicnode.com",
    ),
    (
        "evm-base",
        "EVM_BASE_RPC_URL",
        "https://base-rpc.publicnode.com",
    ),
    (
        "evm-avalanche",
        "EVM_AVALANCHE_RPC_URL",
        "https://avalanche-c-chain-rpc.publicnode.com",
    ),
];

/// Upstream bank URLs for native in-process sidecars; overridable so tests point them at wiremock.
pub const NATIVE_SIDECAR_URLS: &[(&str, &str, &str)] = &[
    ("degiro", "DEGIRO_BASE_URL", "https://trader.degiro.nl"),
    ("cmes", "CMES_BASE_URL", "https://www.creditmutuel-ere.fr"),
    ("sofidy", "SOFIDY_BASE_URL", "https://moncompte.sofidy.com"),
    ("cic", "CIC_BASE_URL", "https://www.cic.fr"),
];

/// Sidecars with built-in native Rust implementations (opted into via `APP_NATIVE_SIDECARS=true`
/// or individually via `<BANK>_AUTH_URL=native://<service>`).
pub const NATIVE_SIDECAR_SERVICES: &[&str] = &[
    "bourso-auth",
    "cic-auth",
    "cmes-auth",
    "degiro-auth",
    "natixis-auth",
    "sofidy-auth",
];

/// Sidecar compose service name, its URL variable, the production default and the dev default:
/// the rows of sidecars.tsv, which the Helm chart check and the workflow tests read too.
pub fn sidecars() -> impl Iterator<Item = [&'static str; 4]> {
    include_str!("sidecars.tsv")
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| {
            let mut columns = line.split('\t');
            std::array::from_fn(|_| columns.next().expect("sidecars.tsv has four columns"))
        })
}

/// Spring's relaxed boolean conversion: true/false, on/off, yes/no, 1/0.
fn parse_bool(key: &str, value: &str) -> Result<bool, ConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" | "on" | "yes" | "1" => Ok(true),
        "false" | "off" | "no" | "0" => Ok(false),
        _ => Err(invalid(format!("{key} must be a boolean"))),
    }
}

fn bool_or(env: &Env, key: &str, default: bool) -> Result<bool, ConfigError> {
    match env.get(key) {
        Some(v) => parse_bool(key, v),
        None => Ok(default),
    }
}

fn u64_or(env: &Env, key: &str, default: u64) -> Result<u64, ConfigError> {
    match env.get(key) {
        Some(v) => v
            .trim()
            .parse()
            .map_err(|_| invalid(format!("{key} must be a whole number"))),
        None => Ok(default),
    }
}

/// Spring's Duration conversion: a bare number of the default unit, or a value with a unit
/// suffix (ns, us, ms, s, m, h, d), or an ISO-8601 PT form.
pub fn parse_duration(value: &str, default_unit: Duration) -> Option<Duration> {
    let v = value.trim();
    if let Some(iso) = v.strip_prefix("PT").or_else(|| v.strip_prefix("pt")) {
        let (num, unit) = iso.split_at(iso.find(|c: char| c.is_ascii_alphabetic())?);
        let n: u64 = num.parse().ok()?;
        return match unit.to_ascii_uppercase().as_str() {
            "S" => Some(Duration::from_secs(n)),
            "M" => Some(Duration::from_secs(n * 60)),
            "H" => Some(Duration::from_secs(n * 3600)),
            _ => None,
        };
    }
    let split = v.find(|c: char| !c.is_ascii_digit()).unwrap_or(v.len());
    let (num, unit) = v.split_at(split);
    let n: u64 = num.parse().ok()?;
    match unit {
        "" => Some(default_unit * u32::try_from(n).ok()?),
        "ns" => Some(Duration::from_nanos(n)),
        "us" => Some(Duration::from_micros(n)),
        "ms" => Some(Duration::from_millis(n)),
        "s" => Some(Duration::from_secs(n)),
        "m" => Some(Duration::from_secs(n * 60)),
        "h" => Some(Duration::from_secs(n * 3600)),
        "d" => Some(Duration::from_secs(n * 86_400)),
        _ => None,
    }
}

/// PICSOU_PROXY_IP: one plain IPv4 address without leading zeros (TrustedProxyBootstrap).
fn parse_proxy_ip(value: &str) -> Result<IpAddr, ConfigError> {
    let plain =
        Regex::new(r"^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}$").expect("static regex");
    let message = || invalid("PICSOU_PROXY_IP must be one plain IPv4 address.");
    if !plain.is_match(value)
        || value
            .split('.')
            .any(|o| o.parse::<u16>().map_or(true, |n| n > 255))
    {
        return Err(message());
    }
    value.parse().map_err(|_| message())
}

/// An IANA zone id, as java.time.ZoneId.of accepts for region ids. Unknown ids fail startup.
pub fn parse_timezone(value: Option<&str>) -> Result<Tz, ConfigError> {
    let configured = value
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .unwrap_or("Europe/Paris");
    let id = if configured == "Z" { "UTC" } else { configured };
    id.parse::<Tz>().map_err(|_| {
        invalid(format!(
            "Invalid app.timezone: '{configured}'. Expected an IANA zone ID such as Europe/Paris or UTC."
        ))
    })
}

impl Config {
    /// Resolves the install secrets (SecretBootstrap) and then every key.
    /// LOGGING_FILE_NAME, else spring.log under LOGGING_FILE_PATH. Read on its own so the log is
    /// installed before the configuration loads: what loading warns about must reach it.
    pub fn log_file_of(env: &Env) -> Option<PathBuf> {
        env.non_blank("LOGGING_FILE_NAME")
            .map(PathBuf::from)
            .or_else(|| {
                env.non_blank("LOGGING_FILE_PATH")
                    .map(|dir| PathBuf::from(dir).join("spring.log"))
            })
    }

    pub fn load(env: &Env) -> Result<Self, ConfigError> {
        let secrets = secrets::bootstrap(env).map_err(|e| invalid(e.to_string()))?;
        Self::from_resolved(env, &secrets)
    }

    fn from_resolved(env: &Env, secrets: &secrets::Resolved) -> Result<Self, ConfigError> {
        let dev = env
            .get("SPRING_PROFILES_ACTIVE")
            .is_some_and(|p| p.split(',').any(|p| p.trim() == "dev"));

        let raw_db_url = env.non_blank("DATABASE_URL").unwrap_or_else(|| {
            env.or(
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://localhost:5432/picsou",
            )
            .to_owned()
        });
        let database = DatabaseConfig::parse(
            &raw_db_url,
            env.or("SPRING_DATASOURCE_USERNAME", "picsou"),
            &secrets.datasource_password,
        )?;
        let datasource = match &database {
            DatabaseConfig::Postgres(ds) => ds.clone(),
            DatabaseConfig::Sqlite(_) => Datasource::default(),
        };

        let jwt_secret = secrets
            .jwt_secret
            .clone()
            .ok_or_else(|| invalid("JWT_SECRET is required"))?;
        if jwt_secret.len() < 32 {
            return Err(invalid(
                "JWT_SECRET must be at least 32 characters (256 bits)",
            ));
        }
        let minutes = |key: &str, default: u64| {
            u64_or(env, key, default).map(|m| Duration::from_secs(m * 60))
        };
        let jwt = JwtConfig {
            secret: jwt_secret,
            access_expiry: minutes("APP_JWT_ACCESS_TOKEN_EXPIRY_MINUTES", 15)?,
            refresh_expiry: Duration::from_secs(
                u64_or(env, "APP_JWT_REFRESH_TOKEN_EXPIRY_DAYS", 7)? * 86_400,
            ),
            mfa_challenge_expiry: minutes("APP_JWT_MFA_CHALLENGE_EXPIRY_MINUTES", 5)?,
            reauth_proof_expiry: minutes("APP_JWT_REAUTH_PROOF_EXPIRY_MINUTES", 5)?,
        };

        let hsts_raw = env.get("HSTS_ENABLED").unwrap_or("false");
        let hsts_enabled = matches!(
            hsts_raw.trim().to_ascii_lowercase().as_str(),
            "true" | "1" | "yes" | "on"
        );
        if !hsts_enabled
            && !matches!(
                hsts_raw.trim().to_ascii_lowercase().as_str(),
                "false" | "0" | "no" | "off" | ""
            )
        {
            tracing::warn!("HSTS_ENABLED='{hsts_raw}' not understood, HSTS disabled.");
        }

        let proxy_ip = env
            .get("PICSOU_PROXY_IP")
            .filter(|v| !v.is_empty())
            .map(parse_proxy_ip)
            .transpose()?;
        let trusted_proxies = match env.raw("SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES") {
            Some("") => TrustedProxies::None,
            Some(pattern) => {
                TrustedProxies::Pattern(Regex::new(&format!("^(?:{pattern})$")).map_err(|_| {
                    invalid(
                        "SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES is not a valid regular expression",
                    )
                })?)
            }
            None => proxy_ip.map_or(TrustedProxies::None, TrustedProxies::Exact),
        };

        let native_sidecars = bool_or(env, "APP_NATIVE_SIDECARS", false)?;
        let sidecars = sidecars()
            .map(|[name, var, prod, dev_url]| {
                let value = match env.get(var) {
                    Some(v) => v.to_owned(),
                    None if native_sidecars && NATIVE_SIDECAR_SERVICES.contains(&name) => {
                        format!("native://{name}")
                    }
                    None => (if dev { dev_url } else { prod }).to_owned(),
                };
                (name, value)
            })
            .collect();

        let log_file = Self::log_file_of(env);

        let sso = SsoConfig {
            issuer_uri: env.or("SSO_ISSUER_URI", "").to_owned(),
            client_id: env.or("SSO_CLIENT_ID", "").to_owned(),
            client_secret: env.or("SSO_CLIENT_SECRET", "").to_owned(),
            provider_name: env
                .non_blank("SSO_PROVIDER_NAME")
                .unwrap_or_else(|| "SSO".to_owned()),
            scopes: env
                .or("SSO_SCOPES", "openid,profile,email")
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
                .collect(),
            groups_claim: env
                .non_blank("SSO_GROUPS_CLAIM")
                .unwrap_or_else(|| "groups".to_owned()),
            admin_group: env.or("SSO_ADMIN_GROUP", "").to_owned(),
            session_revalidate_after: env
                .non_blank("SSO_SESSION_REVALIDATE_AFTER")
                .map(|v| {
                    parse_duration(&v, Duration::from_millis(1))
                        .ok_or_else(|| invalid("SSO_SESSION_REVALIDATE_AFTER must be a duration"))
                })
                .transpose()?
                .unwrap_or(Duration::from_secs(24 * 3600)),
            idp_logout: bool_or(env, "SSO_IDP_LOGOUT", false)?,
        };

        let desktop_mode = bool_or(env, "APP_DESKTOP_MODE", false)?;
        let server_address = env
            .non_blank("SERVER_ADDRESS")
            .map(|a| {
                if a.eq_ignore_ascii_case("localhost") {
                    Ok(IpAddr::from([127, 0, 0, 1]))
                } else {
                    a.parse()
                        .map_err(|_| invalid("SERVER_ADDRESS must be an IP address"))
                }
            })
            .transpose()?
            .unwrap_or(IpAddr::from([0, 0, 0, 0]));
        if desktop_mode && !crate::http::client_ip::is_loopback_ip(&server_address) {
            return Err(invalid(format!(
                "APP_DESKTOP_MODE=true requires a loopback SERVER_ADDRESS (127.0.0.1, ::1, or localhost), got {server_address}"
            )));
        }

        Ok(Self {
            server_address,
            server_port: env
                .non_blank("SERVER_PORT")
                .map(|p| {
                    p.parse()
                        .map_err(|_| invalid("SERVER_PORT must be a port number"))
                })
                .transpose()?
                .unwrap_or(8080),
            datasource,
            database,
            timezone: parse_timezone(env.get("APP_TIMEZONE"))?,
            app_version: env.or("APP_VERSION", "dev").to_owned(),
            desktop: env.or("PICSOU_DESKTOP", "false").to_owned(),
            desktop_unavailable: env.or("PICSOU_DESKTOP_UNAVAILABLE", "").to_owned(),
            desktop_mode,
            desktop_token: env.non_blank("APP_DESKTOP_TOKEN"),
            desktop_legacy_postgres: bool_or(env, "APP_DESKTOP_LEGACY_POSTGRES", false)?,
            jwt,
            crypto_key: secrets.crypto_key.clone().unwrap_or_default(),
            crypto_key_path: PathBuf::from(
                env.or("APP_CRYPTO_KEY_PATH", "/data/.secrets/crypto_key"),
            ),
            secrets_dir: secrets.dir.clone(),
            spa_location: env.non_blank("SPA_LOCATION").map(PathBuf::from),
            log_file,
            cors_allowed_origins: env.or("ALLOWED_ORIGINS", "").to_owned(),
            secure_cookies: bool_or(env, "SECURE_COOKIES", !dev)?,
            hsts_enabled,
            trusted_proxies,
            login_rate_limit_enabled: bool_or(env, "APP_AUTH_LOGIN_RATE_LIMIT_ENABLED", !dev)?,
            admin_recovery_enabled: bool_or(env, "ADMIN_RECOVERY_ENABLED", false)?,
            public_base_url: env
                .or("PUBLIC_BASE_URL", "http://localhost:5173")
                .to_owned(),
            app_username: env.or("APP_USERNAME", "").to_owned(),
            app_password_hash: env.or("APP_PASSWORD_HASH", "").to_owned(),
            mfa_issuer: env.or("APP_MFA_ISSUER", "Picsou").to_owned(),
            persistent_session_expiry: Duration::from_secs(
                u64_or(env, "APP_PERSISTENT_SESSION_EXPIRY_DAYS", 90)? * 86_400,
            ),
            persistent_session_rotation_grace: Duration::from_secs(u64_or(
                env,
                "APP_PERSISTENT_SESSION_ROTATION_GRACE_SECONDS",
                30,
            )?),
            access_key_touch_throttle: Duration::from_secs(u64_or(
                env,
                "APP_ACCESS_KEY_TOUCH_THROTTLE_SECONDS",
                300,
            )?),
            oauth: OAuthClientConfig {
                client_id: env.or("OAUTH_IOS_CLIENT_ID", "picsou-ios").to_owned(),
                redirect_uri: env
                    .or("OAUTH_IOS_REDIRECT_URI", "picsou://callback")
                    .to_owned(),
                access_token_ttl: minutes("OAUTH_ACCESS_TTL_MINUTES", 15)?,
                refresh_token_ttl: Duration::from_secs(
                    u64_or(env, "OAUTH_REFRESH_TTL_DAYS", 30)? * 86_400,
                ),
            },
            sso,
            enablebanking: EnableBankingConfig {
                application_id: env.or("ENABLEBANKING_APPLICATION_ID", "").to_owned(),
                key_id: env.or("ENABLEBANKING_KEY_ID", "").to_owned(),
                redirect_uri: env
                    .or(
                        "ENABLEBANKING_REDIRECT_URI",
                        "https://localhost:5173/sync/callback",
                    )
                    .to_owned(),
                private_key: env.or("ENABLEBANKING_PRIVATE_KEY", "").to_owned(),
                private_key_path: env
                    .or(
                        "ENABLEBANKING_PRIVATE_KEY_PATH",
                        if dev {
                            ".local/keys/enablebanking-private.pem"
                        } else {
                            "/data/keys/enablebanking-private.pem"
                        },
                    )
                    .to_owned(),
                base_url: env
                    .or("ENABLEBANKING_BASE_URL", "https://api.enablebanking.com")
                    .to_owned(),
            },
            sidecars,
            native_sidecars,
            sidecar_api_key: env.non_blank("APP_SIDECAR_API_KEY"),
            mcp_enabled: bool_or(env, "MCP_ENABLED", true)?,
            mcp_protocol: env.or("MCP_PROTOCOL", "SSE").to_owned(),
            mcp_keep_alive_interval: env.or("MCP_KEEP_ALIVE_INTERVAL", "60s").to_owned(),
            telemetry_dsn: env.or("APP_TELEMETRY_DSN", "").to_owned(),
            telemetry_environment: env.or("APP_TELEMETRY_ENVIRONMENT", "production").to_owned(),
            valuation_enabled: bool_or(env, "VALUATION_ENABLED", true)?,
            cerema_api_url: env
                .or("CEREMA_API_URL", "https://apidf-preprod.cerema.fr")
                .to_owned(),
            insee_api_url: env
                .or("INSEE_API_URL", "https://api.insee.fr/series/BDM/V1")
                .to_owned(),
            geocoding_api_url: env
                .or("GEOCODING_API_URL", "https://data.geopf.fr/geocodage")
                .to_owned(),
            yahoo_finance_base_url: env
                .or("YAHOO_FINANCE_BASE_URL", "https://query1.finance.yahoo.com")
                .to_owned(),
            coingecko_base_url: env
                .or("COINGECKO_BASE_URL", "https://api.coingecko.com/api/v3")
                .to_owned(),
            coingecko_api_key: env.non_blank("COINGECKO_API_KEY"),
            precious_metals_base_url: env
                .or("PRECIOUS_METALS_BASE_URL", "https://www.loomis-fxgs.fr")
                .to_owned(),
            openfigi_base_url: env
                .or("OPENFIGI_BASE_URL", "https://api.openfigi.com")
                .to_owned(),
            justetf_base_url: env
                .or("JUSTETF_BASE_URL", "https://www.justetf.com")
                .to_owned(),
            nasdaq_base_url: env
                .or("NASDAQ_BASE_URL", "https://api.nasdaq.com")
                .to_owned(),
            update_check_enabled: bool_or(env, "APP_UPDATE_CHECK_ENABLED", true)?,
            meria_base_url: env
                .or("MERIA_BASE_URL", "https://api.meria.com/v1")
                .to_owned(),
            duckduckgo_icons_url: env
                .or("DUCKDUCKGO_ICONS_URL", "https://icons.duckduckgo.com")
                .to_owned(),
            sync_urls: SYNC_URLS
                .iter()
                .map(|(name, var, default)| (*name, env.or(var, default).to_owned()))
                .collect(),
            native_sidecar_urls: NATIVE_SIDECAR_URLS
                .iter()
                .map(|(name, var, default)| (*name, env.or(var, default).to_owned()))
                .collect(),
            startup_sync_enabled: bool_or(env, "APP_STARTUP_SYNC_ENABLED", true)?,
            instrument_logos_enabled: bool_or(env, "INSTRUMENT_LOGOS_ENABLED", true)?,
            dev_profile: dev,
        })
    }

    /// The base URL of a sync provider by its SYNC_URLS name.
    pub fn sync_url(&self, name: &str) -> &str {
        self.sync_urls
            .get(name)
            .map(String::as_str)
            .expect("a provider listed in SYNC_URLS")
    }

    /// The URL of a connector sidecar by its compose service name.
    pub fn sidecar_url(&self, name: &str) -> Option<&str> {
        self.sidecars.get(name).map(String::as_str)
    }

    /// The upstream bank base URL for a native in-process sidecar.
    pub fn native_sidecar_url(&self, name: &str) -> &str {
        self.native_sidecar_urls
            .get(name)
            .map(String::as_str)
            .expect("a bank listed in NATIVE_SIDECAR_URLS")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_env() -> Env {
        Env::from_map([
            ("JWT_SECRET", "0123456789abcdef0123456789abcdef"),
            ("CRYPTO_ENCRYPTION_KEY", "0123456789abcdef0123456789abcdef"),
        ])
    }

    #[test]
    fn native_sidecars_flag_defaults_off_and_opts_in_the_six_http_banks() {
        let off = Config::load(&base_env()).unwrap();
        assert!(!off.native_sidecars);
        assert_eq!(
            off.sidecar_url("bourso-auth"),
            Some("http://bourso-auth:8001")
        );
        assert_eq!(
            off.sidecar_url("degiro-auth"),
            Some("http://degiro-auth:8001")
        );
        assert_eq!(off.sidecar_url("tr-auth"), Some("http://tr-auth:8001"));

        let mut env = base_env();
        env.set("APP_NATIVE_SIDECARS", "true");
        let on = Config::load(&env).unwrap();
        assert!(on.native_sidecars);
        for service in NATIVE_SIDECAR_SERVICES {
            assert_eq!(
                on.sidecar_url(service),
                Some(format!("native://{service}").as_str()),
                "{service}"
            );
        }
        // Browser-based sidecars keep their HTTP defaults.
        assert_eq!(on.sidecar_url("tr-auth"), Some("http://tr-auth:8001"));
        assert_eq!(on.sidecar_url("bnp-auth"), Some("http://bnp-auth:8001"));

        // Explicit per-bank URL overrides APP_NATIVE_SIDECARS.
        env.set("BOURSO_AUTH_URL", "http://127.0.0.1:8004");
        let overridden = Config::load(&env).unwrap();
        assert_eq!(
            overridden.sidecar_url("bourso-auth"),
            Some("http://127.0.0.1:8004")
        );
        assert_eq!(
            overridden.sidecar_url("cic-auth"),
            Some("native://cic-auth")
        );
    }

    #[test]
    fn database_config_supports_sqlite_and_postgres_urls() {
        // Default remains PostgreSQL jdbc URL.
        let pg_default = Config::load(&base_env()).unwrap();
        assert!(!pg_default.database.is_sqlite());
        assert_eq!(pg_default.datasource.host, "localhost");
        assert_eq!(pg_default.datasource.port, 5432);
        assert_eq!(pg_default.datasource.database, "picsou");

        // DATABASE_URL=sqlite:///abs/path.db
        let mut env = base_env();
        env.set("DATABASE_URL", "sqlite:///data/picsou.db");
        let sq_file = Config::load(&env).unwrap();
        assert_eq!(
            sq_file.database,
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from("/data/picsou.db")))
        );

        // DATABASE_URL=sqlite::memory:
        let mut env = base_env();
        env.set("DATABASE_URL", "sqlite::memory:");
        let sq_mem = Config::load(&env).unwrap();
        assert_eq!(
            sq_mem.database,
            DatabaseConfig::Sqlite(SqliteTarget::InMemory)
        );

        // SPRING_DATASOURCE_URL=jdbc:sqlite:/tmp/local.db
        let mut env = base_env();
        env.set("SPRING_DATASOURCE_URL", "jdbc:sqlite:/tmp/local.db");
        let sq_jdbc = Config::load(&env).unwrap();
        assert_eq!(
            sq_jdbc.database,
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from("/tmp/local.db")))
        );

        // DATABASE_URL=postgres://user:secret@db.internal:5433/picsou_prod
        let mut env = base_env();
        env.set(
            "DATABASE_URL",
            "postgres://alice:s3cr3t@db.internal:5433/picsou_prod",
        );
        let pg_uri = Config::load(&env).unwrap();
        assert_eq!(pg_uri.datasource.host, "db.internal");
        assert_eq!(pg_uri.datasource.port, 5433);
        assert_eq!(pg_uri.datasource.database, "picsou_prod");
        assert_eq!(pg_uri.datasource.username, "alice");
        assert_eq!(pg_uri.datasource.password, "s3cr3t");

        // Reject empty SQLite path.
        let mut env = base_env();
        env.set("DATABASE_URL", "sqlite:///");
        assert!(Config::load(&env).is_err());
    }

    #[test]
    fn the_tls_settings_of_a_postgres_url_are_kept() {
        let jdbc = Datasource::parse_jdbc(
            "jdbc:postgresql://db.internal:5433/picsou?sslmode=verify-full&sslrootcert=%2Fcerts%2Fca.crt&ApplicationName=x",
            "picsou",
            "secret",
        )
        .unwrap();
        assert_eq!((jdbc.host.as_str(), jdbc.port), ("db.internal", 5433));
        assert_eq!(jdbc.database, "picsou");
        assert_eq!(jdbc.ssl_mode.as_deref(), Some("verify-full"));
        assert_eq!(jdbc.ssl_root_cert.as_deref(), Some("/certs/ca.crt"));

        let DatabaseConfig::Postgres(uri) =
            DatabaseConfig::parse("postgres://u:p@db/picsou?sslmode=REQUIRE", "", "").unwrap()
        else {
            panic!("a PostgreSQL URL");
        };
        assert_eq!(uri.ssl_mode.as_deref(), Some("require"));

        let plain = Datasource::parse_jdbc("jdbc:postgresql://db/picsou", "u", "p").unwrap();
        assert_eq!((plain.ssl_mode, plain.ssl_root_cert), (None, None));
        let mode = |query: &str| {
            Datasource::parse_jdbc(&format!("jdbc:postgresql://db/picsou?{query}"), "u", "p")
                .unwrap()
                .ssl_mode
        };
        assert_eq!(mode("sslmode="), None);
        assert_eq!(mode("ssl=true").as_deref(), Some("verify-full"));
        assert_eq!(mode("ssl=true&sslmode=require").as_deref(), Some("require"));
        assert_eq!(mode("ssl=false"), None);
        assert!(
            Datasource::parse_jdbc("jdbc:postgresql://db/picsou?sslmode=strict", "u", "p").is_err()
        );
    }

    #[test]
    fn a_bracketed_ipv6_host_needs_no_port() {
        let host = |url: &str| {
            let ds = Datasource::parse_jdbc(url, "u", "p").unwrap();
            (ds.host, ds.port)
        };
        assert_eq!(
            host("jdbc:postgresql://[::1]/picsou"),
            ("::1".to_owned(), 5432)
        );
        assert_eq!(
            host("jdbc:postgresql://[::1]:6543/picsou"),
            ("::1".to_owned(), 6543)
        );
        assert_eq!(
            host("jdbc:postgresql://db:6543/picsou"),
            ("db".to_owned(), 6543)
        );
        assert!(Datasource::parse_jdbc("jdbc:postgresql://[::1/picsou", "u", "p").is_err());
        assert!(Datasource::parse_jdbc("jdbc:postgresql://db:port/picsou", "u", "p").is_err());
    }

    #[test]
    fn sqlite_url_parser_handles_percent_encoding_special_chars_and_windows_uris() {
        // Percent-encoded spaces, `?`, `#`, and `%` in path, plus query & fragment stripping.
        assert_eq!(
            DatabaseConfig::parse(
                "sqlite:///Users/Alice%20Smith/a%3Fb%23c%2520/picsou.db?mode=rwc#frag",
                "",
                ""
            )
            .unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "/Users/Alice Smith/a?b#c%20/picsou.db"
            )))
        );

        // Literal spaces (hand-written env var) remain supported.
        assert_eq!(
            DatabaseConfig::parse(
                "sqlite:///Users/Alice/Application Support/picsou.db#ignored",
                "",
                ""
            )
            .unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "/Users/Alice/Application Support/picsou.db"
            )))
        );

        // Standard 3-slash Windows URI (`sqlite:///C:/...`) strips the leading `/`.
        assert_eq!(
            DatabaseConfig::parse("sqlite:///C:/Users/Alice/picsou.db", "", "").unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "C:/Users/Alice/picsou.db"
            )))
        );
        assert_eq!(
            DatabaseConfig::parse(
                "sqlite:///C%3A/Users/Alice%20B/a%3Fb%23c%25/picsou.db?cache=shared",
                "",
                ""
            )
            .unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "C:/Users/Alice B/a?b#c%/picsou.db"
            )))
        );
        assert_eq!(
            DatabaseConfig::parse("sqlite://C:/Users/Alice/picsou.db", "", "").unwrap(),
            DatabaseConfig::Sqlite(SqliteTarget::File(PathBuf::from(
                "C:/Users/Alice/picsou.db"
            )))
        );
    }

    #[test]
    fn desktop_mode_requires_loopback_server_address() {
        // Default SERVER_ADDRESS (0.0.0.0) is rejected in desktop mode.
        let mut env = base_env();
        env.set("APP_DESKTOP_MODE", "true");
        let err = Config::load(&env).unwrap_err().to_string();
        assert!(
            err.contains("loopback SERVER_ADDRESS"),
            "unexpected error: {err}"
        );

        // Explicit non-loopback addresses (IPv4/IPv6) are rejected.
        for bad in ["0.0.0.0", "192.168.1.42", "::", "10.0.0.1"] {
            let mut env = base_env();
            env.set("APP_DESKTOP_MODE", "true");
            env.set("SERVER_ADDRESS", bad);
            assert!(
                Config::load(&env).is_err(),
                "expected {bad} to be rejected in desktop mode"
            );
        }

        // Loopback addresses (127.0.0.1, ::1, localhost) are accepted.
        for good in ["127.0.0.1", "::1", "localhost", "LOCALHOST"] {
            let mut env = base_env();
            env.set("APP_DESKTOP_MODE", "true");
            env.set("SERVER_ADDRESS", good);
            let cfg = Config::load(&env).unwrap();
            assert!(cfg.desktop_mode);
            assert!(cfg.server_address.is_loopback(), "{good}");
        }

        // Non-desktop mode still defaults to 0.0.0.0.
        let non_desktop = Config::load(&base_env()).unwrap();
        assert!(!non_desktop.desktop_mode);
        assert_eq!(non_desktop.server_address, IpAddr::from([0, 0, 0, 0]));
    }

    #[test]
    fn desktop_token_and_legacy_postgres_flags_parse_cleanly() {
        let mut env = base_env();
        env.set("APP_DESKTOP_MODE", "true");
        env.set("SERVER_ADDRESS", "127.0.0.1");
        env.set("APP_DESKTOP_TOKEN", "   ");
        let cfg = Config::load(&env).unwrap();
        assert!(cfg.desktop_token.is_none());
        assert!(!cfg.desktop_legacy_postgres);

        env.set("APP_DESKTOP_TOKEN", "  deadbeef0123  ");
        env.set("APP_DESKTOP_LEGACY_POSTGRES", "true");
        let cfg = Config::load(&env).unwrap();
        assert_eq!(cfg.desktop_token.as_deref(), Some("deadbeef0123"));
        assert!(cfg.desktop_legacy_postgres);
    }
}
