//! AccountRepository: account rows, always without the soft-deleted ones unless a query says
//! otherwise. Member scoping is the caller's argument; co-owned reads go through access.rs.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;

use super::model::{ACCOUNT_COLUMNS, ACCOUNT_RETURNING, Account, AccountType, CardNature};
use crate::db::DbExec;
use crate::error::{AppError, AppResult};

/// AccountService.getOrThrow: the member's own account, or the 404 "Account not found".
pub async fn get_or_throw<'e>(db: impl DbExec<'e>, id: i64, member_id: i64) -> AppResult<Account> {
    find_by_id_and_member(db, id, member_id)
        .await?
        .ok_or_else(account_not_found)
}

/// ResourceNotFoundException.account: the same body whichever account was asked for.
pub fn account_not_found() -> AppError {
    AppError::NotFound("Account not found".to_owned())
}

fn select(filter: &str) -> String {
    format!("SELECT {ACCOUNT_COLUMNS} FROM account a WHERE a.deleted_at IS NULL AND {filter}")
}

pub async fn find_by_id<'e>(db: impl DbExec<'e>, id: i64) -> sqlx::Result<Option<Account>> {
    crate::db::query_as(&select("a.id = $1"))
        .bind(id)
        .fetch_optional(db)
        .await
}

pub async fn find_by_id_and_member<'e>(
    db: impl DbExec<'e>,
    id: i64,
    member_id: i64,
) -> sqlx::Result<Option<Account>> {
    crate::db::query_as(&select("a.id = $1 AND a.member_id = $2"))
        .bind(id)
        .bind(member_id)
        .fetch_optional(db)
        .await
}

/// findAllById: the live accounts among ids, in creation order.
pub async fn find_by_ids<'e>(db: impl DbExec<'e>, ids: &[i64]) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.created_at, a.id",
        select("a.id = ANY($1)")
    ))
    .bind(ids)
    .fetch_all(db)
    .await
}

pub async fn find_by_ids_and_member<'e>(
    db: impl DbExec<'e>,
    ids: &[i64],
    member_id: i64,
) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.created_at, a.id",
        select("a.id = ANY($1) AND a.member_id = $2")
    ))
    .bind(ids)
    .bind(member_id)
    .fetch_all(db)
    .await
}

pub async fn find_all_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.created_at, a.id",
        select("a.member_id = $1")
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// The member's accounts that are not hidden, in creation order.
pub async fn find_visible_by_member<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.created_at, a.id",
        select("a.member_id = $1 AND a.hidden = false")
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

pub async fn find_by_external_id_and_member<'e>(
    db: impl DbExec<'e>,
    external_account_id: &str,
    member_id: i64,
) -> sqlx::Result<Option<Account>> {
    crate::db::query_as(&select("a.external_account_id = $1 AND a.member_id = $2"))
        .bind(external_account_id)
        .bind(member_id)
        .fetch_optional(db)
        .await
}

pub async fn find_all_by_member_and_provider<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    provider: &str,
) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.id",
        select("a.member_id = $1 AND a.provider = $2")
    ))
    .bind(member_id)
    .bind(provider)
    .fetch_all(db)
    .await
}

/// The provider's accounts of the member, row-locked until the transaction ends.
pub async fn find_all_by_member_and_provider_for_update<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    provider: &str,
) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.id FOR UPDATE",
        select("a.member_id = $1 AND a.provider = $2")
    ))
    .bind(member_id)
    .bind(provider)
    .fetch_all(db)
    .await
}

pub async fn find_by_iban_and_member<'e>(
    db: impl DbExec<'e>,
    iban: &str,
    member_id: i64,
) -> sqlx::Result<Option<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.id",
        select("a.iban = $1 AND a.member_id = $2")
    ))
    .bind(iban)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

pub async fn find_by_iban_currency_and_member<'e>(
    db: impl DbExec<'e>,
    iban: &str,
    currency: &str,
    member_id: i64,
) -> sqlx::Result<Option<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.id",
        select("a.iban = $1 AND a.currency = $2 AND a.member_id = $3")
    ))
    .bind(iban)
    .bind(currency)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

pub async fn exists_by_iban_currency_and_member_other_than<'e>(
    db: impl DbExec<'e>,
    iban: &str,
    currency: &str,
    member_id: i64,
    id: i64,
) -> sqlx::Result<bool> {
    crate::db::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM account WHERE deleted_at IS NULL AND iban = $1 AND currency = $2 \
         AND member_id = $3 AND id <> $4)",
    )
    .bind(iban)
    .bind(currency)
    .bind(member_id)
    .bind(id)
    .fetch_one(db)
    .await
}

pub async fn find_first_by_iban_member_and_providers<'e>(
    db: impl DbExec<'e>,
    iban: &str,
    member_id: i64,
    providers: &[&str],
) -> sqlx::Result<Option<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.id LIMIT 1",
        select("a.iban = $1 AND a.member_id = $2 AND a.provider = ANY($3)")
    ))
    .bind(iban)
    .bind(member_id)
    .bind(providers)
    .fetch_optional(db)
    .await
}

