//! The ingestion subset of CategorizationService and CategoryService.

use std::sync::Arc;

use picsou::db::AsDbHandle;
use picsou::ledger::categorize::{self, CategorizationContext};
use picsou::ledger::merchants::KnowledgeBase;
use picsou::ledger::model::{
    CategorizationRule, CategoryKind, NewTransaction, RuleMatchType, RuleSource,
};
use picsou::testing::TestApp;

use super::support::{d, member, now};

fn rule(id: i64, match_type: RuleMatchType, pattern: &str, category_id: i64) -> CategorizationRule {
    CategorizationRule {
        id,
        member_id: 1,
        match_type,
        pattern: pattern.to_owned(),
        category_id,
        priority: 0,
        source: RuleSource::User,
        created_at: now(),
    }
}

fn tx(description: &str, counterparty: Option<&str>) -> NewTransaction {
    let mut row = NewTransaction::new(1, "2026-03-15".parse().unwrap(), description, d("-10"));
    row.counterparty = counterparty.map(str::to_owned);
    row
}

#[tokio::test]
async fn ensure_seeded_creates_the_twenty_defaults_once() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    categorize::ensure_seeded(&app.state.db, alice, now())
        .await
        .unwrap();
    categorize::ensure_seeded(&app.state.db, alice, now())
        .await
        .unwrap();

    let all = categorize::categories_of_member(&app.state.db, alice, true)
        .await
        .unwrap();
    assert_eq!(all.len(), 20);
    assert_eq!(all[0].slug.as_deref(), Some("courses"));
    assert_eq!(all[0].sort_order, 0);
    assert!(all.iter().all(|c| c.is_default));
    let transfer = all
        .iter()
        .find(|c| c.slug.as_deref() == Some(categorize::TRANSFER_SLUG))
        .unwrap();
    assert_eq!(transfer.kind, CategoryKind::Transfer);
    assert_eq!(transfer.name, "Virement interne");
}

#[tokio::test]
async fn find_or_create_default_recreates_a_deleted_default_at_the_end_and_refuses_unknown_or_archived()
 {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    categorize::ensure_seeded(&app.state.db, alice, now())
        .await
        .unwrap();
    picsou::db::query("DELETE FROM category WHERE member_id = $1 AND slug = 'epargne'")
        .bind(alice)
        .execute(&app.state.db)
        .await
        .unwrap();
    picsou::db::query(
        "UPDATE category SET archived = true WHERE member_id = $1 AND slug = 'voyages'",
    )
    .bind(alice)
    .execute(&app.state.db)
    .await
    .unwrap();

    let mut conn = app.state.db.acquire().await.unwrap();
    let recreated =
        categorize::find_or_create_default(&mut conn.as_handle(), alice, "epargne", now())
            .await
            .unwrap()
            .unwrap();
    assert_eq!(recreated.sort_order, 20);
    assert_eq!(recreated.kind, CategoryKind::Transfer);
    assert!(
        categorize::find_or_create_default(&mut conn.as_handle(), alice, "voyages", now())
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        categorize::find_or_create_default(&mut conn.as_handle(), alice, "unknown", now())
            .await
            .unwrap()
            .is_none()
    );
    drop(conn);
    let by_slug = categorize::categories_by_slug(&app.state.db, alice, now())
        .await
        .unwrap();
    assert!(!by_slug.contains_key("voyages"));
    assert!(by_slug.contains_key("epargne"));
}

#[test]
fn rules_match_by_type_and_never_override_an_assigned_category() {
    let rules = vec![
        rule(1, RuleMatchType::Counterparty, "EDF", 10),
        rule(2, RuleMatchType::KeywordsAll, "carte  monoprix", 11),
        rule(3, RuleMatchType::KeywordsAny, "netflix spotify", 12),
        rule(4, RuleMatchType::Keyword, "loyer", 13),
    ];
    let mut edf = tx("PRLV SEPA", Some("edf"));
    assert!(categorize::apply(&mut edf, &rules));
    assert_eq!(edf.category_id, Some(10));

    let mut not_exact = tx("PRLV SEPA", Some("EDF ENTREPRISES"));
    assert!(!categorize::apply(&mut not_exact, &rules));

    let mut monoprix = tx("CARTE 12/03 MONOPRIX PARIS", None);
    assert!(categorize::apply(&mut monoprix, &rules));
    assert_eq!(monoprix.category_id, Some(11));

    let mut spotify = tx("Spotify AB", None);
    assert!(categorize::apply(&mut spotify, &rules));
    assert_eq!(spotify.category_id, Some(12));

    let mut assigned = tx("LOYER MARS", None);
    assigned.category_id = Some(99);
    assert!(!categorize::apply(&mut assigned, &rules));
    assert_eq!(assigned.category_id, Some(99));
}

#[tokio::test]
async fn auto_categorize_enriches_then_falls_back_to_the_brands_default_category() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let kb = Arc::new(KnowledgeBase::load(&app.state.db).await.unwrap());
    let ctx: CategorizationContext = categorize::load_context(&app.state.db, kb, alice, now())
        .await
        .unwrap();
    let courses = ctx.categories_by_slug["courses"].id;

    let mut row = tx("CB CARREFOUR MARKET 12/03", None);
    assert!(categorize::auto_categorize(&mut row, &ctx));
    assert!(row.merchant_label.is_some());
    assert!(row.merchant_brand_id.is_some());
    assert_eq!(row.category_id, Some(courses));

    let mut manual = tx("CB CARREFOUR MARKET 12/03", None);
    manual.category_id = Some(-1);
    assert!(!categorize::auto_categorize(&mut manual, &ctx));
    assert_eq!(manual.category_id, Some(-1));
    assert!(manual.merchant_label.is_some(), "enrichment still runs");
}

#[tokio::test]
async fn budget_settings_are_absent_until_saved() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    assert!(
        categorize::budget_settings_of_member(&app.state.db, alice)
            .await
            .unwrap()
            .is_none()
    );
    picsou::db::query("INSERT INTO budget_settings (member_id, cycle_start_day) VALUES ($1, 25)")
        .bind(alice)
        .execute(&app.state.db)
        .await
        .unwrap();
    let settings = categorize::budget_settings_of_member(&app.state.db, alice)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(settings.cycle_start_day, 25);
    assert_eq!(settings.ai_confidence_threshold, 75);
}

#[tokio::test]
async fn find_or_create_default_rolls_back_with_the_callers_transaction() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let mut tx = app.state.db.begin().await.unwrap();
    let created = categorize::find_or_create_default(&mut tx.as_handle(), alice, "epargne", now())
        .await
        .unwrap();
    assert!(created.is_some());
    tx.rollback().await.unwrap();

    assert!(
        categorize::categories_of_member(&app.state.db, alice, true)
            .await
            .unwrap()
            .is_empty()
    );
}
