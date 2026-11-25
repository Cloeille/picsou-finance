use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::json;

#[tokio::test]
async fn creates_lists_and_revokes_a_key_that_then_stops_working() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let created = admin
        .post("/api/access-keys")
        .json(&json!({"name": "Claude", "scopes": ["transactions:read", "accounts:read"]}))
        .send()
        .await;
    assert_eq!(created.status, StatusCode::CREATED);
    let body = created.json();
    let secret = body["secret"].as_str().unwrap().to_owned();
    assert!(secret.starts_with("psk_") && secret.len() == 36);
    assert_eq!(body["key"]["keyPrefix"], secret[..12]);
    assert_eq!(
        body["key"]["scopes"],
        json!(["accounts:read", "transactions:read"])
    );
    assert_eq!(body["key"]["createdAt"], "2026-01-15T10:00:00Z");
    assert!(body["key"].get("revokedAt").is_none());
    let id = body["key"]["id"].as_i64().unwrap();

    let mcp = app
        .client()
        .get("/mcp/anything")
        .bearer(&secret)
        .send()
        .await;
    assert_ne!(
        mcp.status,
        StatusCode::UNAUTHORIZED,
        "the new key authenticates /mcp"
    );

    let list = admin.get("/api/access-keys").send().await.json();
    assert_eq!(list.as_array().unwrap().len(), 1);
    assert_eq!(
        admin
            .delete(&format!("/api/access-keys/{id}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        admin
            .delete(&format!("/api/access-keys/{id}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    let list = admin.get("/api/access-keys").send().await.json();
    assert_eq!(list[0]["revokedAt"], "2026-01-15T10:00:00Z");
    let revoked = app
        .client()
        .get("/mcp/anything")
        .bearer(&secret)
        .send()
        .await;
    assert_eq!(revoked.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn validates_the_request() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let invalid = admin
        .post("/api/access-keys")
        .json(&json!({"name": "", "scopes": [], "expiresAt": "2026-01-15T09:59:59Z"}))
        .send()
        .await;
    assert_eq!(invalid.status, StatusCode::UNPROCESSABLE_ENTITY);
    let errors = &invalid.json()["errors"];
    assert_eq!(errors["name"], "must not be blank");
    assert_eq!(errors["scopes"], "must not be empty");
    assert_eq!(errors["expiresAt"], "must be a future date");
    let unknown = admin
        .post("/api/access-keys")
        .json(&json!({"name": "x", "scopes": ["accounts:read", "admin:all"]}))
        .send()
        .await;
    assert_eq!(unknown.status, StatusCode::BAD_REQUEST);
    assert_eq!(unknown.json()["detail"], "Unknown scope: admin:all");
}

#[tokio::test]
async fn keys_are_scoped_to_their_member() {
    let app = TestApp::with_admin().await;
    app.create_member("bob", "bob-password-1").await;
    let admin = app.login_admin().await;
    let bob = app.login("bob", "bob-password-1").await;
    let created = admin
        .post("/api/access-keys")
        .json(&json!({"name": "mine", "scopes": ["accounts:read"]}))
        .send()
        .await
        .json();
    let id = created["key"]["id"].as_i64().unwrap();
    assert_eq!(bob.get("/api/access-keys").send().await.json(), json!([]));
    let stolen = bob.delete(&format!("/api/access-keys/{id}")).send().await;
    assert_eq!(stolen.status, StatusCode::NOT_FOUND);
    assert_eq!(stolen.json()["detail"], "Access key not found");
}

#[tokio::test]
async fn creation_is_rate_limited_per_member() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    for _ in 0..10 {
        let ok = admin
            .post("/api/access-keys")
            .json(&json!({"name": "k", "scopes": ["accounts:read"]}))
            .send()
            .await;
        assert_eq!(ok.status, StatusCode::CREATED);
    }
    let limited = admin
        .post("/api/access-keys")
        .json(&json!({"name": "k", "scopes": ["accounts:read"]}))
        .send()
        .await;
    assert_eq!(limited.status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        limited.json()["detail"],
        "Too many access-key creations. Try again later."
    );
}
