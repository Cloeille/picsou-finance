//! FamilyController member management and FamilyServiceTest / UsernameDeriverTest, over HTTP.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::json;

use super::support::{PASSWORD, account, admin_member};

async fn user_of(app: &TestApp, member_id: i64) -> Option<(String, String, bool, Option<String>)> {
    picsou::db::query_as(
        "SELECT username, password_hash, is_activated, activation_token FROM app_user WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn a_member_gets_403_on_every_member_management_endpoint() {
    let app = TestApp::with_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let client = app.login("bob", PASSWORD).await;

    for response in [
        client.get("/api/family/members").send().await,
        client
            .post("/api/family/members")
            .json(&json!({"displayName": "X"}))
            .send()
            .await,
        client
            .put(&format!("/api/family/members/{bob}"))
            .json(&json!({"displayName": "X"}))
            .send()
            .await,
        client
            .delete(&format!("/api/family/members/{bob}"))
            .send()
            .await,
        client
            .post(&format!("/api/family/members/{bob}/activate"))
            .send()
            .await,
        client
            .post(&format!("/api/family/members/{bob}/reset-password"))
            .send()
            .await,
    ] {
        assert_eq!(
            response.status,
            StatusCode::FORBIDDEN,
            "{}",
            response.text()
        );
        assert_eq!(response.json()["detail"], "Admin access required");
    }
    // Bean validation runs on the body before the controller checks the role.
    let invalid = client
        .post("/api/family/members")
        .json(&json!({"displayName": " "}))
        .send()
        .await;
    assert_eq!(invalid.status, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn create_validates_the_display_name_and_returns_a_managed_profile() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;

    let blank = admin
        .post("/api/family/members")
        .json(&json!({"displayName": "  "}))
        .send()
        .await;
    assert_eq!(blank.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(blank.json()["errors"]["displayName"], "must not be blank");
    let long = admin
        .post("/api/family/members")
        .json(&json!({"displayName": "a".repeat(101)}))
        .send()
        .await;
    assert_eq!(
        long.json()["errors"]["displayName"],
        "size must be between 0 and 100"
    );

    let created = admin
        .post("/api/family/members")
        .json(&json!({"displayName": "Léa", "ignored": 1}))
        .send()
        .await;
    assert_eq!(created.status, StatusCode::CREATED);
    let body = created.json();
    assert_eq!(body["avatarColor"], "#6366f1");
    assert_eq!(body["managed"], true);
    assert_eq!(body["hasLogin"], false);
    assert!(body.get("loginName").is_none(), "null loginName is omitted");

    let list = admin.get("/api/family/members").send().await.json();
    let names: Vec<&str> = list
        .as_array()
        .unwrap()
        .iter()
        .map(|m| m["displayName"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["admin", "Léa"]);
    assert_eq!(list[0]["loginName"], "admin");
    assert_eq!(list[0]["hasPassword"], true);
    assert_eq!(list[0]["activated"], true);
}

#[tokio::test]
async fn list_reports_mfa_password_and_sso_flags() {
    let app = TestApp::with_admin().await;
    let (bob_user, _) = app.create_member("bob", PASSWORD).await;
    picsou::db::query("UPDATE app_user SET password_hash = '' WHERE id = $1")
        .bind(bob_user)
        .execute(&app.state.db)
        .await
        .unwrap();
    picsou::db::query(
        "INSERT INTO user_identity (user_id, issuer, subject) VALUES ($1, 'https://idp', 'sub-1')",
    )
    .bind(bob_user)
    .execute(&app.state.db)
    .await
    .unwrap();
    picsou::db::query(
        "INSERT INTO user_mfa (user_id, enabled, totp_secret_enc) VALUES ($1, true, 'x')",
    )
    .bind(bob_user)
    .execute(&app.state.db)
    .await
    .unwrap();
    let admin = app.login_admin().await;

    let list = admin.get("/api/family/members").send().await.json();
    let bob = list
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["displayName"] == "bob")
        .unwrap();
    assert_eq!(bob["hasPassword"], false);
    assert_eq!(bob["ssoLinked"], true);
    assert_eq!(bob["mfaEnabled"], true);
    assert_eq!(list[0]["ssoLinked"], false);
}

#[tokio::test]
async fn update_requires_a_display_name_and_an_existing_member() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let id = app.create_managed_member("Kid").await;

    for body in [
        json!({"displayName": " "}),
        json!({}),
        json!({"displayName": null}),
    ] {
        let response = admin
            .put(&format!("/api/family/members/{id}"))
            .json(&body)
            .send()
            .await;
        assert_eq!(response.status, StatusCode::BAD_REQUEST);
        assert_eq!(response.json()["detail"], "displayName is required");
    }
    let missing = admin
        .put("/api/family/members/999999")
        .json(&json!({"displayName": "X"}))
        .send()
        .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(missing.json()["detail"], "Member not found");

    let renamed = admin
        .put(&format!("/api/family/members/{id}"))
        .json(&json!({"displayName": "Kiddo"}))
        .send()
        .await;
    assert_eq!(renamed.status, StatusCode::OK);
    assert_eq!(renamed.json()["displayName"], "Kiddo");
}

#[tokio::test]
async fn activation_derives_the_username_and_reuses_an_unactivated_login() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    app.create_member("jean.dupont", PASSWORD).await;
    let id = app.create_managed_member("Jean Dupont").await;

    let first = admin
        .post(&format!("/api/family/members/{id}/activate"))
        .send()
        .await;
    assert_eq!(first.status, StatusCode::OK);
    let link = first.json()["activationLink"].as_str().unwrap().to_owned();
    let token = link.strip_prefix("/activate/").expect("activation link");
    assert_eq!(token.len(), 64);
    assert!(token.bytes().all(|b| b.is_ascii_hexdigit()));
    let (username, hash, activated, stored) = user_of(&app, id).await.unwrap();
    assert_eq!(username, "jean.dupont.2", "the taken name gets a suffix");
    assert_eq!(hash, "");
    assert!(!activated);
    assert_eq!(stored.as_deref(), Some(token));
    let expires: chrono::DateTime<chrono::Utc> = picsou::db::query_scalar(
        "SELECT activation_token_expires FROM app_user WHERE member_id = $1",
    )
    .bind(id)
    .fetch_one(&app.state.db)
    .await
    .unwrap();
    assert_eq!(
        expires,
        picsou::testing::epoch() + chrono::Duration::days(7)
    );

    let again = admin
        .post(&format!("/api/family/members/{id}/activate"))
        .send()
        .await
        .json();
    let second = again["activationLink"]
        .as_str()
        .unwrap()
        .strip_prefix("/activate/")
        .unwrap()
        .to_owned();
    assert_ne!(second, token);
    let (username, _, _, stored) = user_of(&app, id).await.unwrap();
    assert_eq!(username, "jean.dupont.2");
    assert_eq!(stored.as_deref(), Some(second.as_str()));

    let list = admin.get("/api/family/members").send().await.json();
    let jean = list
        .as_array()
        .unwrap()
        .iter()
        .find(|m| m["id"] == id)
        .unwrap();
    assert_eq!(jean["hasLogin"], true);
    assert_eq!(jean["activated"], false);
    assert_eq!(jean["loginName"], "jean.dupont.2");
}

#[tokio::test]
async fn activation_refuses_an_active_login_and_an_unknown_member() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;

    let active = admin
        .post(&format!("/api/family/members/{bob}/activate"))
        .send()
        .await;
    assert_eq!(active.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        active.json()["detail"],
        "This account already has an active login."
    );
    let missing = admin
        .post("/api/family/members/999999/activate")
        .send()
        .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn reset_issues_a_link_and_keeps_the_password_until_it_is_used() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let kid = app.create_managed_member("Kid").await;
    let (_, hash_before, _, _) = user_of(&app, bob).await.unwrap();

    let reset = admin
        .post(&format!("/api/family/members/{bob}/reset-password"))
        .send()
        .await;
    assert_eq!(reset.status, StatusCode::OK);
    let token = reset.json()["resetLink"]
        .as_str()
        .unwrap()
        .strip_prefix("/activate/")
        .unwrap()
        .to_owned();
    let (_, hash_after, activated, stored) = user_of(&app, bob).await.unwrap();
    assert_eq!(hash_after, hash_before);
    assert!(activated);
    assert_eq!(stored, Some(token));
    app.login("bob", PASSWORD).await;

    let no_login = admin
        .post(&format!("/api/family/members/{kid}/reset-password"))
        .send()
        .await;
    assert_eq!(no_login.status, StatusCode::BAD_REQUEST);
    assert_eq!(no_login.json()["detail"], "Member has no login to reset");
    let missing = admin
        .post("/api/family/members/999999/reset-password")
        .send()
        .await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delete_guards_self_and_unknown_members() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let own = admin_member(&app).await;

    let me = admin
        .delete(&format!("/api/family/members/{own}"))
        .send()
        .await;
    assert_eq!(me.status, StatusCode::FORBIDDEN);
    assert_eq!(me.json()["detail"], "Cannot delete your own account");
    let missing = admin.delete("/api/family/members/999999").send().await;
    assert_eq!(missing.status, StatusCode::NOT_FOUND);
    assert_eq!(missing.json()["detail"], "Member not found");
}

