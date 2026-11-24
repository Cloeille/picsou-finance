//! The shared sidecar layer: the shared key on every request, the key challenge, the detail
//! contract and the session table, exercised through the Bourse Direct endpoints.

use picsou::ledger::sidecar::{SidecarBank, SidecarErrorCode};
use serde_json::json;
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

use super::support::{admin, app_with_sidecar};

const URL: &str = "BOURSE_DIRECT_AUTH_URL";

async fn initiate(
    sidecar: &MockServer,
    app: &picsou::testing::TestApp,
) -> picsou::testing::Response {
    let _ = sidecar;
    let (client, _) = admin(app).await;
    client
        .post("/api/bourse-direct/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await
}

async fn answering(status: u16, body: serde_json::Value) -> MockServer {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(ResponseTemplate::new(status).set_body_json(body))
        .mount(&sidecar)
        .await;
    sidecar
}

#[tokio::test]
async fn every_request_carries_the_trimmed_shared_key() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .and(header("X-Picsou-Sidecar-Key", "the-key"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({ "processId": "p", "mfaRequired": true })),
        )
        .expect(1)
        .mount(&sidecar)
        .await;
    let app = picsou::testing::TestApp::builder()
        .with_admin()
        .env(URL, sidecar.uri())
        .env("APP_SIDECAR_API_KEY", "  the-key ")
        .spawn()
        .await;

    assert_eq!(initiate(&sidecar, &app).await.status, 200);
}

#[tokio::test]
async fn no_key_header_is_sent_when_none_is_configured() {
    let sidecar = answering(200, json!({ "processId": "p", "mfaRequired": true })).await;
    let app = app_with_sidecar(URL, &sidecar).await;

    initiate(&sidecar, &app).await;

    let requests = sidecar.received_requests().await.unwrap();
    assert!(requests[0].headers.get("X-Picsou-Sidecar-Key").is_none());
}

#[tokio::test]
async fn the_sidecars_key_challenge_is_a_deployment_fault_not_a_bad_password() {
    let sidecar = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/initiate"))
        .respond_with(
            ResponseTemplate::new(401).insert_header("WWW-Authenticate", "Picsou-Sidecar-Key"),
        )
        .mount(&sidecar)
        .await;
    let app = app_with_sidecar(URL, &sidecar).await;

    let response = initiate(&sidecar, &app).await;

    assert_eq!(response.status, 422);
    let body = response.json();
    assert_eq!(body["code"], "SIDECAR_UNAUTHORIZED");
    assert!(
        body["detail"]
            .as_str()
            .unwrap()
            .contains("APP_SIDECAR_API_KEY")
    );
}

#[tokio::test]
async fn a_declared_detail_keeps_its_code_and_the_banks_wording() {
    let sidecar = answering(400, json!({ "detail": "INVALID_CREDENTIALS" })).await;
    let app = app_with_sidecar(URL, &sidecar).await;

    let body = initiate(&sidecar, &app).await.json();

    assert_eq!(body["code"], "INVALID_CREDENTIALS");
    assert_eq!(
        body["detail"],
        SidecarBank::BourseDirect
            .message(SidecarErrorCode::InvalidCredentials)
            .unwrap()
    );
}

#[tokio::test]
async fn a_code_the_bank_never_declared_falls_through_to_the_status() {
    let sidecar = answering(503, json!({ "detail": "CAPTCHA_BLOCKED" })).await;
    let app = app_with_sidecar(URL, &sidecar).await;

    let body = initiate(&sidecar, &app).await.json();

    assert_eq!(body["code"], "UPSTREAM_UNAVAILABLE");
    assert_eq!(
        body["detail"],
        SidecarBank::BourseDirect
            .message(SidecarErrorCode::UpstreamUnavailable)
            .unwrap()
    );
}

#[tokio::test]
async fn an_undeclared_code_on_a_client_error_reports_the_callers_message() {
    let sidecar = answering(400, json!({ "detail": "NOT_A_CODE" })).await;
    let app = app_with_sidecar(URL, &sidecar).await;

    let body = initiate(&sidecar, &app).await.json();

    assert_eq!(body["code"], "UPSTREAM_UNAVAILABLE");
    assert_eq!(
        body["detail"],
        "Could not initiate Bourse Direct authentication"
    );
}

#[tokio::test]
async fn a_410_maps_to_the_code_the_client_was_built_with() {
    let sidecar = answering(410, json!({})).await;
    let app = app_with_sidecar(URL, &sidecar).await;

    assert_eq!(
        initiate(&sidecar, &app).await.json()["code"],
        "AUTH_ATTEMPT_EXPIRED"
    );
}

