//! The OAuth2 authorization server end to end: the iOS public client with PKCE, dynamic client
//! registration, consent and MCP tokens, refresh and revocation, and rows the Java backend wrote.

use std::collections::HashMap;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use picsou::testing::{Client, Response, TestApp};
use reqwest::StatusCode;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

const VERIFIER: &str = "a-deterministic-pkce-verifier-with-at-least-43-characters";
const MCP_REDIRECT: &str = "https://claude.ai/api/mcp/auth_callback";

fn challenge() -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(VERIFIER.as_bytes()))
}

fn form(pairs: &[(&str, &str)]) -> String {
    url::form_urlencoded::Serializer::new(String::new())
        .extend_pairs(pairs)
        .finish()
}

fn query(location: &str) -> HashMap<String, String> {
    let q = location.split_once('?').map(|(_, q)| q).unwrap_or_default();
    url::form_urlencoded::parse(q.as_bytes())
        .into_owned()
        .collect()
}

fn location(r: &Response) -> String {
    r.header("location").expect("a redirect").to_owned()
}

async fn authorize(client: &Client, client_id: &str, redirect: &str, scope: &str) -> Response {
    let q = form(&[
        ("response_type", "code"),
        ("client_id", client_id),
        ("redirect_uri", redirect),
        ("scope", scope),
        ("state", "xyz"),
        ("code_challenge", &challenge()),
        ("code_challenge_method", "S256"),
    ]);
    client.get(&format!("/oauth2/authorize?{q}")).send().await
}

async fn token(app: &TestApp, pairs: &[(&str, &str)]) -> Response {
    app.client()
        .post("/oauth2/token")
        .body("application/x-www-form-urlencoded", form(pairs))
        .send()
        .await
}

async fn exchange(app: &TestApp, code: &str, client_id: &str, redirect: &str) -> Response {
    token(
        app,
        &[
            ("grant_type", "authorization_code"),
            ("code", code),
            ("client_id", client_id),
            ("redirect_uri", redirect),
            ("code_verifier", VERIFIER),
        ],
    )
    .await
}

fn claims(jwt: &str) -> Value {
    let payload = jwt.split('.').nth(1).unwrap();
    serde_json::from_slice(&URL_SAFE_NO_PAD.decode(payload).unwrap()).unwrap()
}

#[tokio::test]
async fn the_ios_app_signs_in_with_pkce_and_calls_the_api() {
    let app = TestApp::with_admin().await;
    let anonymous = authorize(&app.client(), "picsou-ios", "picsou://callback", "read").await;
    assert_eq!(anonymous.status, StatusCode::FOUND);
    assert!(location(&anonymous).starts_with(&format!(
        "{}/login?redirect=%2Foauth2%2Fauthorize%3F",
        app.base_url
    )));
    let admin = app.login_admin().await;
    let granted = authorize(&admin, "picsou-ios", "picsou://callback", "read").await;
    let redirect = location(&granted);
    assert!(
        redirect.starts_with("picsou://callback?code="),
        "{redirect}"
    );
    let params = query(&redirect);
    assert_eq!(params["state"], "xyz");
    let issued = exchange(&app, &params["code"], "picsou-ios", "picsou://callback").await;
    assert_eq!(issued.status, StatusCode::OK, "{}", issued.text());
    assert_eq!(
        issued.header("content-type"),
        Some("application/json;charset=UTF-8")
    );
    let body = issued.json();
    assert_eq!(body["token_type"], "Bearer");
    assert_eq!(body["scope"], "read");
    assert_eq!(body["expires_in"], 900);
    assert!(
        body.get("refresh_token").is_none(),
        "no refresh token for a public client"
    );
    let access = body["access_token"].as_str().unwrap().to_owned();
    let c = claims(&access);
    assert_eq!(c["type"], "access");
    assert_eq!(c["aud"], "picsou-ios");
    assert_eq!(c["role"], "ADMIN");
    assert_eq!(c["scope"], json!(["read"]));
    assert_eq!(c["iss"], app.base_url);
    let header: Value = serde_json::from_slice(
        &URL_SAFE_NO_PAD
            .decode(access.split('.').next().unwrap())
            .unwrap(),
    )
    .unwrap();
    assert_eq!(header, json!({"alg": "HS256", "kid": "picsou-hs256"}));
    let api = app
        .client()
        .get("/api/auth/sign-in-methods")
        .bearer(&access)
        .send()
        .await;
    assert_eq!(api.status, StatusCode::OK, "the access token works on /api");
    let replay = exchange(&app, &params["code"], "picsou-ios", "picsou://callback").await;
    assert_eq!(replay.status, StatusCode::BAD_REQUEST);
    assert_eq!(replay.json(), json!({"error": "invalid_grant"}));
    let apps = admin.get("/api/connected-apps").send().await.json();
    assert_eq!(apps[0]["scopes"], json!(["read"]));
    assert_eq!(apps[0]["issuedAt"], "2026-01-15T10:00:00Z");
}

