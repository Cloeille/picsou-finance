//! The SCPI side Louve writes to: ScpiFundReconciler.reconcile(member, LOUVE, ...) and the
//! ScpiPositionService.applySyncedPosition write path it goes through. Track T4 owns SCPI
//! (wealth); until it plugs its reconciler into Hooks.scpi, SqlScpiFunds is the Java behavior for
//! the Louve provider, on the same tables.

use std::collections::HashSet;
use std::sync::LazyLock;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;
use sqlx::PgConnection;
use unicode_normalization::UnicodeNormalization;

use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::AccountType;

/// ScpiProvider.LOUVE: a distributor, so the account it names is "Louve Invest <fund>".
pub const PROVIDER: &str = "LOUVE";
pub const DISPLAY_NAME: &str = "Louve Invest";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DistributionFrequency {
    Monthly,
    Quarterly,
}

impl DistributionFrequency {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Monthly => "MONTHLY",
            Self::Quarterly => "QUARTERLY",
        }
    }
}

/// One fund line of a snapshot, validated by the mapper. A None subscription_price leaves the
/// stored one alone; a None withdrawal_price keeps the previous balance. management_company,
/// isin and the distribution pair (written together or not at all) likewise.
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
    pub distribution_frequency: Option<DistributionFrequency>,
}

/// A line held in dismembered ownership: recorded UNSUPPORTED_OWNERSHIP, never linked, created or
/// valued, and not missing.
#[derive(Debug, Clone, PartialEq)]
pub struct UnsupportedFund {
    pub fund_code: String,
    pub label: Option<String>,
}

/// ScpiFundOutcome.
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

/// ScpiFundReconciler.reconcile for the LOUVE provider, inside the caller's transaction (the
/// member row is already locked). Returns the outcome per fund code, in snapshot order, then the
/// unsupported lines, then the missing funds.
#[async_trait]
pub trait ScpiFunds: Send + Sync {
    async fn reconcile(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        funds: &[SyncedFund],
        unsupported: &[UnsupportedFund],
        synced_at: DateTime<Utc>,
    ) -> AppResult<Vec<(String, Outcome)>>;
}

const ACCOUNT_NAME_MAX: usize = 100;
const LABEL_MAX: usize = 200;
const MANAGEMENT_COMPANY_MAX: usize = 100;
/// Same colour the manual SCPI form uses.
const SCPI_COLOR: &str = "#7c3aed";
const MONEY_SCALE: u32 = 8;

static MARKS: LazyLock<Regex> = LazyLock::new(|| Regex::new("\\p{M}+").expect("valid regex"));
static NON_ALNUM: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("[^a-z0-9]+").expect("valid regex"));

/// The Java ScpiFundReconciler on the scpi_position, scpi_provider_fund and account tables.
#[derive(Debug, Clone, Copy, Default)]
pub struct SqlScpiFunds;

#[derive(Debug, sqlx::FromRow)]
struct FundRow {
    id: Option<i64>,
    label: Option<String>,
    ignored: bool,
    last_outcome: Option<String>,
    last_seen_at: Option<DateTime<Utc>>,
    last_synced_at: Option<DateTime<Utc>>,
}

#[derive(Debug, sqlx::FromRow)]
struct PositionRow {
    id: Option<i64>,
    account_id: i64,
    isin: Option<String>,
    management_company: Option<String>,
    share_count: Decimal,
    subscription_price_eur: Option<Decimal>,
    withdrawal_price_eur: Option<Decimal>,
    annual_distribution_per_share_eur: Option<Decimal>,
    distribution_frequency: Option<String>,
    louve_fund_code: Option<String>,
    valuation_status: String,
}

const POSITION_COLUMNS: &str = "p.id, p.account_id, p.isin, p.management_company, p.share_count, \
    p.subscription_price_eur, p.withdrawal_price_eur, p.annual_distribution_per_share_eur, \
    p.distribution_frequency, p.louve_fund_code, p.valuation_status";

