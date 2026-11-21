//! InstitutionLogoControllerTest and InstitutionLogoServiceTest over HTTP, the icon service
//! stubbed with wiremock.

use std::time::Duration;

use picsou::testing::{Client, TestApp};
use reqwest::StatusCode;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const PASSWORD: &str = "member-pass-1234";
const PNG: &[u8] = &[0x89, b'P', b'N', b'G', 13, 10, 26, 10, 0, 0, 0, 13, 1, 2];

async fn bank_account(app: &TestApp, member_id: i64, domain: Option<&str>) -> i64 {
    sqlx::query_scalar(
        "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color, institution_domain, created_at, updated_at) \
         VALUES ($1, 'Chase', 'CHECKING'::account_type, 'USD', 0, false, '#000000', $2, now(), now()) RETURNING id",
    )
    .bind(member_id)
    .bind(domain)
    .fetch_one(&app.state.db)
    .await
    .expect("account")
}

async fn enable_logos(app: &TestApp, member_id: i64) {
    sqlx::query("INSERT INTO budget_settings (member_id, logo_fetch_enabled) VALUES ($1, true)")
        .bind(member_id)
        .execute(&app.state.db)
        .await
        .expect("budget settings");
}

async fn icon(server: &MockServer, domain: &str, body: &[u8], content_type: &str) {
    Mock::given(method("GET"))
        .and(path(format!("/ip3/{domain}.ico")))
        .respond_with(ResponseTemplate::new(200).set_body_raw(body.to_vec(), content_type))
        .mount(server)
        .await;
}

async fn hits(server: &MockServer) -> usize {
    server.received_requests().await.unwrap_or_default().len()
}

/// An app whose icon service is the stub, and Bob signed in with logos on.
async fn setup() -> (MockServer, TestApp, i64, Client) {
    let server = MockServer::start().await;
    let app = TestApp::builder()
        .env("DUCKDUCKGO_ICONS_URL", server.uri())
        .with_admin()
        .spawn()
        .await;
    let (_, member) = app.create_member("bob", PASSWORD).await;
    enable_logos(&app, member).await;
    let client = app.login("bob", PASSWORD).await;
    (server, app, member, client)
}

fn logo_path(id: i64) -> String {
    format!("/api/accounts/{id}/institution-logo")
}

#[tokio::test]
async fn serves_the_image_with_the_type_its_bytes_have_a_sandbox_policy_and_private_caching() {
    let (server, app, member, client) = setup().await;
    icon(&server, "chase.com", PNG, "text/html").await;
    let id = bank_account(&app, member, Some("chase.com")).await;

    let response = client.get(&logo_path(id)).send().await;
    assert_eq!(response.status, StatusCode::OK);
    assert_eq!(response.body, PNG);
    assert_eq!(response.header("content-type"), Some("image/png"));
    assert_eq!(
        response.header("content-security-policy"),
        Some("default-src 'none'; sandbox")
    );
    assert_eq!(
        response.header("cache-control"),
        Some("max-age=86400, private")
    );
    assert_eq!(response.header("pragma"), None);
}

#[tokio::test]
async fn logos_off_look_like_a_missing_logo_and_fetch_nothing() {
    let server = MockServer::start().await;
    let app = TestApp::builder()
        .env("DUCKDUCKGO_ICONS_URL", server.uri())
        .with_admin()
        .spawn()
        .await;
    let (_, member) = app.create_member("bob", PASSWORD).await;
    let id = bank_account(&app, member, Some("chase.com")).await;
    let client = app.login("bob", PASSWORD).await;

    let response = client.get(&logo_path(id)).send().await;
    assert_eq!(response.status, StatusCode::NOT_FOUND);
    assert!(response.body.is_empty());
    assert_eq!(hits(&server).await, 0);
}

#[tokio::test]
async fn another_members_account_or_one_without_a_domain_has_no_logo_and_fetches_nothing() {
    let (server, app, member, client) = setup().await;
    let (_, carol) = app.create_member("carol", PASSWORD).await;
    let hers = bank_account(&app, carol, Some("chase.com")).await;
    let bare = bank_account(&app, member, None).await;
    let blank = bank_account(&app, member, Some("  ")).await;

    for id in [hers, bare, blank] {
        assert_eq!(
            client.get(&logo_path(id)).send().await.status,
            StatusCode::NOT_FOUND
        );
    }
    assert_eq!(hits(&server).await, 0);
}

#[tokio::test]
async fn two_accounts_of_one_bank_cost_one_fetch_until_the_day_has_passed() {
    let (server, app, member, client) = setup().await;
    icon(&server, "chase.com", PNG, "image/png").await;
    let first = bank_account(&app, member, Some("chase.com")).await;
    let second = bank_account(&app, member, Some("chase.com")).await;

    assert_eq!(
        client.get(&logo_path(first)).send().await.status,
        StatusCode::OK
    );
    assert_eq!(
        client.get(&logo_path(second)).send().await.status,
        StatusCode::OK
    );
    assert_eq!(hits(&server).await, 1);
    // The session expires long before the cache does: sign in again after each jump.
    app.clock.advance(Duration::from_secs(23 * 3600));
    let client = app.login("bob", PASSWORD).await;
    assert_eq!(
        client.get(&logo_path(first)).send().await.status,
        StatusCode::OK
    );
    assert_eq!(hits(&server).await, 1);
    app.clock.advance(Duration::from_secs(2 * 3600));
    let client = app.login("bob", PASSWORD).await;
    assert_eq!(
        client.get(&logo_path(first)).send().await.status,
        StatusCode::OK
    );
    assert_eq!(hits(&server).await, 2);
}

#[tokio::test]
async fn svg_html_and_oversized_icons_are_refused_and_remembered_as_misses_for_an_hour() {
    let (server, app, member, client) = setup().await;
    icon(
        &server,
        "svg.example",
        b"<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>",
        "image/png",
    )
    .await;
    icon(
        &server,
        "html.example",
        b"<!doctype html><script>alert(1)</script>",
        "image/x-icon",
    )
    .await;
    let mut big = vec![0u8; 128 * 1024 + 1];
    big[..PNG.len()].copy_from_slice(PNG);
    icon(&server, "big.example", &big, "image/png").await;
    let ids = [
        bank_account(&app, member, Some("svg.example")).await,
        bank_account(&app, member, Some("html.example")).await,
        bank_account(&app, member, Some("big.example")).await,
        bank_account(&app, member, Some("missing.example")).await,
    ];

    for id in ids {
        assert_eq!(
            client.get(&logo_path(id)).send().await.status,
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            client.get(&logo_path(id)).send().await.status,
            StatusCode::NOT_FOUND
        );
    }
    assert_eq!(hits(&server).await, 4, "each miss is fetched once");
    app.clock.advance(Duration::from_secs(61 * 60));
    let client = app.login("bob", PASSWORD).await;
    assert_eq!(
        client.get(&logo_path(ids[0])).send().await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(hits(&server).await, 5, "a miss is retried after an hour");
}

#[tokio::test]
async fn the_per_ip_bucket_refuses_the_sixty_first_request_of_a_minute() {
    let (_server, app, member, client) = setup().await;
    let bare = bank_account(&app, member, None).await;

    for _ in 0..60 {
        assert_eq!(
            client.get(&logo_path(bare)).send().await.status,
            StatusCode::NOT_FOUND
        );
    }
    let refused = client.get(&logo_path(bare)).send().await;
    assert_eq!(refused.status, StatusCode::TOO_MANY_REQUESTS);
    assert!(refused.body.is_empty());
}
