//! ScpiFundReconciler: the funds a provider sync reports (CORUM, Sofidy, Louve; tracks T5/T6
//! call it) matched to the member's SCPI accounts: updated when linked, adopted when one
//! unlinked account carries the fund's name, created otherwise; tombstoned funds are left alone
//! and the known funds the provider no longer lists are reported missing.

use std::collections::{BTreeMap, HashSet};

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;
use unicode_normalization::UnicodeNormalization;

use super::Provider;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::AccountType;

const ACCOUNT_NAME_MAX: usize = 100;
const LABEL_MAX: usize = 200;
const MANAGEMENT_COMPANY_MAX: usize = 100;
const SCPI_COLOR: &str = "#7c3aed";
const MONEY_SCALE: u32 = 8;

impl Provider {
    pub fn display_name(self) -> &'static str {
        match self {
            Self::Corum => "CORUM",
            Self::Sofidy => "Sofidy",
            Self::Louve => "Louve Invest",
        }
    }

    /// A distributor sells several companies' funds, so its accounts read "<provider> <fund>".
    pub fn distributor(self) -> bool {
        self == Self::Louve
    }
}

/// ScpiFundOutcome, stored as its name.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Created,
    Linked,
    Updated,
    PriceIncomplete,
    Ambiguous,
    Ignored,
    MissingAtProvider,
    UnsupportedOwnership,
}

impl Outcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Created => "CREATED",
            Self::Linked => "LINKED",
            Self::Updated => "UPDATED",
            Self::PriceIncomplete => "PRICE_INCOMPLETE",
            Self::Ambiguous => "AMBIGUOUS",
            Self::Ignored => "IGNORED",
            Self::MissingAtProvider => "MISSING_AT_PROVIDER",
            Self::UnsupportedOwnership => "UNSUPPORTED_OWNERSHIP",
        }
    }
}

/// A fund as the provider reports it.
#[derive(Debug, Clone, PartialEq)]
pub struct SyncedFund {
    pub fund_code: String,
    pub label: Option<String>,
    pub quantity: Decimal,
    pub subscription_price: Option<Decimal>,
    pub withdrawal_price: Option<Decimal>,
    pub management_company: Option<String>,
    pub isin: Option<String>,
    pub annual_distribution_per_share: Option<Decimal>,
    /// MONTHLY or QUARTERLY.
    pub distribution_frequency: Option<String>,
}

/// A fund held in a way Picsou cannot value (e.g. dismembered ownership).
#[derive(Debug, Clone, PartialEq)]
pub struct UnsupportedFund {
    pub fund_code: String,
    pub label: Option<String>,
}

fn truncate(value: &str, max: usize) -> String {
    let units: Vec<u16> = value.encode_utf16().collect();
    if units.len() <= max {
        value.to_owned()
    } else {
        String::from_utf16_lossy(&units[..max])
    }
}

/// Lower-case, accents and punctuation folded to single spaces.
pub fn normalize(value: Option<&str>) -> String {
    let folded: String = value
        .unwrap_or("")
        .nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .collect::<String>()
        .to_lowercase();
    let mut out = String::new();
    for c in folded.chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            out.push(c);
        } else if !out.ends_with(' ') {
            out.push(' ');
        }
    }
    out.trim().to_owned()
}

/// ScpiPositionService.withdrawalValue: shares x withdrawal price at scale 8; zero shares are
/// worth zero even without a price.
pub fn withdrawal_value(
    share_count: Option<Decimal>,
    withdrawal_price: Option<Decimal>,
) -> Option<Decimal> {
    let shares = share_count?;
    if shares.is_zero() {
        return Some(set_scale(Decimal::ZERO, MONEY_SCALE, HALF_UP));
    }
    Some(set_scale(shares * withdrawal_price?, MONEY_SCALE, HALF_UP))
}

#[derive(sqlx::FromRow)]
struct FundRow {
    id: i64,
    ignored: bool,
}