#[tokio::test]
async fn a_code_redeemed_twice_at_the_same_instant_yields_one_token() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let granted = authorize(&admin, "picsou-ios", "picsou://callback", "read").await;
    let code = query(&location(&granted))["code"].clone();

    let (first, second) = tokio::join!(
        exchange(&app, &code, "picsou-ios", "picsou://callback"),
        exchange(&app, &code, "picsou-ios", "picsou://callback"),
    );

    let mut statuses = [first.status, second.status];
    statuses.sort();
    assert_eq!(statuses, [StatusCode::OK, StatusCode::BAD_REQUEST]);
}

#[tokio::test]
async fn request_and_token_errors_follow_spring_authorization_server() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let unknown = authorize(&admin, "nope", "picsou://callback", "read").await;
    assert_eq!(unknown.status, StatusCode::BAD_REQUEST);
    assert_eq!(unknown.json()["path"], "/oauth2/authorize");
    let mismatch = authorize(&admin, "picsou-ios", "picsou://elsewhere", "read").await;
    assert_eq!(mismatch.status, StatusCode::BAD_REQUEST);
    let scope = authorize(&admin, "picsou-ios", "picsou://callback", "admin").await;
    assert!(location(&scope).starts_with(
        "picsou://callback?error=invalid_scope&error_description=OAuth%202.0%20Parameter%3A%20scope"
    ));
    let no_pkce = admin
        .get(&format!(
            "/oauth2/authorize?{}",
            form(&[
                ("response_type", "code"),
                ("client_id", "picsou-ios"),
                ("scope", "read")
            ])
        ))
        .send()
        .await;
    assert!(location(&no_pkce).starts_with("picsou://callback?error=invalid_request&error_description=OAuth%202.0%20Parameter%3A%20code_challenge&"));
    let code = query(&location(
        &authorize(&admin, "picsou-ios", "picsou://callback", "read").await,
    ))["code"]
        .clone();
    let wrong_verifier = token(
        &app,
        &[
            ("grant_type", "authorization_code"),
            ("code", &code),
            ("client_id", "picsou-ios"),
            ("code_verifier", "x".repeat(43).as_str()),
        ],
    )
    .await;
    assert_eq!(wrong_verifier.json(), json!({"error": "invalid_grant"}));
    let wrong_redirect = exchange(&app, &code, "picsou-ios", "picsou://wrong").await;
    assert_eq!(wrong_redirect.status, StatusCode::BAD_REQUEST);
    let unknown_client = exchange(&app, &code, "who", "picsou://callback").await;
    assert_eq!(unknown_client.status, StatusCode::UNAUTHORIZED);
    assert_eq!(unknown_client.json(), json!({"error": "invalid_client"}));
    assert_eq!(
        exchange(&app, &code, "picsou-ios", "picsou://callback")
            .await
            .status,
        StatusCode::OK,
        "errors left the code usable"
    );
    app.clock.advance(std::time::Duration::from_secs(301));
    let late = query(&location(
        &authorize(
            &app.login_admin().await,
            "picsou-ios",
            "picsou://callback",
            "read",
        )
        .await,
    ))["code"]
        .clone();
    app.clock.advance(std::time::Duration::from_secs(301));
    assert_eq!(
        exchange(&app, &late, "picsou-ios", "picsou://callback")
            .await
            .status,
        StatusCode::BAD_REQUEST,
        "codes live five minutes"
    );
}