#[async_trait]
impl ScpiFunds for SqlScpiFunds {
    async fn reconcile(
        &self,
        conn: &mut PgConnection,
        member_id: i64,
        funds: &[SyncedFund],
        unsupported: &[UnsupportedFund],
        synced_at: DateTime<Utc>,
    ) -> AppResult<Vec<(String, Outcome)>> {
        let mut outcomes = Vec::new();
        let mut listed: HashSet<String> = HashSet::new();
        // Accounts adopted or created in this run must not be adopted again by a later fund.
        let mut claimed: HashSet<i64> = HashSet::new();
        for fund in funds {
            listed.insert(fund.fund_code.clone());
            let mut row = fund_row(conn, member_id, &fund.fund_code, fund.label.as_deref()).await?;
            row.last_seen_at = Some(synced_at);
            let outcome = apply_fund(conn, member_id, fund, &row, &mut claimed, synced_at).await?;
            row.last_outcome = Some(outcome.as_str().to_owned());
            row.last_synced_at = Some(synced_at);
            save_fund_row(conn, member_id, &fund.fund_code, &row, synced_at).await?;
            outcomes.push((fund.fund_code.clone(), outcome));
        }
        for fund in unsupported {
            if !listed.insert(fund.fund_code.clone()) {
                return Err(AppError::BadRequest(
                    "A fund is listed both as supported and unsupported".to_owned(),
                ));
            }
            // The linked account, if any, is left as it is: nothing here says what the
            // dismembered right is worth.
            let mut row = fund_row(conn, member_id, &fund.fund_code, fund.label.as_deref()).await?;
            row.last_seen_at = Some(synced_at);
            let outcome = if row.ignored {
                Outcome::Ignored
            } else {
                Outcome::UnsupportedOwnership
            };
            row.last_outcome = Some(outcome.as_str().to_owned());
            row.last_synced_at = Some(synced_at);
            save_fund_row(conn, member_id, &fund.fund_code, &row, synced_at).await?;
            outcomes.push((fund.fund_code.clone(), outcome));
        }
        report_missing(conn, member_id, &listed, synced_at, &mut outcomes).await?;
        Ok(outcomes)
    }
}

async fn apply_fund(
    conn: &mut PgConnection,
    member_id: i64,
    fund: &SyncedFund,
    row: &FundRow,
    claimed: &mut HashSet<i64>,
    now: DateTime<Utc>,
) -> AppResult<Outcome> {
    if row.ignored {
        return Ok(Outcome::Ignored);
    }
    let linked: Option<PositionRow> = sqlx::query_as(&format!(
        "SELECT {POSITION_COLUMNS} FROM scpi_position p WHERE p.member_id = $1 AND p.louve_fund_code = $2"
    ))
    .bind(member_id)
    .bind(&fund.fund_code)
    .fetch_optional(&mut *conn)
    .await?;
    if let Some(mut position) = linked {
        let status = apply(conn, &mut position, fund, member_id, now).await?;
        claimed.insert(position.account_id);
        return Ok(if status == "PRICE_INCOMPLETE" {
            Outcome::PriceIncomplete
        } else {
            Outcome::Updated
        });
    }

    let candidates = adoption_candidates(conn, member_id, fund.label.as_deref(), claimed).await?;
    if candidates.len() > 1 {
        tracing::info!(
            "Several SCPI accounts match this LOUVE fund, none adopted (member={member_id}; fund={}; matches={})",
            fund.fund_code,
            candidates.len()
        );
        return Ok(Outcome::Ambiguous);
    }
    if let Some(account_id) = candidates.first().copied() {
        let existing: Option<PositionRow> = sqlx::query_as(&format!(
            "SELECT {POSITION_COLUMNS} FROM scpi_position p WHERE p.account_id = $1 AND p.member_id = $2"
        ))
        .bind(account_id)
        .bind(member_id)
        .fetch_optional(&mut *conn)
        .await?;
        let mut position = existing.unwrap_or_else(|| new_position(account_id, fund));
        position.louve_fund_code = Some(fund.fund_code.clone());
        apply(conn, &mut position, fund, member_id, now).await?;
        claimed.insert(account_id);
        return Ok(Outcome::Linked);
    }

    let name = match fund.label.as_deref().map(str::trim) {
        Some(label) if !label.is_empty() => label.to_owned(),
        _ => format!("{DISPLAY_NAME} {}", fund.fund_code),
    };
    let mut account = NewAccount::new(
        member_id,
        truncate(&name, ACCOUNT_NAME_MAX),
        AccountType::Scpi,
    );
    account.provider = Some(DISPLAY_NAME.to_owned());
    // A SCPI is always manual: its balance is written from the share, never a bank feed.
    account.is_manual = true;
    account.color = SCPI_COLOR.to_owned();
    let account = accounts::insert(&mut *conn, &account, now).await?;
    let mut position = new_position(account.id, fund);
    position.louve_fund_code = Some(fund.fund_code.clone());
    apply(conn, &mut position, fund, member_id, now).await?;
    claimed.insert(account.id);
    Ok(Outcome::Created)
}

/// A distributor is not the management company; the fund's own one is set by apply.
fn new_position(account_id: i64, fund: &SyncedFund) -> PositionRow {
    PositionRow {
        id: None,
        account_id,
        isin: None,
        management_company: None,
        share_count: fund.quantity,
        subscription_price_eur: None,
        withdrawal_price_eur: None,
        annual_distribution_per_share_eur: None,
        distribution_frequency: None,
        louve_fund_code: None,
        valuation_status: "PRICE_INCOMPLETE".to_owned(),
    }
}

