//! AuthCookieWriter: the five session cookies with identical attributes, written as Set-Cookie
//! headers in the order the Java code adds them.

use axum::http::HeaderValue;
use axum::http::header::SET_COOKIE;
use axum::response::{IntoResponseParts, ResponseParts};

use crate::auth::jwt::Jwt;

pub const ACCESS: &str = "access_token";
pub const REFRESH: &str = "refresh_token";
pub const MFA_CHALLENGE: &str = "mfa_challenge_token";
pub const PERSISTENT: &str = "persistent_token";
pub const REAUTH_PROOF: &str = "reauth_proof";

/// Set-Cookie headers to add to a response.
#[derive(Debug, Clone, Default)]
pub struct Cookies {
    secure: bool,
    headers: Vec<String>,
}

impl Cookies {
    pub fn new(secure: bool) -> Self {
        Self {
            secure,
            headers: Vec::new(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.headers.is_empty()
    }

    pub fn headers(&self) -> &[String] {
        &self.headers
    }

    /// None omits Max-Age, giving a browser-session cookie.
    pub fn add(&mut self, name: &str, value: &str, max_age: Option<u64>) -> &mut Self {
        let max_age = max_age
            .map(|m| format!(" Max-Age={m};"))
            .unwrap_or_default();
        let secure = if self.secure { "; Secure" } else { "" };
        self.headers.push(format!(
            "{name}={value};{max_age} Path=/; HttpOnly; SameSite=Lax{secure}"
        ));
        self
    }

    /// access_token and refresh_token; without Remember Me both are browser-session cookies.
    pub fn access_and_refresh(
        &mut self,
        jwt: &Jwt,
        access: &str,
        refresh: &str,
        persistent: bool,
    ) -> &mut Self {
        self.add(ACCESS, access, persistent.then(|| jwt.access_max_age()));
        self.add(REFRESH, refresh, persistent.then(|| jwt.refresh_max_age()))
    }

    pub fn mfa_challenge(&mut self, jwt: &Jwt, token: &str) -> &mut Self {
        self.add(MFA_CHALLENGE, token, Some(jwt.mfa_challenge_max_age()))
    }

    pub fn clear_mfa_challenge(&mut self) -> &mut Self {
        self.add(MFA_CHALLENGE, "", Some(0))
    }

    pub fn reauth_proof(&mut self, jwt: &Jwt, token: &str) -> &mut Self {
        self.add(REAUTH_PROOF, token, Some(jwt.reauth_proof_max_age()))
    }

    pub fn clear_reauth_proof(&mut self) -> &mut Self {
        self.add(REAUTH_PROOF, "", Some(0))
    }

    pub fn persistent(&mut self, value: &str, max_age: u64) -> &mut Self {
        self.add(PERSISTENT, value, Some(max_age))
    }

    pub fn clear_persistent(&mut self) -> &mut Self {
        self.add(PERSISTENT, "", Some(0))
    }

    /// Access, refresh and persistent; the MFA challenge stays.
    pub fn clear_session(&mut self) -> &mut Self {
        self.add(ACCESS, "", Some(0))
            .add(REFRESH, "", Some(0))
            .add(PERSISTENT, "", Some(0))
    }

    pub fn clear_all(&mut self) -> &mut Self {
        self.add(ACCESS, "", Some(0))
            .add(REFRESH, "", Some(0))
            .add(MFA_CHALLENGE, "", Some(0))
            .add(PERSISTENT, "", Some(0))
            .add(REAUTH_PROOF, "", Some(0))
    }
}

impl IntoResponseParts for Cookies {
    type Error = std::convert::Infallible;

    fn into_response_parts(self, mut res: ResponseParts) -> Result<ResponseParts, Self::Error> {
        for header in self.headers {
            if let Ok(value) = HeaderValue::from_str(&header) {
                res.headers_mut().append(SET_COOKIE, value);
            }
        }
        Ok(res)
    }
}

/// The value of a request cookie by name, the first one when repeated.
pub fn read(headers: &http::HeaderMap, name: &str) -> Option<String> {
    headers
        .get_all(http::header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|pair| pair.trim().split_once('='))
        .find(|(n, _)| *n == name)
        .map(|(_, v)| v.trim_matches('"').to_owned())
}