async fn fund_row(
    conn: &mut PgConnection,
    member_id: i64,
    provider: Provider,
    fund_code: &str,
    label: Option<&str>,
    now: DateTime<Utc>,
) -> sqlx::Result<FundRow> {
    let label = label
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(|l| truncate(l, LABEL_MAX));
    sqlx::query_as(
        "INSERT INTO scpi_provider_fund (member_id, provider, fund_code, label, created_at, updated_at) \
         VALUES ($1, $2, $3, $4, $5, $5) ON CONFLICT (member_id, provider, fund_code) \
         DO UPDATE SET label = COALESCE(EXCLUDED.label, scpi_provider_fund.label), updated_at = $5 \
         RETURNING id, ignored",
    )
    .bind(member_id)
    .bind(provider.as_str())
    .bind(fund_code)
    .bind(label)
    .bind(now)
    .fetch_one(conn)
    .await
}

async fn record(
    conn: &mut PgConnection,
    row_id: i64,
    outcome: Outcome,
    seen_at: Option<DateTime<Utc>>,
    synced_at: DateTime<Utc>,
) -> sqlx::Result<()> {
    sqlx::query(
        "UPDATE scpi_provider_fund SET last_outcome = $2, last_seen_at = COALESCE($3, last_seen_at), \
         last_synced_at = $4, updated_at = $4 WHERE id = $1",
    )
    .bind(row_id)
    .bind(outcome.as_str())
    .bind(seen_at)
    .bind(synced_at)
    .execute(conn)
    .await
    .map(|_| ())
}

/// reconcile: one outcome per fund code, in the order reported, then the missing ones.
pub async fn reconcile(
    conn: &mut PgConnection,
    member_id: i64,
    provider: Provider,
    funds: &[SyncedFund],
    unsupported: &[UnsupportedFund],
    synced_at: DateTime<Utc>,
) -> AppResult<Vec<(String, Outcome)>> {
    let mut outcomes: Vec<(String, Outcome)> = Vec::new();
    let mut listed: HashSet<String> = HashSet::new();
    let mut claimed: HashSet<i64> = HashSet::new();
    for fund in funds {
        listed.insert(fund.fund_code.clone());
        let row = fund_row(
            conn,
            member_id,
            provider,
            &fund.fund_code,
            fund.label.as_deref(),
            synced_at,
        )
        .await?;
        let outcome = apply_fund(
            conn,
            member_id,
            provider,
            fund,
            row.ignored,
            &mut claimed,
            synced_at,
        )
        .await?;
        record(conn, row.id, outcome, Some(synced_at), synced_at).await?;
        outcomes.push((fund.fund_code.clone(), outcome));
    }
    for fund in unsupported {
        if !listed.insert(fund.fund_code.clone()) {
            return Err(AppError::BadRequest(
                "A fund is listed both as supported and unsupported".to_owned(),
            ));
        }
        let row = fund_row(
            conn,
            member_id,
            provider,
            &fund.fund_code,
            fund.label.as_deref(),
            synced_at,
        )
        .await?;
        let outcome = if row.ignored {
            Outcome::Ignored
        } else {
            Outcome::UnsupportedOwnership
        };
        record(conn, row.id, outcome, Some(synced_at), synced_at).await?;
        outcomes.push((fund.fund_code.clone(), outcome));
    }
    report_missing(conn, member_id, provider, &listed, synced_at, &mut outcomes).await?;
    Ok(outcomes)
}

