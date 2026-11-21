//! Enable Banking for the setup wizard: the configuration resolver (EnableBankingConfigProvider),
//! the RSA key pair on disk (EnableBankingKeyPairService) and the connection test
//! (IntegrationsHealthService.testEnableBanking).

use std::path::{Path, PathBuf};
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey, EncodePublicKey};
use rsa::{RsaPrivateKey, RsaPublicKey};
use serde::Serialize;

use crate::AppState;
use crate::error::AppError;
use crate::setup_auth::settings;

const RSA_BITS: usize = 2048;
const TEST_TIMEOUT: Duration = Duration::from_secs(8);

/// Configuration read from app_setting first, the environment second.
pub struct EnableBankingSettings<'a> {
    state: &'a AppState,
}

impl<'a> EnableBankingSettings<'a> {
    pub fn new(state: &'a AppState) -> Self {
        Self { state }
    }

    async fn resolve(&self, key: &str, env: &str) -> Result<Option<String>, AppError> {
        let stored = settings::read(self.state, key)
            .await?
            .filter(|v| !v.trim().is_empty());
        Ok(stored.or_else(|| (!env.trim().is_empty()).then(|| env.to_owned())))
    }

    pub async fn application_id(&self) -> Result<Option<String>, AppError> {
        self.resolve(
            settings::ENABLEBANKING_APP_ID,
            &self.state.config.enablebanking.application_id,
        )
        .await
    }

    /// The kid is the application id unless a key id was configured separately.
    pub async fn key_id(&self) -> Result<Option<String>, AppError> {
        match self
            .resolve(
                settings::ENABLEBANKING_KEY_ID,
                &self.state.config.enablebanking.key_id,
            )
            .await?
        {
            Some(id) => Ok(Some(id)),
            None => self.application_id().await,
        }
    }

    pub async fn redirect_uri(&self) -> Result<Option<String>, AppError> {
        self.resolve(
            settings::ENABLEBANKING_REDIRECT_URI,
            &self.state.config.enablebanking.redirect_uri,
        )
        .await
    }

    pub fn private_key_path(&self) -> PathBuf {
        PathBuf::from(&self.state.config.enablebanking.private_key_path)
    }

    /// The wizard's key file, else the inline ENABLEBANKING_PRIVATE_KEY PEM.
    pub fn private_key(&self) -> Result<Option<RsaPrivateKey>, AppError> {
        let path = self.private_key_path();
        let pem = if path.exists() {
            std::fs::read_to_string(&path).map_err(|_| {
                AppError::Internal(format!(
                    "Failed to read private key from {}",
                    path.display()
                ))
            })?
        } else if !self
            .state
            .config
            .enablebanking
            .private_key
            .trim()
            .is_empty()
        {
            self.state
                .config
                .enablebanking
                .private_key
                .replace("\\n", "\n")
        } else {
            return Ok(None);
        };
        parse_pkcs8_pem(&pem).map(Some).map_err(|_| {
            AppError::Internal("Enable Banking private key is not a valid PKCS8 PEM.".into())
        })
    }

    pub fn private_key_present(&self) -> bool {
        self.private_key_path().exists()
            || !self
                .state
                .config
                .enablebanking
                .private_key
                .trim()
                .is_empty()
    }
}

fn parse_pkcs8_pem(pem: &str) -> Result<RsaPrivateKey, String> {
    let cleaned: String = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect();
    let der = STANDARD
        .decode(cleaned)
        .map_err(|_| "Unable to decode key".to_owned())?;
    RsaPrivateKey::from_pkcs8_der(&der).map_err(|_| "Unable to decode key".to_owned())
}

/// Java's toPem: base64 in 64-character lines, each ending with a newline.
fn to_pem(der: &[u8], label: &str) -> String {
    let b64 = STANDARD.encode(der);
    let mut out = format!("-----BEGIN {label}-----\n");
    for chunk in b64.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(chunk).unwrap_or_default());
        out.push('\n');
    }
    out.push_str(&format!("-----END {label}-----\n"));
    out
}

