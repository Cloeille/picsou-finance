use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::json;

#[tokio::test]
async fn an_unstated_profile_reads_empty_and_creates_nothing() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    assert_eq!(admin.get("/api/me/profile").send().await.json(), json!({}));
    let rows: i64 = sqlx::query_scalar("SELECT count(*) FROM member_profile")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn replaces_the_profile_and_derives_age_and_net_income() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let saved = admin
        .put("/api/me/profile")
        .json(&json!({"birthDate": "1990-01-16", "monthlyNetBeforeTax": 2500, "withholdingTaxRate": 7.5, "riskProfile": "DYNAMIC"}))
        .send()
        .await;
    assert_eq!(saved.status, StatusCode::OK);
    let body = saved.json();
    assert_eq!(
        body["age"], 35,
        "the birthday is tomorrow on the test clock"
    );
    assert_eq!(
        body["monthlyNetBeforeTax"].to_string(),
        "2500",
        "the response keeps the request's scale"
    );
    assert_eq!(body["monthlyNetIncome"].to_string(), "2312.50");
    let read = admin.get("/api/me/profile").send().await.json();
    assert_eq!(
        read["withholdingTaxRate"].to_string(),
        "7.50",
        "numeric(5,2) on read"
    );
    assert_eq!(read["riskProfile"], "DYNAMIC");
    let cleared = admin
        .put("/api/me/profile")
        .json(&json!({"monthlyNetBeforeTax": 2500}))
        .send()
        .await
        .json();
    assert!(
        cleared.get("monthlyNetIncome").is_none(),
        "no income without a withholding rate"
    );
    assert!(cleared.get("birthDate").is_none());
}

#[tokio::test]
async fn rejects_out_of_range_figures() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    let invalid = admin
        .put("/api/me/profile")
        .json(&json!({"birthDate": "2026-01-15", "marginalTaxRate": 100.01, "dependents": -1, "targetRetirementAge": 91}))
        .send()
        .await;
    assert_eq!(invalid.status, StatusCode::UNPROCESSABLE_ENTITY);
    let errors = &invalid.json()["errors"];
    assert_eq!(errors["birthDate"], "must be a past date");
    assert_eq!(
        errors["marginalTaxRate"],
        "must be less than or equal to 100"
    );
    assert_eq!(errors["dependents"], "must be greater than or equal to 0");
    assert_eq!(
        errors["targetRetirementAge"],
        "must be less than or equal to 90"
    );
}

#[tokio::test]
async fn an_admin_edits_a_managed_profile_but_members_stay_apart() {
    let app = TestApp::with_admin().await;
    let kid = app.create_managed_member("Kid").await;
    app.create_member("bob", "bob-password-1").await;
    let admin = app.login_admin().await;
    let put = admin
        .put(&format!("/api/me/profile?memberId={kid}"))
        .json(&json!({"dependents": 0}))
        .send()
        .await;
    assert_eq!(put.status, StatusCode::OK);
    assert_eq!(admin.get("/api/me/profile").send().await.json(), json!({}));
    assert_eq!(
        admin
            .get(&format!("/api/me/profile?memberId={kid}"))
            .send()
            .await
            .json(),
        json!({"dependents": 0})
    );
    let bob = app.login("bob", "bob-password-1").await;
    assert_eq!(
        bob.get(&format!("/api/me/profile?memberId={kid}"))
            .send()
            .await
            .json(),
        json!({})
    );
}
