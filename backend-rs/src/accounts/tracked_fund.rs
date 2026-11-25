//! The tracked fund of an account: the ISIN of the single fund it holds. Analysis pages count
//! the balance of an eligible account as that fund, keyed on the market ticker linked to the
//! ISIN in `security_profile`, the ISIN itself when none is. The balance stays the source of
//! truth: no `account_holding` row is ever written for it.

use std::collections::HashMap;

use crate::accounts::classification::HoldingClassification;
use crate::db::{self, DbHandle};
use crate::ledger::model::{Account, AccountType, WealthTier};

/// The smallest ticker linked to the account's ISIN, other than the ISIN itself.
const LINKED_TICKER: &str = "(SELECT min(sp.ticker) FROM security_profile sp \
     WHERE sp.isin = a.tracked_isin AND sp.ticker <> a.tracked_isin)";

/// Whether the analysis may count the account as its tracked fund: one is declared, and the
/// account is neither a debt, a peer loan nor a pocket of another account.
pub fn eligible(account: &Account) -> bool {
    account.tracked_isin.is_some()
        && !account.account_type.is_liability()
        && account.account_type != AccountType::PeerLoan
        && account.parent_account_id.is_none()
}

/// Account id to fund key, for the eligible accounts without holdings: real lines win.
pub async fn keys(
    conn: &mut DbHandle<'_>,
    accounts: &[&Account],
) -> sqlx::Result<HashMap<i64, String>> {
    let ids: Vec<i64> = accounts
        .iter()
        .filter(|a| eligible(a))
        .map(|a| a.id)
        .collect();
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let rows: Vec<(i64, String)> = db::query_as::<(i64, String)>(&format!(
        "SELECT a.id, COALESCE({LINKED_TICKER}, a.tracked_isin) FROM account a \
         WHERE a.id = ANY($1) AND a.tracked_isin IS NOT NULL \
         AND NOT EXISTS (SELECT 1 FROM account_holding h WHERE h.account_id = a.id)"
    ))
    .bind(&ids)
    .fetch_all(&mut *conn)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(id, key)| (id, key.to_uppercase()))
        .collect())
}

/// Account id to the ticker its tracked ISIN resolves to, for display: only resolved tickers,
/// whatever the account's holdings or eligibility.
pub async fn tracked_tickers(
    conn: &mut DbHandle<'_>,
    accounts: &[&Account],
) -> sqlx::Result<HashMap<i64, String>> {
    let ids: Vec<i64> = accounts
        .iter()
        .filter(|a| a.tracked_isin.is_some())
        .map(|a| a.id)
        .collect();
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let rows: Vec<(i64, Option<String>)> = db::query_as::<(i64, Option<String>)>(&format!(
        "SELECT a.id, {LINKED_TICKER} FROM account a \
         WHERE a.id = ANY($1) AND a.tracked_isin IS NOT NULL"
    ))
    .bind(&ids)
    .fetch_all(&mut *conn)
    .await?;
    Ok(rows
        .into_iter()
        .filter_map(|(id, ticker)| ticker.map(|t| (id, t.to_uppercase())))
        .collect())
}

/// The fund's tier: the member's override on its key, else equity.
pub fn tier(key: &str, overrides: &HashMap<String, HoldingClassification>) -> WealthTier {
    overrides
        .get(key)
        .and_then(|o| o.wealth_tier)
        .unwrap_or(WealthTier::Equity)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn classified(tier: Option<WealthTier>) -> HoldingClassification {
        HoldingClassification {
            id: 1,
            ticker: "IWDA.AS".into(),
            wealth_tier: tier,
            sector_key: None,
            country_key: None,
        }
    }

    #[test]
    fn a_fund_is_equity_unless_the_member_says_otherwise() {
        let mut overrides = HashMap::new();
        assert_eq!(tier("IWDA.AS", &overrides), WealthTier::Equity);
        overrides.insert("IWDA.AS".to_string(), classified(None));
        assert_eq!(tier("IWDA.AS", &overrides), WealthTier::Equity);
        overrides.insert(
            "IWDA.AS".to_string(),
            classified(Some(WealthTier::SafetyNet)),
        );
        assert_eq!(tier("IWDA.AS", &overrides), WealthTier::SafetyNet);
    }
}
