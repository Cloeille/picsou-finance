//! AccountAccessResolver and AccountOwnershipRepository: which accounts a member may see, and
//! for how much of them. Reading is broader than writing (co-owners read, owners write), and
//! shares weight reads only: stored balances and snapshots are always at 100%.

use std::collections::{HashMap, HashSet};

use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::accounts;
use super::decimal::{HALF_UP, divide};
use super::model::{Account, AccountOwnership};
use crate::db::DbExec;
use crate::error::{AppError, AppResult};

const FULL_SHARE: Decimal = Decimal::ONE_HUNDRED;

pub async fn ownerships_of_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<Vec<AccountOwnership>> {
    crate::db::query_as(
        "SELECT id, account_id, member_id, share_percent FROM account_ownership WHERE account_id = $1 ORDER BY id",
    )
    .bind(account_id)
    .fetch_all(db)
    .await
}

pub async fn ownerships_of_accounts<'e>(
    db: impl DbExec<'e>,
    account_ids: &[i64],
) -> sqlx::Result<Vec<AccountOwnership>> {
    crate::db::query_as(
        "SELECT id, account_id, member_id, share_percent FROM account_ownership WHERE account_id = ANY($1) \
         ORDER BY id",
    )
    .bind(account_ids)
    .fetch_all(db)
    .await
}

pub async fn ownerships_of_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<AccountOwnership>> {
    crate::db::query_as(
        "SELECT id, account_id, member_id, share_percent FROM account_ownership WHERE member_id = $1 ORDER BY id",
    )
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// The accounts the member holds a share of through ownership rows (deleted ones included).
pub async fn co_owned_account_ids<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<i64>> {
    crate::db::query_scalar(
        "SELECT account_id FROM account_ownership WHERE member_id = $1 ORDER BY id",
    )
    .bind(member_id)
    .fetch_all(db)
    .await
}

pub async fn delete_ownerships_of_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
) -> sqlx::Result<u64> {
    Ok(
        crate::db::query("DELETE FROM account_ownership WHERE account_id = $1")
            .bind(account_id)
            .execute(db)
            .await?
            .rows_affected(),
    )
}

/// Accounts the member owns, plus the live ones they hold a share of, in creation order.
pub async fn readable_accounts(
    conn: &mut PgConnection,
    member_id: i64,
) -> sqlx::Result<Vec<Account>> {
    let owned = accounts::find_all_by_member(&mut *conn, member_id).await?;
    let co_owned = co_owned_account_ids(&mut *conn, member_id).await?;
    if co_owned.is_empty() {
        return Ok(owned);
    }
    let owned_ids: HashSet<i64> = owned.iter().map(|a| a.id).collect();
    let extra: Vec<i64> = co_owned
        .into_iter()
        .filter(|id| !owned_ids.contains(id))
        .collect();
    if extra.is_empty() {
        return Ok(owned);
    }
    let mut result = owned;
    result.extend(accounts::find_by_ids(&mut *conn, &extra).await?);
    result.sort_by(|a, b| a.created_at.cmp(&b.created_at).then(a.id.cmp(&b.id)));
    Ok(result)
}

pub async fn readable_account_ids(
    conn: &mut PgConnection,
    member_id: i64,
) -> sqlx::Result<Vec<i64>> {
    Ok(readable_accounts(conn, member_id)
        .await?
        .into_iter()
        .map(|a| a.id)
        .collect())
}

/// The member's percentage of the account, in [0, 100]. No ownership rows means the owning
/// member holds 100%; an explicit split that omits the member means none.
pub async fn share_for<'e>(
    db: impl DbExec<'e>,
    account: &Account,
    member_id: i64,
) -> sqlx::Result<Decimal> {
    let rows = ownerships_of_account(db, account.id).await?;
    Ok(share_from(&rows, account, member_id))
}

/// share_for over a set of accounts, in one query; keyed by account id.
pub async fn shares_for<'e>(
    db: impl DbExec<'e>,
    accounts: &[Account],
    member_id: i64,
) -> sqlx::Result<HashMap<i64, Decimal>> {
    if accounts.is_empty() {
        return Ok(HashMap::new());
    }
    let ids: Vec<i64> = accounts.iter().map(|a| a.id).collect();
    let mut by_account: HashMap<i64, Vec<AccountOwnership>> = HashMap::new();
    for row in ownerships_of_accounts(db, &ids).await? {
        by_account.entry(row.account_id).or_default().push(row);
    }
    Ok(accounts
        .iter()
        .map(|a| {
            let rows = by_account.get(&a.id).map(Vec::as_slice).unwrap_or_default();
            (a.id, share_from(rows, a, member_id))
        })
        .collect())
}

fn share_from(rows: &[AccountOwnership], account: &Account, member_id: i64) -> Decimal {
    if rows.is_empty() {
        return if account.member_id == member_id {
            FULL_SHARE
        } else {
            Decimal::ZERO
        };
    }
    rows.iter()
        .find(|r| r.member_id == member_id)
        .map_or(Decimal::ZERO, |r| r.share_percent)
}

/// Applies a share to an amount, rounding as every aggregate must (8 places, half up).
pub fn weigh(amount: Decimal, share_percent: Decimal) -> Decimal {
    if amount.is_zero() || share_percent.is_zero() {
        return Decimal::ZERO;
    }
    if share_percent == FULL_SHARE {
        return amount;
    }
    divide(amount * share_percent, FULL_SHARE, 8, HALF_UP)
}

/// Read guard: owner or co-owner, else the 404 (never a 403 that would reveal the id exists).
pub async fn require_readable(
    conn: &mut PgConnection,
    account_id: i64,
    member_id: i64,
) -> AppResult<Account> {
    let account = accounts::find_by_id(&mut *conn, account_id)
        .await?
        .ok_or_else(accounts::account_not_found)?;
    if account.member_id == member_id
        || share_for(&mut *conn, &account, member_id).await? > Decimal::ZERO
    {
        return Ok(account);
    }
    Err(accounts::account_not_found())
}

/// Write guard: the administrative owner only; a co-owner gets the 403.
pub async fn require_owner(
    conn: &mut PgConnection,
    account_id: i64,
    member_id: i64,
) -> AppResult<Account> {
    let account = accounts::find_by_id(&mut *conn, account_id)
        .await?
        .ok_or_else(accounts::account_not_found)?;
    if account.member_id == member_id {
        return Ok(account);
    }
    if share_for(&mut *conn, &account, member_id).await? > Decimal::ZERO {
        return Err(AppError::Forbidden(
            "Only the owning member can modify this account".to_owned(),
        ));
    }
    Err(accounts::account_not_found())
}

pub async fn is_readable(
    conn: &mut PgConnection,
    account_id: i64,
    member_id: i64,
) -> sqlx::Result<bool> {
    let Some(account) = accounts::find_by_id(&mut *conn, account_id).await? else {
        return Ok(false);
    };
    Ok(account.member_id == member_id
        || share_for(&mut *conn, &account, member_id).await? > Decimal::ZERO)
}
