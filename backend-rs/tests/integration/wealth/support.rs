//! Fixtures shared by the wealth tests.

use chrono::{DateTime, NaiveDate, TimeZone, Utc};
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::Account;
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