#[tokio::test]
async fn deleting_an_activated_member_removes_the_login_and_cascades_their_data() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let (_, bob) = app.create_member("bob", PASSWORD).await;
    let kid = app.create_managed_member("Kid").await;
    let bobs = account(&app, bob, "Bob", "CHECKING", "10").await;
    let kids = account(&app, kid, "Kid", "SAVINGS", "5").await;

    assert_eq!(
        admin
            .delete(&format!("/api/family/members/{bob}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        admin
            .delete(&format!("/api/family/members/{kid}"))
            .send()
            .await
            .status,
        StatusCode::NO_CONTENT
    );

    assert!(user_of(&app, bob).await.is_none());
    let left: i64 = picsou::db::query_scalar("SELECT count(*) FROM account WHERE id = ANY($1)")
        .bind(vec![bobs, kids])
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(left, 0);
    let list = admin.get("/api/family/members").send().await.json();
    assert_eq!(list.as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn another_administrator_can_be_deleted_while_one_remains() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let (second, second_member) = app.create_member("second", PASSWORD).await;
    picsou::db::query("UPDATE app_user SET role = 'ADMIN' WHERE id = $1")
        .bind(second)
        .execute(&app.state.db)
        .await
        .unwrap();

    let response = admin
        .delete(&format!("/api/family/members/{second_member}"))
        .send()
        .await;
    assert_eq!(response.status, StatusCode::NO_CONTENT);
    let admins: i64 =
        picsou::db::query_scalar("SELECT count(*) FROM app_user WHERE role = 'ADMIN'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(admins, 1);
}