async fn apply_fund(
    conn: &mut PgConnection,
    member_id: i64,
    provider: Provider,
    fund: &SyncedFund,
    ignored: bool,
    claimed: &mut HashSet<i64>,
    now: DateTime<Utc>,
) -> AppResult<Outcome> {
    if ignored {
        return Ok(Outcome::Ignored);
    }
    let column = provider.fund_code_column();
    let linked: Option<(i64, i64)> = sqlx::query_as(&format!(
        "SELECT id, account_id FROM scpi_position WHERE member_id = $1 AND {column} = $2 ORDER BY id LIMIT 1"
    ))
    .bind(member_id)
    .bind(&fund.fund_code)
    .fetch_optional(&mut *conn)
    .await?;
    if let Some((position_id, account_id)) = linked {
        let complete = apply(conn, position_id, account_id, fund, now).await?;
        claimed.insert(account_id);
        return Ok(if complete {
            Outcome::Updated
        } else {
            Outcome::PriceIncomplete
        });
    }

    let candidates =
        adoption_candidates(conn, member_id, provider, fund.label.as_deref(), claimed).await?;
    if candidates.len() > 1 {
        tracing::info!(
            "Several SCPI accounts match this {} fund, none adopted (member={member_id}; fund={}; matches={})",
            provider.as_str(),
            fund.fund_code,
            candidates.len()
        );
        return Ok(Outcome::Ambiguous);
    }
    let (account_id, outcome) = match candidates.first() {
        Some(id) => (*id, Outcome::Linked),
        None => {
            let name = fund
                .label
                .as_deref()
                .map(str::trim)
                .filter(|l| !l.is_empty())
                .map(str::to_owned)
                .unwrap_or_else(|| format!("{} {}", provider.display_name(), fund.fund_code));
            let mut draft = NewAccount::new(
                member_id,
                truncate(&name, ACCOUNT_NAME_MAX),
                AccountType::Scpi,
            );
            draft.provider = Some(provider.display_name().to_owned());
            draft.currency = "EUR".to_owned();
            draft.is_manual = true;
            draft.color = SCPI_COLOR.to_owned();
            (
                accounts::insert(&mut *conn, &draft, now).await?.id,
                Outcome::Created,
            )
        }
    };
    let existing: Option<i64> =
        sqlx::query_scalar("SELECT id FROM scpi_position WHERE account_id = $1 AND member_id = $2")
            .bind(account_id)
            .bind(member_id)
            .fetch_optional(&mut *conn)
            .await?;
    let position_id = match existing {
        Some(id) => id,
        None => {
            sqlx::query_scalar(
                "INSERT INTO scpi_position (account_id, member_id, management_company, share_count, created_at, updated_at) \
                 VALUES ($1, $2, $3, $4, $5, $5) RETURNING id",
            )
            .bind(account_id)
            .bind(member_id)
            .bind((!provider.distributor()).then(|| provider.display_name()))
            .bind(fund.quantity)
            .bind(now)
            .fetch_one(&mut *conn)
            .await?
        }
    };
    sqlx::query(&format!(
        "UPDATE scpi_position SET {column} = $2 WHERE id = $1"
    ))
    .bind(position_id)
    .bind(&fund.fund_code)
    .execute(&mut *conn)
    .await?;
    apply(conn, position_id, account_id, fund, now).await?;
    claimed.insert(account_id);
    Ok(outcome)
}

/// The fund's figures onto the position (applySyncedPosition); true when the position could be
/// valued, which also sets the account's balance to the withdrawal value.
async fn apply(
    conn: &mut PgConnection,
    position_id: i64,
    account_id: i64,
    fund: &SyncedFund,
    now: DateTime<Utc>,
) -> AppResult<bool> {
    let account = accounts::find_by_id(&mut *conn, account_id)
        .await?
        .ok_or_else(accounts::account_not_found)?;
    if account.account_type != AccountType::Scpi {
        return Err(AppError::BadRequest(
            "Account is not a SCPI account".to_owned(),
        ));
    }
    let company = fund
        .management_company
        .as_deref()
        .map(str::trim)
        .filter(|c| !c.is_empty())
        .map(|c| truncate(c, MANAGEMENT_COMPANY_MAX));
    let distribution = fund
        .annual_distribution_per_share
        .zip(fund.distribution_frequency.clone());
    let value = withdrawal_value(Some(fund.quantity), fund.withdrawal_price);
    sqlx::query(
        "UPDATE scpi_position SET management_company = COALESCE($2, management_company), isin = COALESCE($3, isin), \
         annual_distribution_per_share_eur = CASE WHEN $4 THEN $5 ELSE annual_distribution_per_share_eur END, \
         distribution_frequency = CASE WHEN $4 THEN $6 ELSE distribution_frequency END, share_count = $7, \
         subscription_price_eur = COALESCE($8, subscription_price_eur), withdrawal_price_eur = $9, \
         valuation_status = $10, updated_at = $11 WHERE id = $1",
    )
    .bind(position_id)
    .bind(company)
    .bind(&fund.isin)
    .bind(distribution.is_some())
    .bind(distribution.as_ref().map(|d| d.0))
    .bind(distribution.as_ref().map(|d| d.1.clone()))
    .bind(fund.quantity)
    .bind(fund.subscription_price)
    .bind(fund.withdrawal_price)
    .bind(if value.is_some() { "OK" } else { "PRICE_INCOMPLETE" })
    .bind(now)
    .execute(&mut *conn)
    .await?;
    let mut account = account;
    account.currency = "EUR".to_owned();
    if let Some(value) = value {
        account.current_balance = value;
        account.cash_balance = Some(Decimal::ZERO);
    }
    accounts::update(&mut *conn, &account, now).await?;
    Ok(value.is_some())
}