fn public_pem(key: &RsaPrivateKey) -> Result<String, AppError> {
    let der = RsaPublicKey::from(key)
        .to_public_key_der()
        .map_err(AppError::internal)?;
    Ok(to_pem(der.as_bytes(), "PUBLIC KEY"))
}

fn write_private(path: &Path, pem: &str) -> std::io::Result<()> {
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
        && !parent.exists()
    {
        std::fs::create_dir_all(parent)?;
        set_mode(parent, 0o700);
    }
    std::fs::write(path, pem)?;
    set_mode(path, 0o600);
    Ok(())
}

fn set_mode(path: &Path, mode: u32) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode));
    }
    #[cfg(not(unix))]
    let _ = (path, mode);
}

fn derive_public_from_file(path: &Path) -> Result<String, AppError> {
    let failed = || {
        AppError::Internal(format!(
            "Failed to derive public key from existing private key at {}. The file may be corrupted; delete it to regenerate (invalidates any uploaded public key).",
            path.display()
        ))
    };
    let pem = std::fs::read_to_string(path).map_err(|_| failed())?;
    let key = parse_pkcs8_pem(&pem).map_err(|_| failed())?;
    public_pem(&key)
}

/// The public PEM of the stored pair, generating a pair only when none exists. The second value
/// is true when a new pair was written.
pub async fn get_or_generate_public_pem(state: &AppState) -> Result<(String, bool), AppError> {
    let path = EnableBankingSettings::new(state).private_key_path();
    if path.exists() {
        tracing::info!(
            "Enable Banking private key already present — returning existing public half"
        );
        return Ok((derive_public_from_file(&path)?, false));
    }
    let key =
        tokio::task::spawn_blocking(|| RsaPrivateKey::new(&mut rsa::rand_core::OsRng, RSA_BITS))
            .await
            .map_err(AppError::internal)?
            .map_err(AppError::internal)?;
    let der = key.to_pkcs8_der().map_err(AppError::internal)?;
    write_private(&path, &to_pem(der.as_bytes(), "PRIVATE KEY")).map_err(|_| {
        AppError::Internal(format!(
            "Failed to generate Enable Banking key pair at {}",
            path.display()
        ))
    })?;
    tracing::info!(
        "Enable Banking key pair generated and persisted to {}",
        path.display()
    );
    Ok((public_pem(&key)?, true))
}

/// Imports a PKCS#8 PEM; the error is the 422 detail shown to the user.
pub fn import_private_key(state: &AppState, pem: &str) -> Result<Result<String, String>, AppError> {
    if pem.trim().is_empty() {
        return Ok(Err(
            "The key file is empty. Paste your private key and try again.".into(),
        ));
    }
    let trimmed = pem.trim();
    if trimmed.contains("-----BEGIN RSA PRIVATE KEY-----") {
        return Ok(Err("PKCS#1 format (BEGIN RSA PRIVATE KEY) is not supported. Convert to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem".into()));
    }
    if !trimmed.contains("-----BEGIN PRIVATE KEY-----") {
        return Ok(Err(
            "Not a valid PKCS#8 private key PEM (expected -----BEGIN PRIVATE KEY-----).".into(),
        ));
    }
    if let Err(reason) = parse_pkcs8_pem(trimmed) {
        return Ok(Err(format!(
            "The PEM could not be parsed as a valid RSA PKCS#8 private key: {reason}"
        )));
    }
    let path = EnableBankingSettings::new(state).private_key_path();
    write_private(&path, trimmed).map_err(|_| {
        AppError::Internal(
            "Could not save the private key on the server. Check the server's file permissions and try again.".into(),
        )
    })?;
    tracing::info!("Enable Banking private key imported from external PEM — public half derived");
    Ok(Ok(derive_public_from_file(&path)?))
}

#[derive(Debug, Serialize)]
pub struct TestResponse {
    pub ok: bool,
    pub code: &'static str,
    pub hint: String,
}

