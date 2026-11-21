use picsou::testing::TestApp;
use reqwest::StatusCode;

#[tokio::test]
async fn probes_answer_like_spring_boot() {
    let app = TestApp::spawn().await;
    let client = app.client();
    let health = client.get("/actuator/health").send().await;
    assert_eq!(health.status, StatusCode::OK);
    assert_eq!(
        health.header("content-type"),
        Some("application/vnd.spring-boot.actuator.v3+json")
    );
    assert_eq!(
        health.text(),
        r#"{"status":"UP","groups":["liveness","readiness"]}"#
    );
    assert_eq!(
        client.get("/actuator/health/liveness").send().await.text(),
        r#"{"status":"UP"}"#
    );
    assert_eq!(
        client.get("/actuator/health/readiness").send().await.text(),
        r#"{"status":"UP"}"#
    );
    assert_eq!(
        client.get("/actuator/info").send().await.text(),
        r#"{"app":{"name":"picsou","version":"dev"}}"#
    );
    let json = client
        .get("/actuator/info")
        .header("accept", "application/json")
        .send()
        .await;
    assert_eq!(json.header("content-type"), Some("application/json"));
}