/// Whether the member deleted an account with this external id: a sync must not resurrect it.
pub async fn exists_soft_deleted_by_external_id<'e>(
    db: impl DbExec<'e>,
    external_account_id: &str,
    member_id: i64,
) -> sqlx::Result<bool> {
    crate::db::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM account WHERE external_account_id = $1 AND member_id = $2 \
         AND deleted_at IS NOT NULL)",
    )
    .bind(external_account_id)
    .bind(member_id)
    .fetch_one(db)
    .await
}

pub async fn exists_soft_deleted_by_external_ids_and_name<'e>(
    db: impl DbExec<'e>,
    external_account_ids: &[String],
    name: &str,
    member_id: i64,
) -> sqlx::Result<bool> {
    crate::db::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM account WHERE external_account_id = ANY($1) AND name = $2 \
         AND member_id = $3 AND deleted_at IS NOT NULL)",
    )
    .bind(external_account_ids)
    .bind(name)
    .bind(member_id)
    .fetch_one(db)
    .await
}

/// Whether a soft-deleted account of the member matches the IBAN, and the currency and the
/// providers when given.
pub async fn exists_soft_deleted_by_iban<'e>(
    db: impl DbExec<'e>,
    iban: &str,
    member_id: i64,
    currency: Option<&str>,
    providers: Option<&[&str]>,
) -> sqlx::Result<bool> {
    crate::db::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM account WHERE iban = $1 AND member_id = $2 \
         AND ($3::text IS NULL OR currency = $3) AND ($4::text[] IS NULL OR provider = ANY($4)) \
         AND deleted_at IS NOT NULL)",
    )
    .bind(iban)
    .bind(member_id)
    .bind(currency)
    .bind(providers)
    .fetch_one(db)
    .await
}

/// Revolut wallets (not their pockets) of the member.
pub async fn find_revolut_wallets<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
) -> sqlx::Result<Vec<Account>> {
    crate::db::query_as(&format!(
        "{} ORDER BY a.id",
        select("a.member_id = $1 AND a.provider = 'Revolut' AND a.parent_account_id IS NULL")
    ))
    .bind(member_id)
    .fetch_all(db)
    .await
}

/// Undeletes every soft-deleted account of the provider for the member (Trade Republic, Revolut).
pub async fn restore_soft_deleted_by_provider<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    provider: &str,
) -> sqlx::Result<u64> {
    Ok(crate::db::query(
        "UPDATE account SET deleted_at = NULL WHERE member_id = $1 AND provider = $2 AND deleted_at IS NOT NULL",
    )
    .bind(member_id)
    .bind(provider)
    .execute(db)
    .await?
    .rows_affected())
}

/// Undeletes the one Revolut account matching the external id, or the IBAN and currency.
pub async fn restore_soft_deleted_revolut_account<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    external_account_id: &str,
    iban: Option<&str>,
    currency: &str,
) -> sqlx::Result<u64> {
    Ok(crate::db::query(
        "UPDATE account SET deleted_at = NULL WHERE member_id = $1 AND provider = 'Revolut' \
         AND deleted_at IS NOT NULL AND (external_account_id = $2 OR ($3::text IS NOT NULL AND iban = $3 \
         AND currency = $4))",
    )
    .bind(member_id)
    .bind(external_account_id)
    .bind(iban)
    .bind(currency)
    .execute(db)
    .await?
    .rows_affected())
}

/// Distinct non-blank account tickers of live accounts of the type, or of every other type.
pub async fn distinct_tickers_by_type<'e>(
    db: impl DbExec<'e>,
    account_type: AccountType,
    exclude: bool,
) -> sqlx::Result<Vec<String>> {
    let op = if exclude { "<>" } else { "=" };
    crate::db::query_scalar(&format!(
        "SELECT DISTINCT ticker FROM account WHERE ticker IS NOT NULL AND length(trim(ticker)) > 0 \
         AND deleted_at IS NULL AND type {op} $1::account_type ORDER BY ticker"
    ))
    .bind(account_type)
    .fetch_all(db)
    .await
}

/// The columns of a new account; the builder defaults are Java's (EUR, manual, #6366f1).
#[derive(Debug, Clone, PartialEq)]
pub struct NewAccount {
    pub member_id: i64,
    pub name: String,
    pub account_type: AccountType,
    pub provider: Option<String>,
    pub currency: String,
    pub current_balance: Decimal,
    pub cash_balance: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub external_account_id: Option<String>,
    pub is_manual: bool,
    pub color: String,
    pub iban: Option<String>,
    pub ticker: Option<String>,
    pub logo_url: Option<String>,
    pub logo_key: Option<String>,
    pub institution_name: Option<String>,
    pub institution_domain: Option<String>,
    pub requisition_id: Option<i64>,
    pub parent_account_id: Option<i64>,
    pub card_nature: Option<CardNature>,
    pub opened_at: Option<NaiveDate>,
}

