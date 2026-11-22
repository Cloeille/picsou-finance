use picsou::testing::{ADMIN_PASSWORD, ADMIN_USERNAME, TestApp};
use reqwest::StatusCode;
use serde_json::json;
use wiremock::matchers::{header_exists, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[tokio::test]
async fn status_before_setup() {
    let app = TestApp::spawn().await;
    let response = app.client().get("/api/setup/status").send().await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(
        response.text(),
        r#"{"state":"PENDING_ADMIN","needsSetup":true,"integrations":{"enablebanking":false,"boursobank":false,"boursedirect":false,"fortuneo":false,"traderepublic":false,"lbp":false,"finary":false,"crypto":false},"sso":{"enabled":false,"providerName":"SSO","adminBootstrap":false}}"#
    );
}

#[tokio::test]
async fn the_gate_blocks_the_api_until_setup_completes() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let blocked = client.get("/api/accounts").send().await;
    assert_eq!(blocked.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        blocked.header("content-type"),
        Some("application/problem+json;charset=ISO-8859-1")
    );
    assert_eq!(
        blocked.text(),
        "            {\"status\":503,\"title\":\"Setup Required\",\"code\":\"setup_required\",\"detail\":\"Picsou is not configured yet. Complete the setup wizard at /setup.\"}\n"
    );
    assert_eq!(
        client.get("/actuator/health").send().await.status,
        StatusCode::OK
    );
    // Outside the backend prefixes the SPA answers (here: nothing served, so Spring's 404).
    assert_eq!(
        client.get("/setup").send().await.status,
        StatusCode::NOT_FOUND
    );

    app.complete_setup(ADMIN_USERNAME, ADMIN_PASSWORD).await;
    assert_eq!(
        client.get("/api/accounts").send().await.status,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn admin_request_validation() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let response = client
        .post("/api/setup/admin")
        .json(&json!({"username": "a", "password": "short"}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        response.json(),
        json!({"type": "about:blank", "title": "Validation failed", "status": 422, "instance": "/api/setup/admin",
               "errors": {"password": "size must be between 8 and 128", "username": "size must be between 3 and 50"}})
    );
    let empty = client
        .post("/api/setup/admin")
        .json(&json!({}))
        .send()
        .await
        .json();
    assert_eq!(
        empty["errors"],
        json!({"password": "must not be blank", "username": "must not be blank"})
    );
    let pattern = client
        .post("/api/setup/admin")
        .json(&json!({"username": "bad name", "password": "password123", "avatarColor": "red"}))
        .send()
        .await
        .json();
    assert_eq!(
        pattern["errors"],
        json!({"username": "Username may only contain letters, digits, dots, underscores and hyphens",
               "avatarColor": "Avatar color must be a hex color like #6366f1"})
    );
}

#[tokio::test]
async fn validation_messages_follow_accept_language() {
    let app = TestApp::spawn().await;
    let french = app
        .client()
        .post("/api/setup/admin")
        .header("accept-language", "fr-FR,fr;q=0.9,en;q=0.8")
        .json(&json!({"username": "a", "avatarColor": "red"}))
        .send()
        .await
        .json();
    assert_eq!(
        french["errors"],
        json!({"username": "la taille doit être comprise entre 3 et 50", "password": "ne doit pas être vide",
               "avatarColor": "Avatar color must be a hex color like #6366f1"})
    );
    let english = app
        .client()
        .post("/api/setup/admin")
        .header("accept-language", "en-US,fr;q=0.5")
        .json(&json!({}))
        .send()
        .await
        .json();
    assert_eq!(english["errors"]["password"], "must not be blank");
}

#[tokio::test]
async fn unreadable_bodies() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let malformed = client
        .post("/api/setup/admin")
        .body("application/json", "{bad")
        .send()
        .await;
    assert_eq!(malformed.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        malformed.text(),
        r#"{"type":"about:blank","title":"Bad Request","status":400,"detail":"Failed to read request","instance":"/api/setup/admin"}"#
    );
    let wrong_type = client
        .post("/api/setup/admin")
        .body("text/plain", "{}")
        .send()
        .await;
    assert_eq!(wrong_type.status, StatusCode::UNSUPPORTED_MEDIA_TYPE);
    assert_eq!(
        wrong_type.json()["detail"],
        "Content-Type 'text/plain;charset=UTF-8' is not supported."
    );
}

#[tokio::test]
async fn seeds_one_admin_only() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let created = client
        .post("/api/setup/admin")
        .header("user-agent", "wizard-test")
        .json(&json!({"username": "admin", "password": "password123", "displayName": "Louise"}))
        .send()
        .await;
    assert_eq!(created.status, StatusCode::OK);
    assert_eq!(
        created.text(),
        r#"{"username":"admin","displayName":"Louise"}"#
    );
    let second = client
        .post("/api/setup/admin")
        .json(&json!({"username": "other", "password": "password123"}))
        .send()
        .await;
    assert_eq!(second.status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(second.json()["detail"], "An unexpected error occurred");
    let state: String =
        picsou::db::query_scalar("SELECT value FROM app_setting WHERE setting_key = 'setup.state'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(state, "IN_PROGRESS");
    let audit: (String, Option<String>, Option<String>) = picsou::db::query_as(
        "SELECT event, actor_username, user_agent FROM setup_audit ORDER BY id LIMIT 1",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        audit,
        (
            "setup.admin.created".into(),
            Some("admin".into()),
            Some("wizard-test".into())
        )
    );
}

#[tokio::test]
async fn security_step() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let invalid = client
        .post("/api/setup/security")
        .json(&json!({"allowedOrigins": ["http://x:1/"], "secureCookies": false}))
        .send()
        .await;
    assert_eq!(invalid.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        invalid.json()["errors"],
        json!({"allowedOrigins[0]": "Origin must look like http(s)://host[:port]"})
    );
    let saved = client
        .post("/api/setup/security")
        .json(&json!({"allowedOrigins": ["https://picsou.example"], "secureCookies": true}))
        .send()
        .await;
    assert_eq!(saved.status, StatusCode::NO_CONTENT);
    let origins: String = picsou::db::query_scalar(
        "SELECT value FROM app_setting WHERE setting_key = 'cors.allowed-origins'",
    )
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(origins, "https://picsou.example");

    app.complete_setup(ADMIN_USERNAME, ADMIN_PASSWORD).await;
    let login = client
        .post("/api/auth/login")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert!(
        login.set_cookies().iter().all(|c| c.ends_with("; Secure")),
        "the wizard's secure flag wins"
    );
}

#[tokio::test]
async fn acknowledgements_and_crypto_key() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let unknown = client
        .post("/api/setup/integrations/bnp/acknowledge")
        .send()
        .await;
    assert_eq!(unknown.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        unknown.json()["detail"],
        "This step only applies to integrations configured after signing in."
    );
    assert_eq!(
        client
            .post("/api/setup/integrations/lbp/acknowledge")
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    let key = client
        .post("/api/setup/integrations/crypto/generate-key")
        .send()
        .await;
    assert_eq!(
        key.text(),
        r#"{"existed":true,"path":"CRYPTO_ENCRYPTION_KEY"}"#
    );
    let status = client.get("/api/setup/status").send().await.json();
    assert_eq!(status["integrations"]["lbp"], true);
    assert_eq!(status["integrations"]["crypto"], true);
}

#[tokio::test]
async fn enable_banking_wizard() {
    let bank = MockServer::start().await;
    let keys = tempfile_dir();
    let app = TestApp::builder()
        .env("ENABLEBANKING_BASE_URL", bank.uri())
        .env(
            "ENABLEBANKING_PRIVATE_KEY_PATH",
            keys.join("eb.pem").display().to_string(),
        )
        .spawn()
        .await;
    let client = app.client();
    let missing = client
        .post("/api/setup/integrations/enablebanking/test")
        .send()
        .await
        .json();
    assert_eq!(
        missing,
        json!({"ok": false, "code": "invalid_application_id", "hint": "Application ID is missing. Fill it in on the previous step."})
    );

    let bad = client
        .post("/api/setup/integrations/enablebanking/config")
        .json(&json!({"applicationId": "nope", "redirectUri": "ftp://x"}))
        .send()
        .await
        .json();
    assert_eq!(
        bad["errors"],
        json!({"applicationId": "Application ID must look like a UUID (32-36 hex characters)",
               "redirectUri": "Redirect URI must start with http:// or https://"})
    );
    let app_id = "0123456789abcdef0123456789abcdef";
    let config = client
        .post("/api/setup/integrations/enablebanking/config")
        .json(&json!({"applicationId": app_id, "redirectUri": "https://picsou.example/sync/callback"}))
        .send()
        .await;
    assert_eq!(config.status, StatusCode::NO_CONTENT);

    let no_key = client
        .post("/api/setup/integrations/enablebanking/test")
        .send()
        .await
        .json();
    assert_eq!(no_key["code"], "public_key_not_uploaded");

    let first = client
        .post("/api/setup/integrations/enablebanking/keypair")
        .send()
        .await
        .json();
    assert_eq!(first["regenerated"], true);
    let pem = first["publicKeyPem"].as_str().unwrap().to_owned();
    assert!(
        pem.starts_with("-----BEGIN PUBLIC KEY-----\n")
            && pem.ends_with("-----END PUBLIC KEY-----\n")
    );
    assert!(
        pem.lines()
            .skip(1)
            .take_while(|l| !l.starts_with("-----"))
            .all(|l| l.len() <= 64)
    );
    let again = client
        .post("/api/setup/integrations/enablebanking/keypair")
        .send()
        .await
        .json();
    assert_eq!(again, json!({"publicKeyPem": pem, "regenerated": false}));

    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .and(query_param("country", "FR"))
        .and(header_exists("authorization"))
        .respond_with(ResponseTemplate::new(401))
        .up_to_n_times(1)
        .mount(&bank)
        .await;
    assert_eq!(
        client
            .post("/api/setup/integrations/enablebanking/test")
            .send()
            .await
            .json()["code"],
        "invalid_key_id"
    );
    Mock::given(method("GET"))
        .and(path("/aspsps"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&bank)
        .await;
    let ok = client
        .post("/api/setup/integrations/enablebanking/test")
        .send()
        .await
        .json();
    assert_eq!(
        ok,
        json!({"ok": true, "code": "ok", "hint": "Enable Banking is ready to use."})
    );

    let requests = bank.received_requests().await.unwrap();
    let jwt = requests[0]
        .headers
        .get("authorization")
        .unwrap()
        .to_str()
        .unwrap()
        .trim_start_matches("Bearer ")
        .to_owned();
    let header: serde_json::Value = decode_segment(jwt.split('.').next().unwrap());
    assert_eq!(header, json!({"alg": "RS256", "kid": app_id}));
    let claims: serde_json::Value = decode_segment(jwt.split('.').nth(1).unwrap());
    assert_eq!(claims["iss"], app_id);
    assert_eq!(claims["aud"], "api.enablebanking.com");
    assert_eq!(
        claims["exp"].as_i64().unwrap() - claims["iat"].as_i64().unwrap(),
        60
    );
    assert_eq!(
        client.get("/api/setup/status").send().await.json()["integrations"]["enablebanking"],
        true
    );
}

#[tokio::test]
async fn private_key_import_errors() {
    let keys = tempfile_dir();
    let app = TestApp::builder()
        .env(
            "ENABLEBANKING_PRIVATE_KEY_PATH",
            keys.join("eb.pem").display().to_string(),
        )
        .spawn()
        .await;
    let client = app.client();
    let pkcs1 = client
        .post("/api/setup/integrations/enablebanking/keypair/import")
        .json(&json!({"privatePem": "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"}))
        .send()
        .await;
    assert_eq!(pkcs1.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert!(
        pkcs1.json()["detail"]
            .as_str()
            .unwrap()
            .starts_with("PKCS#1 format (BEGIN RSA PRIVATE KEY) is not supported.")
    );
    let garbage = client
        .post("/api/setup/integrations/enablebanking/keypair/import")
        .json(&json!({"privatePem": "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"}))
        .send()
        .await
        .json();
    assert_eq!(garbage["title"], "Unprocessable Entity");
    assert!(
        garbage["detail"]
            .as_str()
            .unwrap()
            .starts_with("The PEM could not be parsed as a valid RSA PKCS#8 private key")
    );
    assert!(!keys.join("eb.pem").exists());
}

#[tokio::test]
async fn bourso_sidecar_health() {
    let sidecar = MockServer::start().await;
    let app = TestApp::builder()
        .env("BOURSO_AUTH_URL", sidecar.uri())
        .spawn()
        .await;
    let client = app.client();
    let down = client
        .get("/api/setup/integrations/boursobank/health")
        .send()
        .await
        .json();
    assert_eq!(down["ok"], false);
    assert_eq!(
        down["hint"],
        format!(
            "Sidecar at {} did not respond within 3s. Make sure the bourso-auth container is running, or override the URL below.",
            sidecar.uri()
        )
    );
    Mock::given(method("GET"))
        .and(path("/health"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&sidecar)
        .await;
    let up = client
        .get("/api/setup/integrations/boursobank/health")
        .send()
        .await;
    assert_eq!(
        up.text(),
        format!(r#"{{"ok":true,"url":"{}"}}"#, sidecar.uri())
    );
}

#[tokio::test]
async fn completion_is_one_way() {
    let app = TestApp::with_admin().await;
    let client = app.client();
    let gone = client.post("/api/setup/complete").send().await;
    assert_eq!(gone.status, StatusCode::GONE);
    assert_eq!(
        gone.text(),
        r#"{"type":"about:blank","title":"Gone","status":410,"detail":"Setup is already complete; use the admin UI to manage users and security.","instance":"/api/setup/complete"}"#
    );
    assert_eq!(
        client.get("/api/setup/status").send().await.json()["state"],
        "COMPLETE"
    );
}

#[tokio::test]
async fn setup_requests_are_rate_limited() {
    let app = TestApp::spawn().await;
    let client = app.client();
    for _ in 0..10 {
        client
            .post("/api/setup/integrations/lbp/acknowledge")
            .send()
            .await;
    }
    let limited = client
        .post("/api/setup/integrations/lbp/acknowledge")
        .send()
        .await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        limited.json()["detail"],
        "Too many setup requests. Try again in a minute."
    );
    app.clock.advance(std::time::Duration::from_secs(60));
    assert_eq!(
        client
            .post("/api/setup/integrations/lbp/acknowledge")
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
}

#[tokio::test]
async fn environment_admin_is_seeded_at_start() {
    let hash = bcrypt::hash("env-password-1", 4).unwrap();
    let app = TestApp::builder()
        .env("APP_USERNAME", "envadmin")
        .env("APP_PASSWORD_HASH", hash)
        .spawn()
        .await;
    assert_eq!(
        app.client().get("/api/setup/status").send().await.json()["state"],
        "COMPLETE"
    );
    app.login("envadmin", "env-password-1").await;
}

fn tempfile_dir() -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("picsou-test-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn decode_segment(segment: &str) -> serde_json::Value {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(segment)
        .unwrap();
    serde_json::from_slice(&bytes).unwrap()
}
