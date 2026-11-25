//! The tracked fund of an account: the ISIN of the single fund it holds. Analysis pages count
//! the balance of an eligible account as that fund, keyed on the market ticker linked to the
//! ISIN in `security_profile`, the ISIN itself when none is. The balance stays the source of
//! truth: no `account_holding` row is ever written for it.

use std::collections::{BTreeMap, BTreeSet, HashMap};

use chrono::{DateTime, Utc};

use crate::AppState;
use crate::accounts::classification::HoldingClassification;
use crate::accounts::seams::IsinResolver;
use crate::connectors::seams::SecurityIdentities;
use crate::db::{self, DbExec, DbHandle};
use crate::error::AppResult;
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

/// Asks the resolver for the ticker of `isin` and, when it names one other than the ISIN
/// itself, records the pair in `security_profile` so the analysis keys the fund on it.
pub async fn resolve_and_link(
    conn: &mut DbHandle<'_>,
    resolver: &dyn IsinResolver,
    identities: &dyn SecurityIdentities,
    isin: &str,
    now: DateTime<Utc>,
) -> AppResult<Option<String>> {
    let ticker = resolver.resolve(isin).await.ticker.trim().to_uppercase();
    if ticker.is_empty() || ticker.eq_ignore_ascii_case(isin.trim()) {
        return Ok(None);
    }
    let pair = BTreeMap::from([(ticker.clone(), isin.trim().to_uppercase())]);
    identities.record_on(conn, &pair, now).await;
    Ok(Some(ticker))
}

/// Off the request: links the saved ISIN to its ticker, then refreshes the profile of the
/// fund's key (the ticker, else the ISIN). Failures are logged, never surfaced.
pub fn spawn_resolution(state: AppState, isin: String) {
    tokio::spawn(async move {
        let linked = resolve_and_link(
            &mut state.db.pool_handle(),
            state.accounts.isin.as_ref(),
            state.connectors.seams.security_identities.as_ref(),
            &isin,
            state.clock.now(),
        )
        .await;
        let key = match linked {
            Ok(Some(ticker)) => ticker,
            Ok(None) => isin,
            Err(e) => {
                tracing::warn!("Could not link tracked fund {isin} to a ticker: {e}");
                isin
            }
        };
        state
            .accounts
            .analysis
            .profile_runner
            .refresh_stale(&state.db, &BTreeSet::from([key]), 1)
            .await;
    });
}

/// The weekly retry: links every tracked ISIN of a live account that has no ticker yet.
/// Returns how many were newly linked.
pub async fn resolve_pending(state: &AppState) -> usize {
    let pending: Vec<String> = match db::query_scalar::<String>(&format!(
        "SELECT DISTINCT a.tracked_isin FROM account a \
         WHERE a.tracked_isin IS NOT NULL AND a.deleted_at IS NULL AND {LINKED_TICKER} IS NULL"
    ))
    .fetch_all(&state.db)
    .await
    {
        Ok(isins) => isins,
        Err(e) => {
            tracing::warn!("Could not list the tracked funds to link: {e}");
            return 0;
        }
    };
    let mut linked = 0;
    for isin in pending {
        match resolve_and_link(
            &mut state.db.pool_handle(),
            state.accounts.isin.as_ref(),
            state.connectors.seams.security_identities.as_ref(),
            &isin,
            state.clock.now(),
        )
        .await
        {
            Ok(Some(_)) => linked += 1,
            Ok(None) => {}
            Err(e) => tracing::warn!("Could not link tracked fund {isin} to a ticker: {e}"),
        }
    }
    linked
}

/// The keys of the live tracked accounts (linked ticker, else ISIN), for the profile refresh.
pub async fn profile_tickers<'e>(db: impl DbExec<'e>) -> sqlx::Result<Vec<String>> {
    let keys: Vec<String> = db::query_scalar::<String>(&format!(
        "SELECT DISTINCT COALESCE({LINKED_TICKER}, a.tracked_isin) FROM account a \
         WHERE a.tracked_isin IS NOT NULL AND a.deleted_at IS NULL"
    ))
    .fetch_all(db)
    .await?;
    let unique: BTreeSet<String> = keys.into_iter().map(|k| k.to_uppercase()).collect();
    Ok(unique.into_iter().collect())
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
