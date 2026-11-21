//! AccountAccessResolverTest, on real rows.

use picsou::error::AppError;
use picsou::ledger::access;
use picsou::ledger::model::AccountType;
use picsou::testing::TestApp;

use super::support::{account_of, d, member};

async fn share(app: &TestApp, account_id: i64, member_id: i64, percent: &str) {
    sqlx::query(
        "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
         VALUES ($1, $2, $3, now(), now())",
    )
    .bind(account_id)
    .bind(member_id)
    .bind(d(percent))
    .execute(&app.state.db)
    .await
    .expect("share");
}

#[tokio::test]
async fn share_for_no_rows_owner_holds_everything_and_a_stranger_nothing() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let bob = member(&app, "bob").await;
    let house = account_of(&app, alice, AccountType::RealEstate, "400000").await;

    assert_eq!(
        access::share_for(&app.state.db, &house, alice)
            .await
            .unwrap(),
        d("100")
    );
    assert_eq!(
        access::share_for(&app.state.db, &house, bob).await.unwrap(),
        d("0")
    );
}

#[tokio::test]
async fn share_for_explicit_split_returns_each_holders_share_and_omitted_members_get_nothing() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let bob = member(&app, "bob").await;
    let carol = member(&app, "carol").await;
    let house = account_of(&app, alice, AccountType::RealEstate, "400000").await;
    share(&app, house.id, alice, "40").await;
    share(&app, house.id, bob, "30").await;

    assert_eq!(
        access::share_for(&app.state.db, &house, alice)
            .await
            .unwrap(),
        d("40")
    );
    assert_eq!(
        access::share_for(&app.state.db, &house, bob).await.unwrap(),
        d("30")
    );
    // The 30% held outside Picsou is nobody's, and an explicit split omitting a member gives
    // them nothing.
    assert_eq!(
        access::share_for(&app.state.db, &house, carol)
            .await
            .unwrap(),
        d("0")
    );
}

#[tokio::test]
async fn readable_accounts_include_co_owned_ones_once_in_creation_order() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let bob = member(&app, "bob").await;
    let alices = account_of(&app, alice, AccountType::RealEstate, "1").await;
    let bobs = account_of(&app, bob, AccountType::Checking, "1").await;
    share(&app, alices.id, alice, "50").await;
    share(&app, alices.id, bob, "50").await;
    let mut conn = app.state.db.acquire().await.unwrap();

    assert_eq!(
        access::readable_account_ids(&mut conn, bob).await.unwrap(),
        vec![alices.id, bobs.id]
    );
    assert_eq!(
        access::readable_account_ids(&mut conn, alice)
            .await
            .unwrap(),
        vec![alices.id]
    );
}

#[tokio::test]
async fn readable_accounts_skip_a_soft_deleted_co_owned_account() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let bob = member(&app, "bob").await;
    let house = account_of(&app, alice, AccountType::RealEstate, "1").await;
    share(&app, house.id, bob, "50").await;
    sqlx::query("UPDATE account SET deleted_at = now() WHERE id = $1")
        .bind(house.id)
        .execute(&app.state.db)
        .await
        .unwrap();
    let mut conn = app.state.db.acquire().await.unwrap();

    assert!(
        access::readable_account_ids(&mut conn, bob)
            .await
            .unwrap()
            .is_empty()
    );
    assert!(
        !access::is_readable(&mut conn, house.id, alice)
            .await
            .unwrap()
    );
}

#[tokio::test]
async fn guards_let_a_co_owner_read_but_not_write_and_hide_everything_else() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let bob = member(&app, "bob").await;
    let carol = member(&app, "carol").await;
    let house = account_of(&app, alice, AccountType::RealEstate, "1").await;
    share(&app, house.id, alice, "50").await;
    share(&app, house.id, bob, "50").await;
    let mut conn = app.state.db.acquire().await.unwrap();

    assert_eq!(
        access::require_readable(&mut conn, house.id, bob)
            .await
            .unwrap()
            .id,
        house.id
    );
    assert_eq!(
        access::require_owner(&mut conn, house.id, alice)
            .await
            .unwrap()
            .id,
        house.id
    );
    match access::require_owner(&mut conn, house.id, bob).await {
        Err(AppError::Forbidden(detail)) => {
            assert_eq!(detail, "Only the owning member can modify this account")
        }
        other => panic!("co-owner write must be forbidden, got {other:?}"),
    }
    for result in [
        access::require_readable(&mut conn, house.id, carol).await,
        access::require_owner(&mut conn, house.id, carol).await,
        access::require_readable(&mut conn, house.id + 1000, alice).await,
    ] {
        match result {
            Err(AppError::NotFound(detail)) => assert_eq!(detail, "Account not found"),
            other => panic!("expected the 404, got {other:?}"),
        }
    }
    assert!(access::is_readable(&mut conn, house.id, bob).await.unwrap());
    assert!(
        !access::is_readable(&mut conn, house.id, carol)
            .await
            .unwrap()
    );
    assert!(
        !access::is_readable(&mut conn, 999_999, alice)
            .await
            .unwrap()
    );
}

#[tokio::test]
async fn shares_for_resolves_a_whole_set() {
    let app = TestApp::spawn().await;
    let alice = member(&app, "alice").await;
    let bob = member(&app, "bob").await;
    let split = account_of(&app, alice, AccountType::RealEstate, "1").await;
    let whole = account_of(&app, bob, AccountType::Checking, "1").await;
    share(&app, split.id, alice, "70").await;
    share(&app, split.id, bob, "30").await;

    let shares = access::shares_for(&app.state.db, &[split.clone(), whole.clone()], bob)
        .await
        .unwrap();
    assert_eq!(shares[&split.id], d("30"));
    assert_eq!(shares[&whole.id], d("100"));
}

#[test]
fn weigh_applies_the_percentage_at_eight_places_half_up() {
    assert_eq!(access::weigh(d("400000"), d("50")), d("200000"));
    assert_eq!(
        access::weigh(d("400000"), d("33.333")).to_string(),
        "133332.00000000"
    );
    assert_eq!(access::weigh(d("-200000"), d("50")), d("-100000"));
    assert_eq!(
        access::weigh(d("0.000000015"), d("50")).to_string(),
        "0.00000001"
    );
}

#[test]
fn weigh_full_share_keeps_the_amount_and_zero_share_yields_zero() {
    assert_eq!(access::weigh(d("1234.56"), d("100")).to_string(), "1234.56");
    assert_eq!(access::weigh(d("400000"), d("0")), d("0"));
    assert_eq!(access::weigh(d("0"), d("50")), d("0"));
}
