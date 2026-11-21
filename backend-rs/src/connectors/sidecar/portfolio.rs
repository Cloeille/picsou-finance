//! The write most investment sidecars end a sync with: the account upserted by external id (a
//! soft-deleted one is never resurrected), its holdings replaced wholesale and the day's snapshot.

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::error::JobResult;
use crate::AppState;
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::model::{Account, AccountType, HoldingDraft};
use crate::ledger::{holdings, snapshots};

/// One provider account as the sync writes it.
pub struct PortfolioAccount<'a> {
    pub external_id: &'a str,
    pub name: &'a str,
    pub account_type: AccountType,
    pub provider: &'static str,
    /// Set on creation only; the user may recolor the account afterwards.
    pub color: &'a str,
    pub balance_eur: Decimal,
    pub cash_balance: Option<Decimal>,
    pub invested_eur: Decimal,
}

/// None when the account was soft-deleted by the member: the sync skips it.
pub async fn write(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
    data: &PortfolioAccount<'_>,
    drafts: &[HoldingDraft],
    synced_at: DateTime<Utc>,
) -> JobResult<Option<Account>> {
    let Some(saved) = upsert_account(state, conn, member_id, data, synced_at).await? else {
        return Ok(None);
    };
    replace_holdings(
        state,
        conn,
        saved.id,
        drafts,
        data.balance_eur,
        data.invested_eur,
    )
    .await?;
    Ok(Some(saved))
}

/// The holdings replaced wholesale, then the day's snapshot.
pub async fn replace_holdings(
    state: &AppState,
    conn: &mut PgConnection,
    account_id: i64,
    drafts: &[HoldingDraft],
    balance_eur: Decimal,
    invested_eur: Decimal,
) -> JobResult<()> {
    holdings::replace_all(&mut *conn, account_id, drafts, state.clock.now()).await?;
    snapshots::upsert(
        &mut *conn,
        account_id,
        balance_eur,
        invested_eur,
        state.clock.today(),
        state.clock.now(),
    )
    .await?;
    Ok(())
}

/// Refreshes an existing account with the provider's figures.
pub async fn refresh(
    state: &AppState,
    conn: &mut PgConnection,
    mut account: Account,
    data: &PortfolioAccount<'_>,
    synced_at: DateTime<Utc>,
) -> JobResult<Account> {
    account.external_account_id = Some(data.external_id.to_owned());
    account.name = data.name.to_owned();
    account.account_type = data.account_type;
    account.provider = Some(data.provider.to_owned());
    account.currency = "EUR".to_owned();
    account.is_manual = false;
    account.current_balance = data.balance_eur;
    account.cash_balance = data.cash_balance;
    account.last_synced_at = Some(synced_at);
    Ok(accounts::update(&mut *conn, &account, state.clock.now()).await?)
}

/// Creates the provider's account.
pub async fn create(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
    data: &PortfolioAccount<'_>,
    synced_at: DateTime<Utc>,
) -> JobResult<Account> {
    let mut draft = NewAccount::new(member_id, data.name, data.account_type);
    draft.external_account_id = Some(data.external_id.to_owned());
    draft.provider = Some(data.provider.to_owned());
    draft.is_manual = false;
    draft.color = data.color.to_owned();
    draft.current_balance = data.balance_eur;
    draft.cash_balance = data.cash_balance;
    draft.last_synced_at = Some(synced_at);
    Ok(accounts::insert(&mut *conn, &draft, state.clock.now()).await?)
}

/// The account half of [`write`]: insert or refresh, skipping a soft-deleted one.
pub async fn upsert_account(
    state: &AppState,
    conn: &mut PgConnection,
    member_id: i64,
    data: &PortfolioAccount<'_>,
    synced_at: DateTime<Utc>,
) -> JobResult<Option<Account>> {
    match accounts::find_by_external_id_and_member(&mut *conn, data.external_id, member_id).await? {
        Some(account) => Ok(Some(refresh(state, conn, account, data, synced_at).await?)),
        None => {
            if accounts::exists_soft_deleted_by_external_id(&mut *conn, data.external_id, member_id)
                .await?
            {
                tracing::info!(
                    "{} skipped a soft-deleted account (member={member_id})",
                    data.provider
                );
                return Ok(None);
            }
            Ok(Some(create(state, conn, member_id, data, synced_at).await?))
        }
    }
}