impl NewAccount {
    pub fn new(member_id: i64, name: impl Into<String>, account_type: AccountType) -> Self {
        Self {
            member_id,
            name: name.into(),
            account_type,
            provider: None,
            currency: "EUR".to_owned(),
            current_balance: Decimal::ZERO,
            cash_balance: None,
            last_synced_at: None,
            external_account_id: None,
            is_manual: true,
            color: "#6366f1".to_owned(),
            iban: None,
            ticker: None,
            logo_url: None,
            logo_key: None,
            institution_name: None,
            institution_domain: None,
            requisition_id: None,
            parent_account_id: None,
            card_nature: None,
            opened_at: None,
        }
    }
}

pub async fn insert<'e>(
    db: impl DbExec<'e>,
    account: &NewAccount,
    now: DateTime<Utc>,
) -> sqlx::Result<Account> {
    crate::db::query_as(&format!(
        "INSERT INTO account (member_id, name, type, provider, currency, current_balance, cash_balance, \
         last_synced_at, external_account_id, is_manual, color, iban, ticker, logo_url, logo_key, institution_name, \
         institution_domain, requisition_id, parent_account_id, card_nature, opened_at, hidden, created_at, updated_at) \
         VALUES ($1, $2, $3::account_type, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, \
         $20, $21, false, $22, $22) RETURNING {ACCOUNT_RETURNING}"
    ))
    .bind(account.member_id)
    .bind(&account.name)
    .bind(account.account_type)
    .bind(&account.provider)
    .bind(&account.currency)
    .bind(account.current_balance)
    .bind(account.cash_balance)
    .bind(account.last_synced_at)
    .bind(&account.external_account_id)
    .bind(account.is_manual)
    .bind(&account.color)
    .bind(&account.iban)
    .bind(&account.ticker)
    .bind(&account.logo_url)
    .bind(&account.logo_key)
    .bind(&account.institution_name)
    .bind(&account.institution_domain)
    .bind(account.requisition_id)
    .bind(account.parent_account_id)
    .bind(account.card_nature)
    .bind(account.opened_at)
    .bind(now)
    .fetch_one(db)
    .await
}

/// Writes every mutable column of the row back (JPA save of a loaded entity), stamping updated_at.
pub async fn update<'e>(
    db: impl DbExec<'e>,
    account: &Account,
    now: DateTime<Utc>,
) -> sqlx::Result<Account> {
    crate::db::query_as(&format!(
        "UPDATE account SET member_id = $2, name = $3, type = $4::account_type, provider = $5, \
         currency = $6, current_balance = $7, cash_balance = $8, last_synced_at = $9, payment_due_amount = $10, \
         payment_due_date = $11, card_nature = $12, reward_points = $13, external_account_id = $14, is_manual = $15, \
         color = $16, iban = $17, ticker = $18, logo_url = $19, logo_key = $20, institution_name = $21, \
         institution_domain = $22, requisition_id = $23, opened_at = $24, provider_invested_eur = $25, \
         provider_paid_in_eur = $26, scheduled_payment_eur = $27, scheduled_payment_cadence = $28, deleted_at = $29, \
         parent_account_id = $30, hidden = $31, updated_at = $32 WHERE id = $1 RETURNING {ACCOUNT_RETURNING}"
    ))
    .bind(account.id)
    .bind(account.member_id)
    .bind(&account.name)
    .bind(account.account_type)
    .bind(&account.provider)
    .bind(&account.currency)
    .bind(account.current_balance)
    .bind(account.cash_balance)
    .bind(account.last_synced_at)
    .bind(account.payment_due_amount)
    .bind(account.payment_due_date)
    .bind(account.card_nature)
    .bind(account.reward_points)
    .bind(&account.external_account_id)
    .bind(account.is_manual)
    .bind(&account.color)
    .bind(&account.iban)
    .bind(&account.ticker)
    .bind(&account.logo_url)
    .bind(&account.logo_key)
    .bind(&account.institution_name)
    .bind(&account.institution_domain)
    .bind(account.requisition_id)
    .bind(account.opened_at)
    .bind(account.provider_invested_eur)
    .bind(account.provider_paid_in_eur)
    .bind(account.scheduled_payment_eur)
    .bind(account.scheduled_payment_cadence)
    .bind(account.deleted_at)
    .bind(account.parent_account_id)
    .bind(account.hidden)
    .bind(now)
    .fetch_one(db)
    .await
}

/// Sets the balance and the sync instant only, the write every sync ends with.
pub async fn set_balance<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    balance: Decimal,
    synced_at: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    crate::db::query(
        "UPDATE account SET current_balance = $2, last_synced_at = COALESCE($3, last_synced_at), updated_at = $4 \
         WHERE id = $1",
    )
    .bind(account_id)
    .bind(balance)
    .bind(synced_at)
    .bind(now)
    .execute(db)
    .await?;
    Ok(())
}