/// The fund's published figures, then ScpiPositionService.applySyncedPosition. Returns the
/// valuation status written.
async fn apply(
    conn: &mut PgConnection,
    position: &mut PositionRow,
    fund: &SyncedFund,
    member_id: i64,
    now: DateTime<Utc>,
) -> AppResult<&'static str> {
    if let Some(company) = fund.management_company.as_deref().map(str::trim)
        && !company.is_empty()
    {
        position.management_company = Some(truncate(company, MANAGEMENT_COMPANY_MAX));
    }
    if let Some(isin) = &fund.isin {
        position.isin = Some(isin.clone());
    }
    if let (Some(annual), Some(frequency)) = (
        fund.annual_distribution_per_share,
        fund.distribution_frequency,
    ) {
        position.annual_distribution_per_share_eur = Some(annual);
        position.distribution_frequency = Some(frequency.as_str().to_owned());
    }
    let account = accounts::find_by_id(&mut *conn, position.account_id)
        .await?
        .ok_or_else(|| AppError::internal("SCPI position without its account"))?;
    if account.account_type != AccountType::Scpi {
        // The fund link is data, and data can be wrong: never write a share balance onto
        // another asset type.
        return Err(AppError::internal("Account is not a SCPI account"));
    }
    position.share_count = fund.quantity;
    if fund.subscription_price.is_some() {
        position.subscription_price_eur = fund.subscription_price;
    }
    position.withdrawal_price_eur = fund.withdrawal_price;
    let value = withdrawal_value(fund.quantity, fund.withdrawal_price);
    let status = if value.is_some() {
        "OK"
    } else {
        "PRICE_INCOMPLETE"
    };
    position.valuation_status = status.to_owned();
    save_position(conn, position, member_id, now).await?;
    sqlx::query(
        "UPDATE account SET currency = 'EUR', current_balance = COALESCE($2, current_balance), \
         cash_balance = CASE WHEN $2 IS NULL THEN cash_balance ELSE 0 END, updated_at = $3 WHERE id = $1",
    )
    .bind(account.id)
    .bind(value)
    .bind(now)
    .execute(&mut *conn)
    .await?;
    Ok(status)
}

/// None when the price is absent. Zero shares is zero whatever the price: a sold position has
/// no value, and reading it as unknown would leave the sold balance in the net worth.
fn withdrawal_value(shares: Decimal, price: Option<Decimal>) -> Option<Decimal> {
    if shares.is_zero() {
        return Some(set_scale(Decimal::ZERO, MONEY_SCALE, HALF_UP));
    }
    price.map(|p| set_scale(shares * p, MONEY_SCALE, HALF_UP))
}

async fn save_position(
    conn: &mut PgConnection,
    p: &PositionRow,
    member_id: i64,
    now: DateTime<Utc>,
) -> AppResult<()> {
    let query = match p.id {
        Some(id) => sqlx::query(
            "UPDATE scpi_position SET isin = $2, management_company = $3, share_count = $4, \
             subscription_price_eur = $5, withdrawal_price_eur = $6, annual_distribution_per_share_eur = $7, \
             distribution_frequency = $8, louve_fund_code = $9, valuation_status = $10, updated_at = $11 \
             WHERE id = $1",
        )
        .bind(id),
        None => sqlx::query(
            "INSERT INTO scpi_position (account_id, isin, management_company, share_count, subscription_price_eur, \
             withdrawal_price_eur, annual_distribution_per_share_eur, distribution_frequency, louve_fund_code, \
             valuation_status, created_at, updated_at, member_id) \
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11, $12)",
        )
        .bind(p.account_id),
    };
    let query = query
        .bind(&p.isin)
        .bind(&p.management_company)
        .bind(p.share_count)
        .bind(p.subscription_price_eur)
        .bind(p.withdrawal_price_eur)
        .bind(p.annual_distribution_per_share_eur)
        .bind(&p.distribution_frequency)
        .bind(&p.louve_fund_code)
        .bind(&p.valuation_status)
        .bind(now);
    let query = if p.id.is_none() {
        query.bind(member_id)
    } else {
        query
    };
    query.execute(&mut *conn).await?;
    Ok(())
}