#[tokio::test]
async fn refresh_and_revoke_never_authenticate_a_public_client() {
    let app = TestApp::with_admin().await;
    let refresh = token(
        &app,
        &[
            ("grant_type", "refresh_token"),
            ("refresh_token", "x"),
            ("client_id", "picsou-ios"),
        ],
    )
    .await;
    assert_eq!(refresh.status, StatusCode::FOUND);
    assert_eq!(
        location(&refresh),
        format!("{}/login?redirect=%2Foauth2%2Ftoken", app.base_url)
    );
    let revoke = app
        .client()
        .post("/oauth2/revoke")
        .body(
            "application/x-www-form-urlencoded",
            form(&[("token", "x"), ("client_id", "picsou-ios")]),
        )
        .send()
        .await;
    assert_eq!(
        location(&revoke),
        format!("{}/login?redirect=%2Foauth2%2Frevoke", app.base_url)
    );
    let basic = app
        .client()
        .post("/oauth2/introspect")
        .header("authorization", "Basic cGljc291LWlvczp4")
        .body("application/x-www-form-urlencoded", "token=x")
        .send()
        .await;
    assert_eq!(basic.status, StatusCode::UNAUTHORIZED);
    assert_eq!(basic.json(), json!({"error": "invalid_client"}));
}

