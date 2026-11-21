//! The SCPI reconciliation the provider syncs run: create, adopt by name, update, tombstone,
//! ambiguity, missing at the provider.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::testing::TestApp;
use picsou::wealth::scpi::Provider;
use picsou::wealth::scpi::reconciler::{
    Outcome, SyncedFund, normalize, reconcile, withdrawal_value,
};

use super::support::{account, d};

fn fund(code: &str, label: &str, quantity: &str, withdrawal: Option<&str>) -> SyncedFund {
    SyncedFund {
        fund_code: code.to_owned(),
        label: Some(label.to_owned()),
        quantity: d(quantity),
        subscription_price: Some(d("200")),
        withdrawal_price: withdrawal.map(d),
        management_company: None,
        isin: None,
        annual_distribution_per_share: Some(d("12")),
        distribution_frequency: Some("MONTHLY".to_owned()),
    }
}

#[test]
fn names_fold_accents_and_punctuation_and_values_round_to_eight_places() {
    assert_eq!(normalize(Some("  CORUM Éurion – XL ")), "corum eurion xl");
    assert_eq!(
        withdrawal_value(Some(d("3")), Some(d("181.333333333")))
            .unwrap()
            .to_string(),
        "544.00000000"
    );
    assert_eq!(
        withdrawal_value(Some(d("0")), None).unwrap().to_string(),
        "0.00000000"
    );
    assert!(withdrawal_value(Some(d("3")), None).is_none());
}

#[tokio::test]
async fn a_sync_adopts_creates_updates_and_reports_the_missing_fund() {
    let app = TestApp::with_admin().await;
    let member: i64 = sqlx::query_scalar("SELECT member_id FROM app_user WHERE username = 'admin'")
        .fetch_one(&app.state.db)
        .await
        .unwrap();
    let mine = account(
        &app,
        NewAccount::new(member, "Corum Origin", AccountType::Scpi),
    )
    .await
    .id;
    let now = app.state.clock.now();
    let mut tx = app.state.db.begin().await.unwrap();
    let first = reconcile(
        &mut tx,
        member,
        Provider::Corum,
        &[
            fund("CO", "CORUM ORIGIN", "10", Some("1000")),
            fund("XL", "CORUM XL", "5", None),
        ],
        &[],
        now,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    assert_eq!(
        first,
        vec![
            ("CO".to_owned(), Outcome::Linked),
            ("XL".to_owned(), Outcome::Created)
        ]
    );
    let balance: String =
        sqlx::query_scalar("SELECT current_balance::text FROM account WHERE id = $1")
            .bind(mine)
            .fetch_one(&app.state.db)
            .await
            .unwrap();
    assert_eq!(d(&balance), d("10000"));

    let mut tx = app.state.db.begin().await.unwrap();
    let second = reconcile(
        &mut tx,
        member,
        Provider::Corum,
        &[fund("CO", "CORUM ORIGIN", "11", Some("1000"))],
        &[],
        now,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    assert_eq!(
        second,
        vec![
            ("CO".to_owned(), Outcome::Updated),
            ("XL".to_owned(), Outcome::MissingAtProvider)
        ]
    );
    let statuses: Vec<(String, String)> =
        sqlx::query_as("SELECT fund_code, last_outcome FROM scpi_provider_fund ORDER BY fund_code")
            .fetch_all(&app.state.db)
            .await
            .unwrap();
    assert_eq!(
        statuses,
        vec![
            ("CO".to_owned(), "UPDATED".to_owned()),
            ("XL".to_owned(), "MISSING_AT_PROVIDER".to_owned())
        ]
    );
}
