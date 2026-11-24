//! DegiroController and DegiroSyncService over HTTP, the sidecar stubbed.

use rust_decimal::Decimal;
use serde_json::json;
use wiremock::MockServer;

use super::support::{admin, answer, app_with_sidecar, count, d, fail, member, n};

const URL: &str = "DEGIRO_AUTH_URL";

fn portfolio() -> serde_json::Value {
    json!({
        "cashEur": n("100.50"),
        "positions": [
            { "isin": "", "symbol": "AAPL", "name": "Apple", "quantity": n("2"), "buyingPrice": n("100"), "currentPrice": n("150") },
            { "isin": "", "symbol": "AAPL", "name": "Apple", "quantity": n("2"), "buyingPrice": n("200"), "currentPrice": n("150") },
            { "isin": "", "symbol": "GONE", "name": "Gone", "quantity": n("0"), "buyingPrice": n("1"), "currentPrice": n("1") }
        ]
    })
}

#[tokio::test]
async fn a_login_without_totp_stores_the_session_and_imports_at_once() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p", "totpRequired": false, "sessionBlob": "blob" }),
    )
    .await;
    answer(&sidecar, "/portfolio", portfolio()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/degiro/auth/initiate")
        .json(&json!({ "username": "u", "password": "p" }))
        .send()
        .await;

    assert_eq!(response.json(), json!({ "totpRequired": false }));
    let status = client.get("/api/degiro/status").send().await.json();
    assert_eq!(status["isActive"], true);
    assert_eq!(status["status"], "ACTIVE");
    let (balance, cash): (Decimal, Decimal) =
        picsou::db::query_as("SELECT current_balance, cash_balance FROM account WHERE external_account_id = 'degiro-portfolio'")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!((balance, cash), (d("700.50"), d("100.50")));
    let (ticker, quantity, average): (String, Decimal, Decimal) =
        picsou::db::query_as("SELECT ticker, quantity, average_buy_in FROM account_holding")
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        (ticker.as_str(), quantity, average),
        ("AAPL", d("4"), d("150")),
        "VWAP merge, zero line dropped"
    );
}

#[tokio::test]
async fn sync_answers_the_account_and_an_expired_session_asks_to_reconnect() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p", "totpRequired": true }),
    )
    .await;
    answer(&sidecar, "/complete", json!({ "sessionBlob": "blob" })).await;
    answer(&sidecar, "/portfolio", portfolio()).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let initiated = client
        .post("/api/degiro/auth/initiate")
        .json(&json!({ "username": "u", "password": "p" }))
        .send()
        .await;
    assert_eq!(
        initiated.json(),
        json!({ "processId": "p", "totpRequired": true })
    );
    client
        .post("/api/degiro/auth/complete")
        .json(&json!({ "processId": "p", "code": "123456" }))
        .send()
        .await;
    let synced = client.post("/api/degiro/sync").send().await;
    assert_eq!(synced.status, 200);
    assert_eq!(synced.json()["name"], "DEGIRO");
    assert_eq!(synced.json()["type"], "COMPTE_TITRES");

    sidecar.reset().await;
    fail(&sidecar, "/portfolio", 401, json!({})).await;
    let expired = client.post("/api/degiro/sync").send().await;
    assert_eq!(expired.status, 422);
    assert_eq!(
        expired.json()["detail"],
        "Your DEGIRO session has expired. Please reconnect from the DEGIRO page."
    );
    assert_eq!(
        client.get("/api/degiro/status").send().await.json()["status"],
        "REAUTH_REQUIRED"
    );
    let again = client.post("/api/degiro/sync").send().await;
    assert_eq!(again.status, 422, "refused before calling the sidecar");
}

#[tokio::test]
async fn failures_carry_fixed_messages_and_no_code() {
    let sidecar = MockServer::start().await;
    fail(
        &sidecar,
        "/initiate",
        400,
        json!({ "detail": "INVALID_CREDENTIALS" }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;

    let rejected = client
        .post("/api/degiro/auth/initiate")
        .json(&json!({ "username": "u", "password": "p" }))
        .send()
        .await;
    let no_session = client.post("/api/degiro/sync").send().await;

    assert_eq!(rejected.status, 422);
    assert_eq!(
        rejected.json()["detail"],
        "DEGIRO authentication failed. Please check your credentials and try again."
    );
    assert!(rejected.json().get("code").is_none());
    assert_eq!(
        no_session.json()["detail"],
        "No DEGIRO session. Please connect from the DEGIRO page."
    );
}

#[tokio::test]
async fn status_and_clear_are_scoped_to_the_member() {
    let sidecar = MockServer::start().await;
    answer(
        &sidecar,
        "/initiate",
        json!({ "processId": "p", "totpRequired": false, "sessionBlob": "blob" }),
    )
    .await;
    fail(&sidecar, "/portfolio", 503, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (client, _) = admin(&app).await;
    client
        .post("/api/degiro/auth/initiate")
        .json(&json!({ "username": "u", "password": "p" }))
        .send()
        .await;
    let (other, _) = member(&app, "bob").await;

    assert_eq!(
        other.get("/api/degiro/status").send().await.json(),
        json!({ "isActive": false })
    );
    assert_eq!(other.delete("/api/degiro/session").send().await.status, 204);
    assert_eq!(count(&app, "SELECT count(*) FROM degiro_session").await, 1);
    assert_eq!(
        client.get("/api/degiro/status").send().await.json()["isActive"],
        true,
        "a failed first import keeps it"
    );
}