/// Manual SCPI accounts of the member, linked to no provider, whose normalized name equals the
/// normalized label, or "Louve Invest <label>" (a distributor). Exact after normalization.
async fn adoption_candidates(
    conn: &mut PgConnection,
    member_id: i64,
    label: Option<&str>,
    claimed: &HashSet<i64>,
) -> AppResult<Vec<i64>> {
    let wanted = normalize(label);
    if wanted.is_empty() {
        return Ok(Vec::new());
    }
    let prefixed = normalize(Some(&format!(
        "{DISPLAY_NAME} {}",
        label.unwrap_or_default()
    )));
    let mut matches = Vec::new();
    for account in accounts::find_all_by_member(&mut *conn, member_id).await? {
        if account.account_type != AccountType::Scpi || claimed.contains(&account.id) {
            continue;
        }
        let name = normalize(Some(&account.name));
        if wanted != name && prefixed != name {
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

/// Reports, without touching any balance, every fund of this member the snapshot no longer
/// lists, linked positions included even when they predate scpi_provider_fund.
async fn report_missing(
    conn: &mut PgConnection,
    member_id: i64,
    listed: &HashSet<String>,
    synced_at: DateTime<Utc>,
    outcomes: &mut Vec<(String, Outcome)>,
) -> AppResult<()> {
    let mut known: Vec<(String, Option<String>)> = sqlx::query_as(
        "SELECT fund_code, label FROM scpi_provider_fund WHERE member_id = $1 AND provider = $2 \
         AND NOT ignored ORDER BY id",
    )
    .bind(member_id)
    .bind(PROVIDER)
    .fetch_all(&mut *conn)
    .await?;
    let linked: Vec<(String, String)> = sqlx::query_as(
        "SELECT p.louve_fund_code, a.name FROM scpi_position p JOIN account a ON a.id = p.account_id \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL AND p.louve_fund_code IS NOT NULL ORDER BY p.id",
    )
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    for (code, name) in linked {
        if !known.iter().any(|(c, _)| *c == code) {
            known.push((code, Some(name)));
        }
    }
    for (code, label) in known {
        if listed.contains(&code) {
            continue;
        }
        let mut row = fund_row(conn, member_id, &code, label.as_deref()).await?;
        row.last_outcome = Some(Outcome::MissingAtProvider.as_str().to_owned());
        row.last_synced_at = Some(synced_at);
        save_fund_row(conn, member_id, &code, &row, synced_at).await?;
        outcomes.push((code, Outcome::MissingAtProvider));
    }
    Ok(())
}

async fn fund_row(
    conn: &mut PgConnection,
    member_id: i64,
    fund_code: &str,
    label: Option<&str>,
) -> AppResult<FundRow> {
    let found: Option<FundRow> = sqlx::query_as(
        "SELECT id, label, ignored, last_outcome, last_seen_at, last_synced_at FROM scpi_provider_fund \
         WHERE member_id = $1 AND provider = $2 AND fund_code = $3",
    )
    .bind(member_id)
    .bind(PROVIDER)
    .bind(fund_code)
    .fetch_optional(&mut *conn)
    .await?;
    let mut row = found.unwrap_or(FundRow {
        id: None,
        label: None,
        ignored: false,
        last_outcome: None,
        last_seen_at: None,
        last_synced_at: None,
    });
    if let Some(label) = label.map(str::trim).filter(|l| !l.is_empty()) {
        row.label = Some(truncate(label, LABEL_MAX));
    }
    Ok(row)
}

async fn save_fund_row(
    conn: &mut PgConnection,
    member_id: i64,
    fund_code: &str,
    row: &FundRow,
    now: DateTime<Utc>,
) -> AppResult<()> {
    let query = match row.id {
        Some(id) => sqlx::query(
            "UPDATE scpi_provider_fund SET label = $2, ignored = $3, last_outcome = $4, last_seen_at = $5, \
             last_synced_at = $6, updated_at = $7 WHERE id = $1",
        )
        .bind(id),
        None => sqlx::query(
            "INSERT INTO scpi_provider_fund (member_id, fund_code, label, ignored, last_outcome, last_seen_at, \
             last_synced_at, created_at, updated_at, provider) \
             VALUES ($8, $1, $2, $3, $4, $5, $6, $7, $7, 'LOUVE')",
        )
        .bind(fund_code),
    };
    let query = query
        .bind(&row.label)
        .bind(row.ignored)
        .bind(&row.last_outcome)
        .bind(row.last_seen_at)
        .bind(row.last_synced_at)
        .bind(now);
    let query = if row.id.is_none() {
        query.bind(member_id)
    } else {
        query
    };
    query.execute(&mut *conn).await?;
    Ok(())
}

/// Case, accents and punctuation do not distinguish two fund names; nothing looser than that.
fn normalize(value: Option<&str>) -> String {
    let Some(value) = value else {
        return String::new();
    };
    let decomposed: String = value.nfd().collect();
    let stripped = MARKS.replace_all(&decomposed, "").to_lowercase();
    NON_ALNUM.replace_all(&stripped, " ").trim().to_owned()
}

/// String.substring(0, max) on UTF-16 units, never splitting a character.
fn truncate(value: &str, max: usize) -> String {
    let mut units = 0;
    value
        .chars()
        .take_while(|c| {
            units += c.len_utf16();
            units <= max
        })
        .collect()
}
