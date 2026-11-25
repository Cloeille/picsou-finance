//! A fake OIDC provider on wiremock: discovery, JWKS, a token endpoint that checks PKCE and
//! signs RS256 ID tokens, userinfo. Codes are issued by the test, as the IdP's login page would.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{DateTime, Utc};
use jsonwebtoken::{Algorithm, EncodingKey, Header};
use rsa::RsaPrivateKey;
use rsa::pkcs8::{EncodePrivateKey, LineEnding};
use rsa::traits::PublicKeyParts;
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Fault {
    BadSignature,
    WrongAudience,
    WrongIssuer,
    Expired,
}

struct Keys {
    pem: String,
    jwk: Value,
    other_pem: String,
}

fn keys() -> &'static Keys {
    static KEYS: OnceLock<Keys> = OnceLock::new();
    KEYS.get_or_init(|| {
        let mut rng = rsa::rand_core::OsRng;
        let key = RsaPrivateKey::new(&mut rng, 2048).expect("rsa key");
        let other = RsaPrivateKey::new(&mut rng, 2048).expect("rsa key");
        let b64 = |b: Vec<u8>| URL_SAFE_NO_PAD.encode(b);
        Keys {
            pem: key.to_pkcs8_pem(LineEnding::LF).expect("pem").to_string(),
            other_pem: other.to_pkcs8_pem(LineEnding::LF).expect("pem").to_string(),
            jwk: json!({"kty": "RSA", "kid": "test-key", "use": "sig", "alg": "RS256",
                "n": b64(key.n().to_bytes_be()), "e": b64(key.e().to_bytes_be())}),
        }
    })
}

pub fn s256(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

struct Grant {
    params: HashMap<String, String>,
    subject: String,
    claims: Map<String, Value>,
    auth_time: DateTime<Utc>,
}

#[derive(Default)]
struct State {
    grants: HashMap<String, Grant>,
    exchanges: HashMap<String, HashMap<String, String>>,
    userinfo: HashMap<String, Value>,
    calls: HashMap<String, usize>,
    tamper_verifier: bool,
    tamper_nonce: bool,
    fault: Option<Fault>,
    now: Option<DateTime<Utc>>,
    counter: u64,
}

#[derive(Clone)]
pub struct FakeIdp {
    pub server: Arc<MockServer>,
    state: Arc<Mutex<State>>,
}

fn form(body: &[u8]) -> HashMap<String, String> {
    url::form_urlencoded::parse(body).into_owned().collect()
}

struct TokenEndpoint(FakeIdp);

impl Respond for TokenEndpoint {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let idp = &self.0;
        let issuer = idp.issuer();
        let mut st = idp.state.lock().unwrap();
        *st.calls.entry("/token".into()).or_default() += 1;
        let params = form(&request.body);
        let code = params.get("code").cloned().unwrap_or_default();
        let Some(grant) = st.grants.remove(&code) else {
            return ResponseTemplate::new(400).set_body_json(json!({"error": "invalid_grant"}));
        };
        st.exchanges.insert(code.clone(), params.clone());
        let verifier = params.get("code_verifier").cloned().unwrap_or_default();
        let tampered = std::mem::take(&mut st.tamper_verifier);
        if tampered || grant.params.get("code_challenge") != Some(&s256(&verifier)) {
            return ResponseTemplate::new(400).set_body_json(json!({"error": "invalid_grant"}));
        }
        let now = st.now.unwrap_or_else(Utc::now).timestamp();
        let fault = st.fault.take();
        let mut nonce = grant.params.get("nonce").cloned().unwrap_or_default();
        if std::mem::take(&mut st.tamper_nonce) {
            nonce.push('x');
        }
        let mut claims = grant.claims.clone();
        claims.insert(
            "iss".into(),
            if fault == Some(Fault::WrongIssuer) {
                "https://other.example".into()
            } else {
                issuer.into()
            },
        );
        claims.insert("sub".into(), grant.subject.clone().into());
        claims.insert(
            "aud".into(),
            if fault == Some(Fault::WrongAudience) {
                "someone-else".into()
            } else {
                "picsou-test".into()
            },
        );
        claims.insert("iat".into(), now.into());
        claims.insert(
            "exp".into(),
            if fault == Some(Fault::Expired) {
                now - 600
            } else {
                now + 300
            }
            .into(),
        );
        claims.insert("nonce".into(), nonce.into());
        claims.insert("auth_time".into(), grant.auth_time.timestamp().into());
        let pem = if fault == Some(Fault::BadSignature) {
            &keys().other_pem
        } else {
            &keys().pem
        };
        let header = Header {
            kid: Some("test-key".into()),
            ..Header::new(Algorithm::RS256)
        };
        let id_token = jsonwebtoken::encode(
            &header,
            &claims,
            &EncodingKey::from_rsa_pem(pem.as_bytes()).unwrap(),
        )
        .unwrap();
        st.counter += 1;
        let access = format!("idp-access-{}", st.counter);
        let mut info = grant.claims.clone();
        info.insert("sub".into(), grant.subject.clone().into());
        st.userinfo.insert(access.clone(), Value::Object(info));
        ResponseTemplate::new(200).set_body_json(json!({
            "access_token": access, "token_type": "Bearer", "expires_in": 300, "id_token": id_token,
        }))
    }
}

