//! JwtUtil: HS-signed tokens with the Java claims. jjwt picks the HMAC strength from the key
//! length (HS512 from 64 bytes, HS384 from 48, else HS256), so the same secret yields the same
//! algorithm in both backends; any of the three is accepted on input.

use std::time::Duration;

use chrono::{DateTime, Utc};
use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use serde_with::skip_serializing_none;
use uuid::Uuid;

use crate::auth::users::AppUser;
use crate::clock::AppClock;
use crate::config::JwtConfig;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenType {
    Access,
    Refresh,
    MfaChallenge,
    Reauth,
}

impl TokenType {
    fn as_str(self) -> &'static str {
        match self {
            Self::Access => "access",
            Self::Refresh => "refresh",
            Self::MfaChallenge => "mfa_challenge",
            Self::Reauth => "reauth",
        }
    }
}

/// The claims a Picsou token may carry, in jjwt's emission order.
#[skip_serializing_none]
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Claims {
    pub sub: Option<String>,
    pub uid: Option<i64>,
    pub role: Option<String>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
    pub remember_me: Option<bool>,
    pub tv: Option<i64>,
    pub iat: Option<i64>,
    pub exp: Option<i64>,
    pub sid: Option<String>,
    pub sso_at: Option<i64>,
    pub nbf: Option<i64>,
    pub aud: Option<Value>,
    pub iss: Option<String>,
    /// A space-delimited string on MCP tokens, an array on the authorization server's first-party
    /// tokens; both read as the space-delimited form.
    #[serde(default, deserialize_with = "scope_text")]
    pub scope: Option<String>,
}

fn scope_text<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    Ok(match Option::<Value>::deserialize(deserializer)? {
        Some(Value::String(s)) => Some(s),
        Some(Value::Array(items)) => Some(
            items
                .iter()
                .filter_map(Value::as_str)
                .collect::<Vec<_>>()
                .join(" "),
        ),
        _ => None,
    })
}

impl Claims {
    pub fn is(&self, kind: TokenType) -> bool {
        self.kind.as_deref() == Some(kind.as_str())
    }

    /// The remember-me series a refresh token is bound to; None when absent or malformed.
    pub fn series_id(&self) -> Option<Uuid> {
        self.sid.as_deref().and_then(|s| Uuid::parse_str(s).ok())
    }

    pub fn sso_authenticated_at(&self) -> Option<DateTime<Utc>> {
        self.sso_at.and_then(|s| DateTime::from_timestamp(s, 0))
    }

    /// A token minted by the OAuth2 authorization server (the native app), not by a web sign-in.
    pub fn is_authorization_server_token(&self) -> bool {
        self.aud.is_some() || self.iss.is_some()
    }

    pub fn audience_contains(&self, audience: &str) -> bool {
        match &self.aud {
            Some(Value::String(a)) => a == audience,
            Some(Value::Array(list)) => list.iter().any(|a| a.as_str() == Some(audience)),
            _ => false,
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum JwtError {
    #[error("invalid token")]
    Invalid,
    #[error("expired token")]
    Expired,
}

#[derive(Clone)]
pub struct Jwt {
    algorithm: Algorithm,
    encoding: EncodingKey,
    decoding: DecodingKey,
    config: JwtConfig,
    clock: AppClock,
}

impl std::fmt::Debug for Jwt {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Jwt")
            .field("algorithm", &self.algorithm)
            .finish_non_exhaustive()
    }
}

impl Jwt {
    pub fn new(config: &JwtConfig, clock: AppClock) -> Result<Self, jsonwebtoken::errors::Error> {
        let key = config.secret.as_bytes();
        let algorithm = match key.len() {
            64.. => Algorithm::HS512,
            48.. => Algorithm::HS384,
            _ => Algorithm::HS256,
        };
        Ok(Self {
            algorithm,
            encoding: EncodingKey::from_secret(key)?,
            decoding: DecodingKey::from_secret(key)?,
            config: config.clone(),
            clock,
        })
    }

    pub fn config(&self) -> &JwtConfig {
        &self.config
    }

    pub fn sign(&self, claims: &Claims) -> String {
        let header = Header {
            typ: None,
            ..Header::new(self.algorithm)
        };
        jsonwebtoken::encode(&header, claims, &self.encoding).unwrap_or_default()
    }

    fn base(&self, user: &AppUser, kind: TokenType, ttl: Duration) -> Claims {
        let now = self.clock.now().timestamp();
        Claims {
            sub: Some(user.username.clone()),
            uid: Some(user.id),
            role: Some(user.role.as_str().to_owned()),
            kind: Some(kind.as_str().to_owned()),
            tv: Some(user.token_version),
            iat: Some(now),
            exp: Some(now + i64::try_from(ttl.as_secs()).unwrap_or(i64::MAX / 2)),
            ..Claims::default()
        }
    }

    pub fn access_token(&self, user: &AppUser, sso_at: Option<DateTime<Utc>>) -> String {
        let mut claims = self.base(user, TokenType::Access, self.config.access_expiry);
        claims.sso_at = sso_at.map(|t| t.timestamp());
        self.sign(&claims)
    }

    pub fn refresh_token(
        &self,
        user: &AppUser,
        series: Option<Uuid>,
        sso_at: Option<DateTime<Utc>>,
    ) -> String {
        let mut claims = self.base(user, TokenType::Refresh, self.config.refresh_expiry);
        claims.sid = series.map(|s| s.to_string());
        claims.sso_at = sso_at.map(|t| t.timestamp());
        self.sign(&claims)
    }

    pub fn mfa_challenge_token(&self, user: &AppUser, remember_me: bool) -> String {
        let mut claims = self.base(
            user,
            TokenType::MfaChallenge,
            self.config.mfa_challenge_expiry,
        );
        claims.remember_me = Some(remember_me);
        claims.tv = None;
        self.sign(&claims)
    }

    pub fn reauth_proof(&self, user: &AppUser) -> String {
        let mut claims = self.base(user, TokenType::Reauth, self.config.reauth_proof_expiry);
        claims.role = None;
        self.sign(&claims)
    }

    /// Verifies the signature and the expiry against the injected clock.
    pub fn parse(&self, token: &str) -> Result<Claims, JwtError> {
        let mut validation = Validation::new(self.algorithm);
        validation.algorithms = vec![Algorithm::HS256, Algorithm::HS384, Algorithm::HS512];
        validation.validate_exp = false;
        validation.validate_nbf = false;
        validation.validate_aud = false;
        validation.required_spec_claims.clear();
        let data = jsonwebtoken::decode::<Claims>(token, &self.decoding, &validation)
            .map_err(|_| JwtError::Invalid)?;
        let now = self.clock.now().timestamp_millis();
        if data
            .claims
            .exp
            .is_some_and(|exp| now > exp.saturating_mul(1000))
        {
            return Err(JwtError::Expired);
        }
        if data
            .claims
            .nbf
            .is_some_and(|nbf| now < nbf.saturating_mul(1000))
        {
            return Err(JwtError::Invalid);
        }
        Ok(data.claims)
    }

    pub fn access_max_age(&self) -> u64 {
        self.config.access_expiry.as_secs()
    }

    pub fn refresh_max_age(&self) -> u64 {
        self.config.refresh_expiry.as_secs()
    }

    pub fn mfa_challenge_max_age(&self) -> u64 {
        self.config.mfa_challenge_expiry.as_secs()
    }

    pub fn reauth_proof_max_age(&self) -> u64 {
        self.config.reauth_proof_expiry.as_secs()
    }
}