async fn register(app: &TestApp, scope: &str) -> String {
    let response = app
        .client()
        .post("/oauth2/register")
        .json(&json!({"client_name": "Claude", "redirect_uris": [MCP_REDIRECT], "scope": scope}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::CREATED, "{}", response.text());
    let body = response.json();
    assert_eq!(body["token_endpoint_auth_method"], "none");
    assert_eq!(
        body["grant_types"],
        json!(["authorization_code", "refresh_token"])
    );
    assert_eq!(body["client_id_issued_at"], 1_768_471_200);
    body["client_id"].as_str().unwrap().to_owned()
}

#[tokio::test]
async fn an_mcp_client_registers_gets_consent_and_calls_mcp() {
    let app = TestApp::with_admin().await;
    let client_id = register(&app, "accounts:read transactions:read").await;
    let admin = app.login_admin().await;
    let parked = authorize(&admin, &client_id, MCP_REDIRECT, "accounts:read").await;
    let consent = location(&parked);
    assert!(
        consent.starts_with(&format!(
            "{}/consent?scope=accounts:read&client_id={client_id}&state=",
            app.base_url
        )),
        "{consent}"
    );
    let state = query(&consent)["state"].clone();
    let info = admin
        .get(&format!(
            "/api/oauth2/consent-info?{}",
            form(&[
                ("client_id", &client_id),
                ("scope", "accounts:read goals:read"),
                ("state", &state)
            ])
        ))
        .send()
        .await
        .json();
    assert_eq!(
        info,
        json!({"client_name": "Claude", "requested_scopes": ["accounts:read"], "state": state})
    );
    let approved = admin
        .post("/oauth2/authorize")
        .body(
            "application/x-www-form-urlencoded",
            form(&[
                ("client_id", &client_id),
                ("state", &state),
                ("scope", "accounts:read"),
            ]),
        )
        .send()
        .await;
    let back = location(&approved);
    assert!(back.starts_with(&format!("{MCP_REDIRECT}?code=")), "{back}");
    assert_eq!(query(&back)["state"], "xyz");
    let issued = exchange(&app, &query(&back)["code"], &client_id, MCP_REDIRECT)
        .await
        .json();
    let access = issued["access_token"].as_str().unwrap().to_owned();
    let c = claims(&access);
    assert_eq!(c["type"], "mcp");
    assert_eq!(c["aud"], "picsou-mcp");
    assert_eq!(c["scope"], "accounts:read");
    assert!(c.get("role").is_none());
    let mcp = app
        .client()
        .get("/mcp/anything")
        .bearer(&access)
        .send()
        .await;
    assert_ne!(
        mcp.status,
        StatusCode::UNAUTHORIZED,
        "the MCP token authenticates /mcp"
    );
    let api = app
        .client()
        .get("/api/auth/sign-in-methods")
        .bearer(&access)
        .send()
        .await;
    assert_eq!(api.status, StatusCode::UNAUTHORIZED, "never /api");
    let again = authorize(&admin, &client_id, MCP_REDIRECT, "accounts:read").await;
    assert!(
        location(&again).starts_with(&format!("{MCP_REDIRECT}?code=")),
        "consent is remembered"
    );
    let apps = admin.get("/api/connected-apps").send().await.json();
    let id = apps[0]["id"].as_str().unwrap().to_owned();
    assert_eq!(apps[0]["clientName"], "Claude");
    let member = {
        app.create_member("bob", "bob-password-1").await;
        app.login("bob", "bob-password-1").await
    };
    assert_eq!(
        member
            .delete(&format!("/api/connected-apps/{id}"))
            .send()
            .await
            .status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        admin
            .delete(&format!("/api/connected-apps/{id}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    let remaining: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM oauth2_authorization WHERE id = $1")
            .bind(&id)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(remaining, 0);
}

#[tokio::test]
async fn denying_consent_returns_access_denied_to_the_client() {
    let app = TestApp::with_admin().await;
    let client_id = register(&app, "accounts:read").await;
    let admin = app.login_admin().await;
    let parked = authorize(&admin, &client_id, MCP_REDIRECT, "accounts:read").await;
    let state = query(&location(&parked))["state"].clone();
    let anonymous = app
        .client()
        .post("/oauth2/authorize")
        .body(
            "application/x-www-form-urlencoded",
            form(&[("client_id", &client_id), ("state", &state)]),
        )
        .send()
        .await;
    assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED);
    let denied = admin
        .post("/oauth2/authorize")
        .body(
            "application/x-www-form-urlencoded",
            form(&[("client_id", &client_id), ("state", &state)]),
        )
        .send()
        .await;
    assert_eq!(
        location(&denied),
        format!(
            "{MCP_REDIRECT}?error=access_denied&error_description=OAuth%202.0%20Parameter%3A%20client_id&error_uri=https%3A%2F%2Fdatatracker.ietf.org%2Fdoc%2Fhtml%2Frfc6749%23section-4.1.2.1&state=xyz"
        )
    );
    let reused = admin
        .post("/oauth2/authorize")
        .body(
            "application/x-www-form-urlencoded",
            form(&[
                ("client_id", &client_id),
                ("state", &state),
                ("scope", "accounts:read"),
            ]),
        )
        .send()
        .await;
    assert_eq!(
        reused.status,
        StatusCode::UNAUTHORIZED,
        "a denied request is gone"
    );
}

#[tokio::test]
async fn dynamic_registration_validates_and_is_rate_limited() {
    let app = TestApp::with_admin().await;
    let cases = [
        (
            json!({"redirect_uris": []}),
            "redirect_uris must contain at least one URI",
        ),
        (
            json!({"redirect_uris": ["http://evil.example/cb"]}),
            "redirect_uris entry must be https:// or a loopback http://127.0.0.1 / http://localhost URI: http://evil.example/cb",
        ),
        (
            json!({"redirect_uris": ["https://ok.example/cb#frag"]}),
            "Malformed redirect_uris entry: https://ok.example/cb#frag",
        ),
        (
            json!({"redirect_uris": ["https://ok.example/cb"], "scope": "accounts:read nope"}),
            "Unknown scope: nope",
        ),
    ];
    for (body, detail) in cases {
        let response = app
            .client()
            .post("/oauth2/register")
            .json(&body)
            .send()
            .await;
        assert_eq!(response.status, StatusCode::BAD_REQUEST);
        assert_eq!(response.json()["detail"], detail);
    }
    let defaulted = app
        .client()
        .post("/oauth2/register")
        .json(&json!({"redirect_uris": ["http://127.0.0.1:9000/cb"]}))
        .send()
        .await
        .json();
    let scopes: Vec<&str> = defaulted["scope"].as_str().unwrap().split(' ').collect();
    assert_eq!(scopes.len(), 14);
    assert!(
        scopes
            .iter()
            .all(|s| s.ends_with(":read") || s.ends_with("-read"))
    );
    for _ in 0..5 {
        app.client()
            .post("/oauth2/register")
            .json(&json!({}))
            .send()
            .await;
    }
    let limited = app
        .client()
        .post("/oauth2/register")
        .json(&json!({}))
        .send()
        .await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn a_loopback_redirect_may_change_port_but_not_localhost() {
    let app = TestApp::with_admin().await;
    let response = app
        .client()
        .post("/oauth2/register")
        .json(&json!({"redirect_uris": ["http://127.0.0.1:8080/cb", "http://localhost:8080/cb"], "scope": "accounts:read"}))
        .send()
        .await
        .json();
    let client_id = response["client_id"].as_str().unwrap().to_owned();
    let admin = app.login_admin().await;
    let other_port = authorize(
        &admin,
        &client_id,
        "http://127.0.0.1:9999/cb",
        "accounts:read",
    )
    .await;
    assert_eq!(other_port.status, StatusCode::FOUND);
    assert!(location(&other_port).contains("/consent?"));
    let localhost = authorize(
        &admin,
        &client_id,
        "http://localhost:8080/cb",
        "accounts:read",
    )
    .await;
    assert_eq!(localhost.status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn metadata_documents_follow_the_request_origin() {
    let app = TestApp::spawn().await;
    let meta = app
        .client()
        .get("/.well-known/oauth-authorization-server")
        .header("host", "picsou.example")
        .send()
        .await;
    assert_eq!(
        meta.status,
        StatusCode::OK,
        "served before setup, like the AS chain"
    );
    let meta = meta.json();
    assert_eq!(meta["issuer"], "http://picsou.example");
    assert_eq!(
        meta["registration_endpoint"],
        "http://picsou.example/oauth2/register"
    );
    assert_eq!(meta["code_challenge_methods_supported"], json!(["S256"]));
    let jwks = app.client().get("/oauth2/jwks").send().await;
    assert_eq!(jwks.text(), "{\"keys\":[]}");
    let app = TestApp::with_admin().await;
    let resource = app
        .client()
        .get("/.well-known/oauth-protected-resource")
        .header("host", "picsou.example")
        .send()
        .await
        .json();
    assert_eq!(resource["resource"], "http://picsou.example/mcp");
    assert_eq!(
        resource["authorization_servers"],
        json!(["http://picsou.example"])
    );
    assert_eq!(resource["bearer_methods_supported"], json!(["header"]));
    assert_eq!(resource["scopes_supported"].as_array().unwrap().len(), 26);
}

/// A row in the exact format Java's JdbcOAuth2AuthorizationService writes, code still unused.
const JAVA_ATTRIBUTES: &str = r#"{"@class":"java.util.Collections$UnmodifiableMap","java.security.Principal":{"@class":"org.springframework.security.authentication.UsernamePasswordAuthenticationToken","authorities":["java.util.Collections$UnmodifiableRandomAccessList",[{"@class":"org.springframework.security.core.authority.SimpleGrantedAuthority","authority":"ROLE_ADMIN"}]],"details":null,"authenticated":true,"principal":{"@class":"com.picsou.model.AppUser","id":1,"username":"admin","role":"ADMIN","tokenVersion":0},"credentials":null},"org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest":{"@class":"org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest","authorizationUri":"http://127.0.0.1:62629/oauth2/authorize","authorizationGrantType":{"value":"authorization_code"},"responseType":{"value":"code"},"clientId":"picsou-ios","redirectUri":"picsou://callback","scopes":["java.util.Collections$UnmodifiableSet",["read","write"]],"state":"st","additionalParameters":{"@class":"java.util.Collections$UnmodifiableMap","code_challenge":"CHALLENGE","code_challenge_method":"S256"},"authorizationRequestUri":"http://127.0.0.1:62629/oauth2/authorize?response_type=code","attributes":{"@class":"java.util.Collections$UnmodifiableMap"}}}"#;

#[tokio::test]
async fn a_code_the_java_backend_issued_is_exchanged() {
    let app = TestApp::with_admin().await;
    let client: String = picsou::db::query_scalar(
        "SELECT id FROM oauth2_registered_client WHERE client_id = 'picsou-ios'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type, authorized_scopes, \
         attributes, authorization_code_value, authorization_code_issued_at, authorization_code_expires_at, authorization_code_metadata) \
         VALUES ('java-row', $1, 'admin', 'authorization_code', 'read,write', $2, 'java-code', $3, $4, \
         '{\"@class\":\"java.util.Collections$UnmodifiableMap\",\"metadata.token.invalidated\":false}')",
    )
    .bind(&client)
    .bind(JAVA_ATTRIBUTES.replace("CHALLENGE", &challenge()))
    .bind(chrono::NaiveDate::from_ymd_opt(2026, 1, 15).unwrap().and_hms_opt(11, 0, 0).unwrap())
    .bind(chrono::NaiveDate::from_ymd_opt(2026, 1, 15).unwrap().and_hms_opt(11, 5, 0).unwrap())
    .execute(&app.state.db)
    .await
    .unwrap();
    let issued = exchange(&app, "java-code", "picsou-ios", "picsou://callback").await;
    assert_eq!(issued.status, StatusCode::OK, "{}", issued.text());
    assert_eq!(issued.json()["scope"], "read write");
    let (metadata, scopes): (String, String) = picsou::db::query_as(
        "SELECT access_token_metadata, access_token_scopes FROM oauth2_authorization WHERE id = 'java-row'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(scopes, "read,write");
    let metadata: Value = serde_json::from_str(&metadata).unwrap();
    assert_eq!(
        metadata["metadata.token.claims"]["uid"],
        json!(["java.lang.Long", 1])
    );
    assert_eq!(
        metadata["metadata.token.claims"]["aud"],
        json!(["java.util.Collections$SingletonList", ["picsou-ios"]])
    );
}
