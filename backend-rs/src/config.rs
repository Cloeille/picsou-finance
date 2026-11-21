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
}

impl Datasource {
    pub fn parse_jdbc(url: &str, username: &str, password: &str) -> Result<Self, ConfigError> {
        let rest = url
            .strip_prefix("jdbc:postgresql://")
            .ok_or_else(|| invalid("SPRING_DATASOURCE_URL must start with jdbc:postgresql://"))?;
        let rest = rest.split('?').next().unwrap_or_default();
        let (authority, database) = rest
            .split_once('/')
            .ok_or_else(|| invalid("SPRING_DATASOURCE_URL must name a database"))?;
        let (host, port) = match authority.rsplit_once(':') {
            Some((host, port)) if !host.ends_with(']') || authority.starts_with('[') => (
                host.trim_start_matches('[')
                    .trim_end_matches(']')
                    .to_owned(),
                port.parse::<u16>()
                    .map_err(|_| invalid("SPRING_DATASOURCE_URL has an invalid port"))?,
            ),
            _ => (
                authority
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .to_owned(),
                5432,
            ),
        };
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
    /// On only when the issuer and the client id are both set, as SsoProperties decides.
    pub fn enabled(&self) -> bool {
        !self.issuer_uri.trim().is_empty() && !self.client_id.trim().is_empty()
    }

    pub fn admin_group_configured(&self) -> bool {
        self.enabled() && !self.admin_group.trim().is_empty()
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
    pub timezone: Tz,
    pub app_version: String,
    pub desktop: String,
    pub desktop_unavailable: String,
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
    pub mcp_enabled: bool,
    pub mcp_protocol: String,
    pub mcp_keep_alive_interval: String,
    pub telemetry_dsn: String,
    pub telemetry_environment: String,
    pub valuation_enabled: bool,
    pub cerema_api_url: String,
    pub insee_api_url: String,
    pub geocoding_api_url: String,
    pub update_check_enabled: bool,
    pub meria_base_url: String,
    /// The DuckDuckGo icon service Java hard-codes; overridable so tests can stub it.
    pub duckduckgo_icons_url: String,
    pub startup_sync_enabled: bool,
    pub instrument_logos_enabled: bool,
    pub dev_profile: bool,
}

/// Sidecar compose service name, its URL variable, the production default and the dev default.
pub const SIDECARS: &[(&str, &str, &str, &str)] = &[
    (
        "tr-auth",
        "TR_AUTH_URL",
        "http://tr-auth:8001",
        "http://127.0.0.1:8001",
    ),
    (
        "bourso-auth",
        "BOURSO_AUTH_URL",
        "http://bourso-auth:8001",
        "http://127.0.0.1:8004",
    ),
    (
        "caisse-epargne-auth",
        "CAISSE_EPARGNE_AUTH_URL",
        "http://caisse-epargne-auth:8001",
        "http://127.0.0.1:8008",
    ),
    (
        "banque-populaire-auth",
        "BANQUE_POPULAIRE_AUTH_URL",
        "http://banque-populaire-auth:8001",
        "http://127.0.0.1:8014",
    ),
    (
        "lbp-auth",
        "LBP_AUTH_URL",
        "http://lbp-auth:8001",
        "http://127.0.0.1:8015",
    ),
    (
        "revolut-auth",
        "REVOLUT_AUTH_URL",
        "http://revolut-auth:8002",
        "http://127.0.0.1:8010",
    ),
    (
        "bourse-direct-auth",
        "BOURSE_DIRECT_AUTH_URL",
        "http://bourse-direct-auth:8001",
        "http://127.0.0.1:8002",
    ),
    (
        "degiro-auth",
        "DEGIRO_AUTH_URL",
        "http://degiro-auth:8001",
        "http://127.0.0.1:8011",
    ),
    (
        "amundi-auth",
        "AMUNDI_AUTH_URL",
        "http://amundi-auth:8001",
        "http://127.0.0.1:8003",
    ),
    (
        "cmes-auth",
        "CMES_AUTH_URL",
        "http://cmes-auth:8001",
        "http://127.0.0.1:8009",
    ),
    (
        "cic-auth",
        "CIC_AUTH_URL",
        "http://cic-auth:8001",
        "http://127.0.0.1:8012",
    ),
    (
        "natixis-auth",
        "NATIXIS_AUTH_URL",
        "http://natixis-auth:8001",
        "http://127.0.0.1:8016",
    ),
    (
        "bnp-auth",
        "BNP_AUTH_URL",
        "http://bnp-auth:8001",
        "http://127.0.0.1:8017",
    ),
    (
        "amex-auth",
        "AMEX_AUTH_URL",
        "http://amex-auth:8001",
        "http://127.0.0.1:8013",
    ),
    (
        "fortuneo-auth",
        "FORTUNEO_AUTH_URL",
        "http://fortuneo-auth:8001",
        "http://127.0.0.1:8005",
    ),
    (
        "corum-auth",
        "CORUM_AUTH_URL",
        "http://corum-auth:8001",
        "http://127.0.0.1:8006",
    ),
    (
        "sofidy-auth",
        "SOFIDY_AUTH_URL",
        "http://sofidy-auth:8001",
        "http://127.0.0.1:8007",
    ),
];

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
    pub fn load(env: &Env) -> Result<Self, ConfigError> {
        let secrets = secrets::bootstrap(env).map_err(|e| invalid(e.to_string()))?;
        Self::from_resolved(env, &secrets)
    }

    fn from_resolved(env: &Env, secrets: &secrets::Resolved) -> Result<Self, ConfigError> {
        let dev = env
            .get("SPRING_PROFILES_ACTIVE")
            .is_some_and(|p| p.split(',').any(|p| p.trim() == "dev"));

        let datasource = Datasource::parse_jdbc(
            env.or(
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://localhost:5432/picsou",
            ),
            env.or("SPRING_DATASOURCE_USERNAME", "picsou"),
            &secrets.datasource_password,
        )?;

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

        let sidecars = SIDECARS
            .iter()
            .map(|(name, var, prod, dev_url)| {
                (
                    *name,
                    env.or(var, if dev { dev_url } else { prod }).to_owned(),
                )
            })
            .collect();

        let log_file = env
            .non_blank("LOGGING_FILE_NAME")
            .map(PathBuf::from)
            .or_else(|| {
                env.non_blank("LOGGING_FILE_PATH")
                    .map(|dir| PathBuf::from(dir).join("spring.log"))
            });

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
                .get("SSO_SESSION_REVALIDATE_AFTER")
                .map(|v| {
                    parse_duration(v, Duration::from_millis(1))
                        .ok_or_else(|| invalid("SSO_SESSION_REVALIDATE_AFTER must be a duration"))
                })
                .transpose()?
                .unwrap_or(Duration::from_secs(24 * 3600)),
            idp_logout: bool_or(env, "SSO_IDP_LOGOUT", false)?,
        };

        Ok(Self {
            server_address: env
                .non_blank("SERVER_ADDRESS")
                .map(|a| {
                    a.parse()
                        .map_err(|_| invalid("SERVER_ADDRESS must be an IP address"))
                })
                .transpose()?
                .unwrap_or(IpAddr::from([0, 0, 0, 0])),
            server_port: env
                .non_blank("SERVER_PORT")
                .map(|p| {
                    p.parse()
                        .map_err(|_| invalid("SERVER_PORT must be a port number"))
                })
                .transpose()?
                .unwrap_or(8080),
            datasource,
            timezone: parse_timezone(env.get("APP_TIMEZONE"))?,
            app_version: env.or("APP_VERSION", "dev").to_owned(),
            desktop: env.or("PICSOU_DESKTOP", "false").to_owned(),
            desktop_unavailable: env.or("PICSOU_DESKTOP_UNAVAILABLE", "").to_owned(),
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
            update_check_enabled: bool_or(env, "APP_UPDATE_CHECK_ENABLED", true)?,
            meria_base_url: env
                .or("MERIA_BASE_URL", "https://api.meria.com/v1")
                .to_owned(),
            duckduckgo_icons_url: env
                .or("DUCKDUCKGO_ICONS_URL", "https://icons.duckduckgo.com")
                .to_owned(),
            startup_sync_enabled: bool_or(env, "APP_STARTUP_SYNC_ENABLED", true)?,
            instrument_logos_enabled: bool_or(env, "INSTRUMENT_LOGOS_ENABLED", true)?,
            dev_profile: dev,
        })
    }

    /// The URL of a connector sidecar by its compose service name.
    pub fn sidecar_url(&self, name: &str) -> Option<&str> {
        self.sidecars.get(name).map(String::as_str)
    }
}