/// The unlinked SCPI accounts named like the fund ("<provider> <fund>" too for a distributor).
async fn adoption_candidates(
    conn: &mut PgConnection,
    member_id: i64,
    provider: Provider,
    label: Option<&str>,
    claimed: &HashSet<i64>,
) -> AppResult<Vec<i64>> {
    let wanted = normalize(label);
    if wanted.is_empty() {
        return Ok(Vec::new());
    }
    let prefixed = if provider.distributor() {
        normalize(Some(&format!(
            "{} {}",
            provider.display_name(),
            label.unwrap_or("")
        )))
    } else {
        wanted.clone()
    };
    let mut matches = Vec::new();
    for account in accounts::find_all_by_member(&mut *conn, member_id).await? {
        if account.account_type != AccountType::Scpi || claimed.contains(&account.id) {
            continue;
        }
        let name = normalize(Some(&account.name));
        if name != wanted && name != prefixed {
            continue;
        }
        let linked: Option<bool> = sqlx::query_scalar(
            "SELECT corum_fund_code IS NOT NULL OR sofidy_fund_code IS NOT NULL OR louve_fund_code IS NOT NULL \
             FROM scpi_position WHERE account_id = $1 AND member_id = $2",
        )
        .bind(account.id)
        .bind(member_id)
        .fetch_optional(&mut *conn)
        .await?;
        if linked != Some(true) {
            matches.push(account.id);
        }
    }
    Ok(matches)
}

async fn report_missing(
    conn: &mut PgConnection,
    member_id: i64,
    provider: Provider,
    listed: &HashSet<String>,
    synced_at: DateTime<Utc>,
    outcomes: &mut Vec<(String, Outcome)>,
) -> AppResult<()> {
    let mut known: BTreeMap<String, Option<String>> = BTreeMap::new();
    let mut order: Vec<String> = Vec::new();
    let rows: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT fund_code, label FROM scpi_provider_fund WHERE member_id = $1 AND provider = $2 AND NOT ignored ORDER BY id",
    )
    .bind(member_id)
    .bind(provider.as_str())
    .fetch_all(&mut *conn)
    .await?;
    let column = provider.fund_code_column();
    let positions: Vec<(String, String)> = sqlx::query_as(&format!(
        "SELECT p.{column}, a.name FROM scpi_position p JOIN account a ON a.id = p.account_id AND a.deleted_at IS NULL \
         WHERE a.member_id = $1 AND p.{column} IS NOT NULL ORDER BY p.id"
    ))
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    for (code, label) in rows
        .into_iter()
        .chain(positions.into_iter().map(|(c, n)| (c, Some(n))))
    {
        if let std::collections::btree_map::Entry::Vacant(e) = known.entry(code) {
            order.push(e.key().clone());
            e.insert(label);
        }
    }
    for code in order {
        if listed.contains(&code) {
            continue;
        }
        let label = known.get(&code).cloned().flatten();
        let row = fund_row(
            conn,
            member_id,
            provider,
            &code,
            label.as_deref(),
            synced_at,
        )
        .await?;
        record(conn, row.id, Outcome::MissingAtProvider, None, synced_at).await?;
        outcomes.push((code, Outcome::MissingAtProvider));
    }
    Ok(())
}
