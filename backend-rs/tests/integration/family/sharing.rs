//! GET/PUT /api/family/sharing: levels per resource type, manual ids validated as owned.

use picsou::testing::TestApp;
use reqwest::StatusCode;
use serde_json::json;

use super::support::{PASSWORD, account, goal};

async fn stored_ids(app: &TestApp, member_id: i64, resource_type: &str) -> Vec<i64> {
    sqlx::query_scalar(
        "SELECT resource_id FROM shared_resource WHERE owner_member_id = $1 AND resource_type = $2 ORDER BY id",
    )
    .bind(member_id)
    .bind(resource_type)
    .fetch_all(&app.state.db)
    .await
    .unwrap()
}

#[tokio::test]
async fn defaults_to_none_and_requires_the_resource_type() {
    let app = TestApp::with_admin().await;
    app.create_member("bob", PASSWORD).await;
    let bob = app.login("bob", PASSWORD).await;

    let none = bob
        .get("/api/family/sharing?resourceType=ACCOUNT")
        .send()
        .await;
    assert_eq!(none.status, StatusCode::OK);
    assert_eq!(
        none.json(),
        json!({"resourceType": "ACCOUNT", "sharingLevel": "NONE", "sharedResourceIds": []})
    );
    let missing = bob.get("/api/family/sharing").send().await;
    assert_eq!(missing.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        missing.json()["detail"],
        "Required parameter 'resourceType' is not present."
    );
}

#[tokio::test]
async fn all_reads_back_as_minus_one_and_manual_keeps_distinct_ids_in_order() {
    let app = TestApp::with_admin().await;
    let (_, member) = app.create_member("bob", PASSWORD).await;
    let first = account(&app, member, "A", "CHECKING", "1").await;
    let second = account(&app, member, "B", "SAVINGS", "2").await;
    let bob = app.login("bob", PASSWORD).await;

    let all = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "ACCOUNT", "sharingLevel": "ALL"}))
        .send()
        .await;
    assert_eq!(all.status, StatusCode::OK);
    assert!(all.body.is_empty());
    let read = bob
        .get("/api/family/sharing?resourceType=ACCOUNT")
        .send()
        .await
        .json();
    assert_eq!(read["sharingLevel"], "ALL");
    assert_eq!(read["sharedResourceIds"], json!([-1]));

    let manual = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "ACCOUNT", "sharingLevel": "MANUAL", "sharedResourceIds": [second, first, second]}))
        .send()
        .await;
    assert_eq!(manual.status, StatusCode::OK);
    let read = bob
        .get("/api/family/sharing?resourceType=ACCOUNT")
        .send()
        .await
        .json();
    assert_eq!(read["sharingLevel"], "MANUAL");
    assert_eq!(read["sharedResourceIds"], json!([second, first]));

    bob.put("/api/family/sharing")
        .json(&json!({"resourceType": "ACCOUNT", "sharingLevel": "NONE"}))
        .send()
        .await;
    assert!(
        stored_ids(&app, member, "ACCOUNT").await.is_empty(),
        "leaving MANUAL drops the stored ids"
    );
}

#[tokio::test]
async fn a_foreign_or_unknown_id_is_refused_before_anything_changes() {
    let app = TestApp::with_admin().await;
    let (_, member) = app.create_member("bob", PASSWORD).await;
    let (_, other) = app.create_member("carol", PASSWORD).await;
    let own = account(&app, member, "Mine", "CHECKING", "1").await;
    let foreign = account(&app, other, "Hers", "CHECKING", "1").await;
    let own_goal = goal(&app, member, "Trip", "1200", &[]).await;
    let foreign_goal = goal(&app, other, "Car", "9000", &[]).await;
    let bob = app.login("bob", PASSWORD).await;
    bob.put("/api/family/sharing")
        .json(&json!({"resourceType": "ACCOUNT", "sharingLevel": "MANUAL", "sharedResourceIds": [own]}))
        .send()
        .await;

    for body in [
        json!({"resourceType": "ACCOUNT", "sharingLevel": "MANUAL", "sharedResourceIds": [own, foreign]}),
        json!({"resourceType": "ACCOUNT", "sharingLevel": "MANUAL", "sharedResourceIds": [999999]}),
        json!({"resourceType": "GOAL", "sharingLevel": "MANUAL", "sharedResourceIds": [own_goal, foreign_goal]}),
    ] {
        let response = bob.put("/api/family/sharing").json(&body).send().await;
        assert_eq!(response.status, StatusCode::BAD_REQUEST);
        assert_eq!(
            response.json()["detail"],
            "One or more shared resource IDs not found"
        );
    }
    assert_eq!(stored_ids(&app, member, "ACCOUNT").await, [own]);
    assert!(stored_ids(&app, member, "GOAL").await.is_empty());

    let goals = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "GOAL", "sharingLevel": "MANUAL", "sharedResourceIds": [own_goal]}))
        .send()
        .await;
    assert_eq!(goals.status, StatusCode::OK);
    assert_eq!(stored_ids(&app, member, "GOAL").await, [own_goal]);
}

#[tokio::test]
async fn an_unknown_type_level_or_a_null_id_is_rejected() {
    let app = TestApp::with_admin().await;
    app.create_member("bob", PASSWORD).await;
    let bob = app.login("bob", PASSWORD).await;

    let bogus = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "BOGUS", "sharingLevel": "ALL"}))
        .send()
        .await;
    assert_eq!(bogus.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        bogus.json()["errors"]["resourceType"],
        "must match \"ACCOUNT|GOAL\""
    );
    let no_level = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "GOAL"}))
        .send()
        .await;
    assert_eq!(no_level.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        no_level.json()["errors"]["sharingLevel"],
        "must not be null"
    );
    let null_id = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "ACCOUNT", "sharingLevel": "MANUAL", "sharedResourceIds": [1, null]}))
        .send()
        .await;
    assert_eq!(null_id.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        null_id.json()["errors"]["sharedResourceIds[1]"],
        "must not be null"
    );
    let bad_level = bob
        .put("/api/family/sharing")
        .json(&json!({"resourceType": "GOAL", "sharingLevel": "SOME"}))
        .send()
        .await;
    assert_eq!(bad_level.status, StatusCode::BAD_REQUEST);
}
