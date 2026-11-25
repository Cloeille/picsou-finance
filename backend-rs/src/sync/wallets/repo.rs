//! WalletAddressRepository over wallet_address. The address is a public identifier and is
//! stored in clear, as the Java entity stores it.

use chrono::{DateTime, Utc};

use super::Chain;
use crate::db::{self, DbExec, DbFromRow};
use crate::error::{AppError, AppResult};

#[derive(Debug, Clone)]
pub struct Wallet {
    pub id: i64,
    pub chain: Chain,
    pub address: String,
    pub label: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
}

#[derive(DbFromRow)]
struct WalletRow {
    id: i64,
    chain: String,
    address: String,
    label: Option<String>,
    last_synced_at: Option<DateTime<Utc>>,
}

impl TryFrom<WalletRow> for Wallet {
    type Error = AppError;

    fn try_from(row: WalletRow) -> AppResult<Self> {
        let chain = row
            .chain
            .parse()
            .map_err(|_| AppError::internal(format!("wallet {} has an unknown chain", row.id)))?;
        Ok(Self {
            id: row.id,
            chain,
            address: row.address,
            label: row.label,
            last_synced_at: row.last_synced_at,
        })
    }
}

const COLUMNS: &str = "id, chain, address, label, last_synced_at";

pub async fn find_all_by_member<'e>(db: impl DbExec<'e>, member_id: i64) -> AppResult<Vec<Wallet>> {
    let rows: Vec<WalletRow> = db::query_as(&format!(
        "SELECT {COLUMNS} FROM wallet_address WHERE member_id = $1 ORDER BY id"
    ))
    .bind(member_id)
    .fetch_all(db)
    .await?;
    rows.into_iter().map(Wallet::try_from).collect()
}

pub async fn find_by_id_and_member<'e>(
    db: impl DbExec<'e>,
    id: i64,
    member_id: i64,
) -> AppResult<Option<Wallet>> {
    let row: Option<WalletRow> = db::query_as(&format!(
        "SELECT {COLUMNS} FROM wallet_address WHERE id = $1 AND member_id = $2"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(db)
    .await?;
    row.map(Wallet::try_from).transpose()
}

/// The member's wallet, row-locked until the caller's transaction ends.
pub async fn lock<'e>(db: impl DbExec<'e>, id: i64, member_id: i64) -> AppResult<Option<Wallet>> {
    let row: Option<WalletRow> = db::query_as(&format!(
        "SELECT {COLUMNS} FROM wallet_address WHERE id = $1 AND member_id = $2 FOR UPDATE"
    ))
    .bind(id)
    .bind(member_id)
    .fetch_optional(db)
    .await?;
    row.map(Wallet::try_from).transpose()
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    chain: Chain,
    address: &str,
    label: Option<&str>,
    now: DateTime<Utc>,
) -> AppResult<Wallet> {
    let row: WalletRow = db::query_as(&format!(
        "INSERT INTO wallet_address (member_id, chain, address, label, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $5) RETURNING {COLUMNS}"
    ))
    .bind(member_id)
    .bind(chain.as_str())
    .bind(address)
    .bind(label)
    .bind(now)
    .fetch_one(db)
    .await?;
    row.try_into()
}

pub async fn set_last_synced<'e>(
    db: impl DbExec<'e>,
    id: i64,
    now: DateTime<Utc>,
) -> AppResult<()> {
    db::query("UPDATE wallet_address SET last_synced_at = $2, updated_at = $2 WHERE id = $1")
        .bind(id)
        .bind(now)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn delete<'e>(db: impl DbExec<'e>, id: i64) -> AppResult<()> {
    db::query("DELETE FROM wallet_address WHERE id = $1")
        .bind(id)
        .execute(db)
        .await?;
    Ok(())
}
