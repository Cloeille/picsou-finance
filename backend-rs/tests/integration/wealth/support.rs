//! Fixtures shared by the wealth tests.

use chrono::{DateTime, NaiveDate, TimeZone, Utc};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::{Account, AccountType};
use picsou::testing::TestApp;
use rust_decimal::Decimal;

pub fn d(value: &str) -> Decimal {
    value.parse().expect("decimal literal")
}

pub fn day(value: &str) -> NaiveDate {
    value.parse().expect("date literal")
}

pub fn now() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 3, 15, 10, 0, 0).unwrap()
}

pub async fn account(app: &TestApp, draft: NewAccount) -> Account {
    accounts::insert(&app.state.db, &draft, now())
        .await
        .expect("account")
}

pub async fn account_of(
    app: &TestApp,
    member_id: i64,
    account_type: AccountType,
    balance: &str,
) -> Account {
    let mut draft = NewAccount::new(member_id, "Account", account_type);
    draft.current_balance = d(balance);
    account(app, draft).await
}