#[tokio::test]
async fn an_unreachable_sidecar_is_unavailable() {
    let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let uri = format!("http://{}", closed.local_addr().unwrap());
    drop(closed);
    let app = picsou::testing::TestApp::builder()
        .with_admin()
        .env(URL, uri)
        .spawn()
        .await;
    let (client, _) = admin(&app).await;

    let response = client
        .post("/api/bourse-direct/auth/initiate")
        .json(&json!({ "login": "jdoe", "password": "secret" }))
        .send()
        .await;

    assert_eq!(response.status, 422);
    assert_eq!(response.json()["code"], "UPSTREAM_UNAVAILABLE");
}

#[tokio::test]
async fn a_body_larger_than_springs_default_buffer_is_decoded() {
    let sidecar = answering(
        200,
        json!({ "processId": "p", "mfaRequired": true, "mfaType": "x".repeat(400 * 1024) }),
    )
    .await;
    let app = app_with_sidecar(URL, &sidecar).await;

    let response = initiate(&sidecar, &app).await;

    assert_eq!(response.status, 200);
}

#[tokio::test]
async fn a_cleartext_sidecar_url_across_a_network_refuses_to_start() {
    let config = picsou::config::Config::load(&picsou::config::Env::from_map([
        ("JWT_SECRET", picsou::testing::JWT_SECRET),
        ("CRYPTO_ENCRYPTION_KEY", picsou::testing::CRYPTO_KEY),
        ("BOURSE_DIRECT_AUTH_URL", "http://sidecar.example.com:8001"),
    ]))
    .expect("config");
    let db = picsou::db::Db::from(picsou::db::sqlite::SqliteDb::open_in_memory().unwrap());

    let error = picsou::connectors::Connectors::new(&config, db)
        .err()
        .expect("refused");

    assert!(
        error
            .to_string()
            .contains("Bourse Direct sidecar URL is unusable"),
        "{error}"
    );
}

#[test]
fn all_17_connector_controllers_are_fully_covered() {
    use std::collections::BTreeSet;

    const CONTROLLERS: &[&str] = &[
        "AmexController",
        "AmundiController",
        "BanquePopulaireController",
        "BnpController",
        "BourseDirectController",
        "BoursoController",
        "CaisseEpargneController",
        "CicController",
        "CmesController",
        "CorumController",
        "DegiroController",
        "FortuneoController",
        "LbpController",
        "NatixisController",
        "RevolutController",
        "SofidyController",
        "TradeRepublicController",
    ];
    let var = regex::Regex::new(r"\{[^}]*\}").unwrap();
    let normalize = |p: &str| var.replace_all(p, "{}").into_owned();

    let tsv = include_str!("../../../scripts/java_endpoints.tsv");
    let expected: BTreeSet<(String, String)> = tsv
        .lines()
        .filter(|l| !l.trim().is_empty())
        .filter_map(|l| {
            let mut cols = l.split('\t');
            let method = cols.next()?;
            let path = cols.next()?;
            let controller = cols.next()?;
            CONTROLLERS
                .contains(&controller)
                .then(|| (method.to_owned(), normalize(path)))
        })
        .collect();
    assert_eq!(expected.len(), 93);

    let routes = picsou::routes::all();
    let served: BTreeSet<(String, String)> = routes
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize(p)))
        .collect();

    let missing: Vec<_> = expected.difference(&served).collect();
    assert!(missing.is_empty(), "T6 endpoints not served: {missing:?}");
}

#[tokio::test]
async fn startup_recovery_fails_the_in_flight_sync_of_every_bank() {
    let sidecar = MockServer::start().await;
    let app = app_with_sidecar(URL, &sidecar).await;
    let (_, member_id) = admin(&app).await;
    let yesterday = app.state.clock.now() - chrono::Duration::days(1);
    // La Banque Postale keeps its sessions in a table of its own.
    let banks = SidecarBank::ALL
        .iter()
        .filter(|bank| !matches!(bank, SidecarBank::Lbp));
    for bank in banks {
        let region =
            matches!(bank, SidecarBank::BanquePopulaire).then_some("ALSACE_LORRAINE_CHAMPAGNE");
        picsou::db::query(
            "INSERT INTO sidecar_session (bank, member_id, session_state, region, last_validated_at, \
             is_active, sync_status, updated_at) VALUES ($1, $2, 'state', $3, $4, true, 'RUNNING', $4)",
        )
        .bind(bank.as_str())
        .bind(member_id)
        .bind(region)
        .bind(yesterday)
        .execute(&app.state.db)
        .await
        .unwrap();
    }

    picsou::connectors::recover_interrupted_syncs(&app.state)
        .await
        .unwrap();

    let stuck: Vec<String> = picsou::db::query_scalar(
        "SELECT bank FROM sidecar_session WHERE sync_status IN ('QUEUED', 'RUNNING') ORDER BY bank",
    )
    .fetch_all(&app.state.db)
    .await
    .unwrap();
    assert!(
        stuck.is_empty(),
        "still in flight after a restart: {stuck:?}"
    );
}
