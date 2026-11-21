use picsou::testing::{ADMIN_PASSWORD, ADMIN_USERNAME, TestApp};
use reqwest::StatusCode;
use serde_json::json;

async fn remembered(
    app: &TestApp,
    username: &str,
    password: &str,
    agent: &str,
) -> picsou::testing::Client {
    let client = app.client();
    let response = client
        .post("/api/auth/login")
        .header("user-agent", agent)
        .json(&json!({"username": username, "password": password, "rememberMe": true}))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::OK);
    client
}

#[tokio::test]
async fn lists_and_revokes_sessions() {
    let app = TestApp::with_admin().await;
    let phone = remembered(&app, ADMIN_USERNAME, ADMIN_PASSWORD, "phone").await;
    let laptop = remembered(&app, ADMIN_USERNAME, ADMIN_PASSWORD, "laptop").await;
    let list = laptop.get("/api/auth/sessions").send().await.json();
    let sessions = list.as_array().unwrap();
    assert_eq!(sessions.len(), 2);
    let current: Vec<_> = sessions.iter().filter(|s| s["current"] == true).collect();
    assert_eq!(current.len(), 1);
    assert_eq!(current[0]["userAgent"], "laptop");
    assert_eq!(current[0]["ipPrefix"], "127.0.0.");
    assert_eq!(current[0]["createdAt"], "2026-01-15T10:00:00Z");
    assert_eq!(current[0]["expiresAt"], "2026-04-15T10:00:00Z");
    assert_eq!(current[0]["trustedFor2fa"], false);

    assert_eq!(
        laptop.delete("/api/auth/sessions").send().await.status,
        StatusCode::NO_CONTENT
    );
    let after = laptop.get("/api/auth/sessions").send().await.json();
    assert_eq!(
        after.as_array().unwrap().len(),
        1,
        "the current session is spared"
    );
    phone.remove_cookie("access_token");
    phone.remove_cookie("refresh_token");
    assert_eq!(
        phone.get("/api/auth/sessions").send().await.status,
        StatusCode::UNAUTHORIZED
    );

    let id = after[0]["id"].as_i64().unwrap();
    assert_eq!(
        laptop
            .delete(&format!("/api/auth/sessions/{id}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        laptop.get("/api/auth/sessions").send().await.json(),
        json!([])
    );
}

#[tokio::test]
async fn another_users_session_is_not_found() {
    let app = TestApp::with_admin().await;
    app.create_member("member", "member-password").await;
    let admin = remembered(&app, ADMIN_USERNAME, ADMIN_PASSWORD, "a").await;
    let member = remembered(&app, "member", "member-password", "m").await;
    let admin_id = admin.get("/api/auth/sessions").send().await.json()[0]["id"]
        .as_i64()
        .unwrap();
    let stolen = member
        .delete(&format!("/api/auth/sessions/{admin_id}"))
        .send()
        .await;
    assert_eq!(stolen.status, StatusCode::NOT_FOUND);
    assert!(stolen.body.is_empty());
    assert_eq!(
        member.delete("/api/auth/sessions/9999").send().await.status,
        StatusCode::NOT_FOUND
    );
    let typed = member.delete("/api/auth/sessions/abc").send().await;
    assert_eq!(typed.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        typed.json()["detail"],
        "Failed to convert 'id' with value: 'abc'"
    );
}
