//! Savings endpoints and the quinzaine interest projection.

use picsou::ledger::accounts::NewAccount;
use picsou::ledger::model::AccountType;
use picsou::wealth::savings::interest::project;
use picsou::wealth::savings::model::{RateBasis, SavingsConfig, SavingsProduct};
use serde_json::json;

use super::goals::Fixture;
use super::support::{account, d, day};

fn config(
    product: SavingsProduct,
    rate: &str,
    basis: RateBasis,
    tax: Option<&str>,
) -> SavingsConfig {
    SavingsConfig {
        account_id: 1,
        product,
        annual_rate: d(rate),
        rate_basis: basis,
        tax_rate_pct: tax.map(d),
        ceiling: None,
    }
}

#[test]
fn a_flat_year_at_three_percent_earns_three_hundred() {
    let p = project(
        &config(SavingsProduct::LivretA, "3.00", RateBasis::Net, None),
        d("10000"),
        &[],
        day("2025-12-31"),
    );
    assert_eq!(p.estimated_interest_ytd, d("300.00"));
    assert_eq!(p.projected_interest_full_year, d("300.00"));
    assert_eq!(p.next_capitalization_date, day("2025-12-31"));
    assert!(p.net_of_tax);
}

#[test]
fn a_deposit_earns_from_the_next_quinzaine() {
    let p = project(
        &config(SavingsProduct::LivretA, "3.00", RateBasis::Net, None),
        d("10000"),
        &[(day("2025-01-20"), d("1200"))],
        day("2025-12-31"),
    );
    assert_eq!(p.estimated_interest_ytd, d("333.00"));
}

#[test]
fn a_gross_commercial_rate_loses_the_flat_tax() {
    let p = project(
        &config(
            SavingsProduct::Commercial,
            "4.00",
            RateBasis::Gross,
            Some("30.00"),
        ),
        d("5000"),
        &[],
        day("2025-12-31"),
    );
    assert_eq!(p.estimated_interest_ytd, d("140.00"));
    assert_eq!(p.annual_rate_pct, d("2.80"));
    assert!(!p.net_of_tax);
}

#[tokio::test]
async fn suggestions_list_synced_unconfigured_books_by_name() {
    let f = Fixture::new().await;
    let mut synced = NewAccount::new(f.member, "LIVRET A M DUPONT", AccountType::Checking);
    synced.is_manual = false;
    let synced = account(&f.app, synced).await.id;
    let mut manual = NewAccount::new(f.member, "Livret A", AccountType::Checking);
    manual.is_manual = true;
    account(&f.app, manual).await;
    let body = f.admin.get("/api/savings/suggestions").send().await.json();
    let rows = body.as_array().unwrap();
    assert_eq!(rows.len(), 1, "{body}");
    assert_eq!(rows[0]["accountId"], synced);
    assert_eq!(rows[0]["suggestedProduct"], "LIVRET_A");
}

#[tokio::test]
async fn the_config_is_upserted_retypes_the_account_and_is_member_scoped() {
    let f = Fixture::new().await;
    let id = f.account(f.member, AccountType::Checking, "1000").await;
    let put = |body: serde_json::Value| {
        let client = &f.admin;
        async move {
            client
                .put(&format!("/api/accounts/{id}/savings-config"))
                .json(&body)
                .send()
                .await
        }
    };
    let response = put(json!({"product": "LIVRET_A", "annualRate": 3, "rateBasis": "GROSS"})).await;
    assert_eq!(response.status, 400);
    assert!(
        response.json()["detail"]
            .as_str()
            .unwrap()
            .starts_with("Regulated savings products (LIVRET_A)")
    );

    let response =
        put(json!({"product": "LEP", "annualRate": 4, "rateBasis": "NET", "ceiling": 10000})).await;
    assert_eq!(response.status, 200, "{}", response.text());
    let response = put(json!({"product": "LEP", "annualRate": 3.5, "rateBasis": "NET"})).await;
    assert_eq!(response.status, 200);
    let rows: i64 = picsou::db::query_scalar(
        "SELECT count(*) FROM savings_interest_config WHERE account_id = $1",
    )
    .bind(id)
    .fetch_one(&f.app.state.db)
    .await
    .unwrap();
    assert_eq!(rows, 1);
    let kind: String = picsou::db::query_scalar("SELECT type::text FROM account WHERE id = $1")
        .bind(id)
        .fetch_one(&f.app.state.db)
        .await
        .unwrap();
    assert_eq!(kind, "LEP");

    let response = f
        .admin
        .get(&format!("/api/accounts/{id}/savings-interest"))
        .send()
        .await;
    assert_eq!(response.status, 200, "{}", response.text());
    assert_eq!(response.json()["basis"], "NET");

    let response = f
        .admin
        .delete(&format!("/api/accounts/{id}/savings-config"))
        .send()
        .await;
    assert_eq!(response.status, 204);
    let response = f
        .admin
        .get(&format!("/api/accounts/{id}/savings-interest"))
        .send()
        .await;
    assert_eq!(response.status, 404);

    let (_, other) = f.app.create_member("other", "password-1234").await;
    let foreign = f.account(other, AccountType::Savings, "10").await;
    let response = f
        .admin
        .delete(&format!("/api/accounts/{foreign}/savings-config"))
        .send()
        .await;
    assert_eq!(response.status, 404);
}