struct UserinfoEndpoint(FakeIdp);

impl Respond for UserinfoEndpoint {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let mut st = self.0.state.lock().unwrap();
        *st.calls.entry("/userinfo".into()).or_default() += 1;
        let token = request
            .headers
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .unwrap_or_default()
            .to_owned();
        match st.userinfo.get(&token) {
            Some(info) => ResponseTemplate::new(200).set_body_json(info.clone()),
            None => ResponseTemplate::new(401),
        }
    }
}

impl FakeIdp {
    pub async fn start() -> Self {
        let server = Arc::new(MockServer::start().await);
        let idp = Self {
            server: server.clone(),
            state: Arc::default(),
        };
        let issuer = idp.issuer();
        Mock::given(method("GET"))
            .and(path("/.well-known/openid-configuration"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "issuer": issuer,
                "authorization_endpoint": format!("{issuer}/authorize"),
                "token_endpoint": format!("{issuer}/token"),
                "jwks_uri": format!("{issuer}/jwks"),
                "userinfo_endpoint": format!("{issuer}/userinfo"),
                "end_session_endpoint": format!("{issuer}/logout"),
                "response_types_supported": ["code"],
                "subject_types_supported": ["public"],
                "id_token_signing_alg_values_supported": ["RS256"],
            })))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/jwks"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(json!({"keys": [keys().jwk.clone()]})),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/token"))
            .respond_with(TokenEndpoint(idp.clone()))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/userinfo"))
            .respond_with(UserinfoEndpoint(idp.clone()))
            .mount(&server)
            .await;
        idp
    }

    pub fn issuer(&self) -> String {
        self.server.uri()
    }

    /// The instant the IdP stamps its tokens with: the app's clock.
    pub fn set_now(&self, now: DateTime<Utc>) {
        self.state.lock().unwrap().now = Some(now);
    }

    /// What the IdP's login page does: a code for the authorization request it received.
    pub fn issue_code(
        &self,
        params: HashMap<String, String>,
        subject: &str,
        claims: Value,
        auth_time: DateTime<Utc>,
    ) -> String {
        let mut st = self.state.lock().unwrap();
        st.counter += 1;
        let code = format!("code-{}", st.counter);
        st.grants.insert(
            code.clone(),
            Grant {
                params,
                subject: subject.to_owned(),
                claims: claims.as_object().cloned().unwrap_or_default(),
                auth_time,
            },
        );
        code
    }

    pub fn exchange(&self, code: &str) -> Option<HashMap<String, String>> {
        self.state.lock().unwrap().exchanges.get(code).cloned()
    }

    pub fn calls(&self, endpoint: &str) -> usize {
        self.state
            .lock()
            .unwrap()
            .calls
            .get(endpoint)
            .copied()
            .unwrap_or(0)
    }

    pub fn tamper_next_verifier(&self) {
        self.state.lock().unwrap().tamper_verifier = true;
    }

    pub fn tamper_next_nonce(&self) {
        self.state.lock().unwrap().tamper_nonce = true;
    }

    pub fn fault_next_id_token(&self, fault: Fault) {
        self.state.lock().unwrap().fault = Some(fault);
    }
}

pub fn claims(name: &str, admin: bool) -> Value {
    json!({
        "preferred_username": name,
        "email": format!("{name}@example.test"),
        "name": name,
        "groups": if admin { vec!["picsou-admin"] } else { Vec::new() },
    })
}