impl TestResponse {
    fn failure(code: &'static str, hint: impl Into<String>) -> Self {
        Self {
            ok: false,
            code,
            hint: hint.into(),
        }
    }
}

#[derive(Serialize)]
struct TestClaims<'a> {
    iss: &'a str,
    aud: &'a str,
    iat: i64,
    exp: i64,
}

/// Spring's HttpStatus toString: "404 NOT_FOUND".
fn status_name(status: reqwest::StatusCode) -> String {
    match status.canonical_reason() {
        Some(reason) => format!(
            "{} {}",
            status.as_u16(),
            reason.to_ascii_uppercase().replace([' ', '-'], "_")
        ),
        None => status.as_u16().to_string(),
    }
}

pub async fn test_connection(state: &AppState) -> Result<TestResponse, AppError> {
    let settings = EnableBankingSettings::new(state);
    let Some(app_id) = settings.application_id().await? else {
        return Ok(TestResponse::failure(
            "invalid_application_id",
            "Application ID is missing. Fill it in on the previous step.",
        ));
    };
    let Some(key_id) = settings.key_id().await? else {
        return Ok(TestResponse::failure(
            "invalid_key_id",
            "Key ID is missing. Fill it in on the previous step.",
        ));
    };
    let Some(key) = settings.private_key()? else {
        return Ok(TestResponse::failure(
            "public_key_not_uploaded",
            "No private key on the server yet. Go back one step to generate the key pair.",
        ));
    };
    let der = key.to_pkcs8_der().map_err(AppError::internal)?;
    let encoding =
        jsonwebtoken::EncodingKey::from_rsa_pem(to_pem(der.as_bytes(), "PRIVATE KEY").as_bytes())
            .map_err(AppError::internal)?;
    let now = state.clock.now().timestamp();
    let mut header = jsonwebtoken::Header::new(jsonwebtoken::Algorithm::RS256);
    header.typ = None;
    header.kid = Some(key_id);
    let claims = TestClaims {
        iss: &app_id,
        aud: "api.enablebanking.com",
        iat: now,
        exp: now + 60,
    };
    let jwt = jsonwebtoken::encode(&header, &claims, &encoding).map_err(AppError::internal)?;
    let url = format!(
        "{}/aspsps",
        state.config.enablebanking.base_url.trim_end_matches('/')
    );
    let sent = state
        .http
        .get(url)
        .query(&[("country", "FR")])
        .bearer_auth(jwt)
        .timeout(TEST_TIMEOUT)
        .send()
        .await;
    Ok(match sent {
        Ok(response) if response.status().is_success() => {
            tracing::info!("setup.integration.enablebanking.test ok");
            TestResponse {
                ok: true,
                code: "ok",
                hint: "Enable Banking is ready to use.".into(),
            }
        }
        Ok(response) if response.status() == reqwest::StatusCode::UNAUTHORIZED => {
            tracing::info!("setup.integration.enablebanking.test failed code=invalid_key_id");
            TestResponse::failure(
                "invalid_key_id",
                "Enable Banking rejected the JWT. Double-check your Application ID and Key ID, and make sure the public key has been uploaded to the Enable Banking dashboard.",
            )
        }
        Ok(response) if response.status() == reqwest::StatusCode::FORBIDDEN => {
            TestResponse::failure(
                "public_key_not_uploaded",
                "Your public key does not match any key on the Enable Banking dashboard. Go back one step, copy the public key, and paste it in your Enable Banking app.",
            )
        }
        Ok(response) => {
            let status = response.status();
            tracing::warn!("setup.integration.enablebanking.test http_error status={status}");
            TestResponse::failure(
                "unknown",
                format!(
                    "Enable Banking returned {}. {}",
                    status_name(status),
                    status.canonical_reason().unwrap_or_default()
                ),
            )
        }
        Err(e) => {
            tracing::warn!("setup.integration.enablebanking.test network_error: {e}");
            TestResponse::failure(
                "network",
                "Could not reach Enable Banking. Check your network and retry.",
            )
        }
    })
}
