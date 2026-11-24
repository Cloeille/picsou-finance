//! The default of the \`scpi_funds\` seam: Java's ScpiFundReconciler and
//! ScpiPositionService.applySyncedPosition (track T4), on scpi_position and scpi_provider_fund.
//! Every write runs in the caller's sync transaction, after its member row lock, so two syncs of
//! one member cannot both create one account; the per-member unique fund-code indexes back that.

use std::collections::HashSet;
use std::sync::LazyLock;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use regex::Regex;
use rust_decimal::Decimal;

use crate::connectors::seams::{ScpiFundOutcome, ScpiFunds, ScpiProvider, SyncedFund};
use crate::db::{self, AsDbHandle, DbExec, DbFromRow, DbHandle};
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::merchants::match_key;
use crate::ledger::model::{ACCOUNT_COLUMNS, Account, AccountType};

const ACCOUNT_NAME_MAX: usize = 100;
const LABEL_MAX: usize = 200;
/// The colour the manual SCPI form uses.
const SCPI_COLOR: &str = "#7c3aed";
const MONEY_SCALE: u32 = 8;
const PRICE_INCOMPLETE: &str = "PRICE_INCOMPLETE";

static NON_ALNUM: LazyLock<Regex> = LazyLock::new(|| Regex::new("[^a-z0-9]+").expect("regex"));

#[derive(Debug, Default, Clone, Copy)]
pub struct RecordedScpiFunds;

/// Case, accents and punctuation do not tell two fund names apart ("Corum XL", "CORUM-XL");
/// nothing looser does, a fuzzy match being how a phantom link happens.
pub fn normalize(value: &str) -> String {
    let folded: String = value
        .chars()
        .map(|c| {
            let key = match_key(&c.to_string());
            if key.is_empty() { c.to_string() } else { key }
        })
        .collect();
    NON_ALNUM
        .replace_all(&folded.to_lowercase(), " ")
        .trim()
        .to_owned()
}

fn truncate(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

fn code_column(provider: ScpiProvider) -> &'static str {
    match provider {
        ScpiProvider::Corum => "corum_fund_code",
        ScpiProvider::Sofidy => "sofidy_fund_code",
    }
}

#[derive(Debug, Clone, DbFromRow)]
struct Position {
    id: Option<i64>,
    account_id: i64,
    member_id: i64,
    management_company: Option<String>,
    share_count: Decimal,
    subscription_price_eur: Option<Decimal>,
    withdrawal_price_eur: Option<Decimal>,
    corum_fund_code: Option<String>,
    sofidy_fund_code: Option<String>,
    louve_fund_code: Option<String>,
    valuation_status: String,
}

const POSITION_COLUMNS: &str = "p.id, p.account_id, p.member_id, p.management_company, p.share_count, \
    p.subscription_price_eur, p.withdrawal_price_eur, p.corum_fund_code, p.sofidy_fund_code, p.louve_fund_code, \
    p.valuation_status";

impl Position {
    fn new(account: &Account, provider: ScpiProvider, quantity: Decimal) -> Self {
        Self {
            id: None,
            account_id: account.id,
            member_id: account.member_id,
            management_company: Some(provider.display_name().to_owned()),
            share_count: quantity,
            subscription_price_eur: None,
            withdrawal_price_eur: None,
            corum_fund_code: None,
            sofidy_fund_code: None,
            louve_fund_code: None,
            valuation_status: PRICE_INCOMPLETE.to_owned(),
        }
    }

    fn fund_code(&self, provider: ScpiProvider) -> Option<&str> {
        match provider {
            ScpiProvider::Corum => self.corum_fund_code.as_deref(),
            ScpiProvider::Sofidy => self.sofidy_fund_code.as_deref(),
        }
    }

    fn set_fund_code(&mut self, provider: ScpiProvider, code: &str) {
        let code = Some(code.to_owned());
        match provider {
            ScpiProvider::Corum => self.corum_fund_code = code,
            ScpiProvider::Sofidy => self.sofidy_fund_code = code,
        }
    }

    fn links_a_provider(&self) -> bool {
        self.corum_fund_code.is_some()
            || self.sofidy_fund_code.is_some()
            || self.louve_fund_code.is_some()
    }
}

/// One scpi_provider_fund row, loaded or about to be inserted.
#[derive(Debug, Clone, DbFromRow)]
struct FundRow {
    id: Option<i64>,
    fund_code: String,
    label: Option<String>,
    ignored: bool,
    last_seen_at: Option<DateTime<Utc>>,
}

async fn position_by_code<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    provider: ScpiProvider,
    code: &str,
) -> sqlx::Result<Option<Position>> {
    let column = code_column(provider);
    db::query_as(&format!(
        "SELECT {POSITION_COLUMNS} FROM scpi_position p WHERE p.member_id = $1 AND p.{column} = $2"
    ))
    .bind(member_id)
    .bind(code)
    .fetch_optional(db)
    .await
}

async fn position_by_account<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    member_id: i64,
) -> sqlx::Result<Option<Position>> {
    db::query_as(&format!(
        "SELECT {POSITION_COLUMNS} FROM scpi_position p WHERE p.account_id = $1 AND p.member_id = $2"
    ))
    .bind(account_id)
    .bind(member_id)
    .fetch_optional(db)
    .await
}

/// findByAccountMemberIdAnd<Provider>FundCodeIsNotNull: the account owner's live accounts.
async fn linked_positions(
    conn: &mut DbHandle<'_>,
    member_id: i64,
    provider: ScpiProvider,
) -> sqlx::Result<Vec<(Position, String)>> {
    let column = code_column(provider);
    let positions: Vec<Position> = db::query_as(&format!(
        "SELECT {POSITION_COLUMNS} FROM scpi_position p JOIN account a ON a.id = p.account_id \
         WHERE a.member_id = $1 AND a.deleted_at IS NULL AND p.{column} IS NOT NULL ORDER BY p.id"
    ))
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    let mut linked = Vec::with_capacity(positions.len());
    for position in positions {
        let name: String = db::query_scalar("SELECT name FROM account WHERE id = $1")
            .bind(position.account_id)
            .fetch_one(&mut *conn)
            .await?;
        linked.push((position, name));
    }
    Ok(linked)
}

/// The position's account as its lazy association loads it: by id alone.
async fn account_of<'e>(db: impl DbExec<'e>, position: &Position) -> AppResult<Account> {
    db::query_as::<Account>(&format!(
        "SELECT {ACCOUNT_COLUMNS} FROM account a WHERE a.id = $1"
    ))
    .bind(position.account_id)
    .fetch_optional(db)
    .await?
    .ok_or_else(|| AppError::internal("SCPI position without its account"))
}

async fn save_position<'e>(
    db: impl DbExec<'e>,
    position: &mut Position,
    now: DateTime<Utc>,
) -> sqlx::Result<()> {
    match position.id {
        Some(id) => {
            db::query(
                "UPDATE scpi_position SET management_company = $2, share_count = $3, subscription_price_eur = $4, \
                 withdrawal_price_eur = $5, corum_fund_code = $6, sofidy_fund_code = $7, valuation_status = $8, \
                 updated_at = $9 WHERE id = $1",
            )
            .bind(id)
            .bind(&position.management_company)
            .bind(position.share_count)
            .bind(position.subscription_price_eur)
            .bind(position.withdrawal_price_eur)
            .bind(&position.corum_fund_code)
            .bind(&position.sofidy_fund_code)
            .bind(&position.valuation_status)
            .bind(now)
            .execute(db)
            .await?;
        }
        None => {
            let id: i64 = db::query_scalar(
                "INSERT INTO scpi_position (account_id, member_id, management_company, share_count, \
                 subscription_price_eur, withdrawal_price_eur, dividend_policy, corum_fund_code, sofidy_fund_code, \
                 valuation_status, created_at, updated_at) \
                 VALUES ($1, $2, $3, $4, $5, $6, 'CASH', $7, $8, $9, $10, $10) RETURNING id",
            )
            .bind(position.account_id)
            .bind(position.member_id)
            .bind(&position.management_company)
            .bind(position.share_count)
            .bind(position.subscription_price_eur)
            .bind(position.withdrawal_price_eur)
            .bind(&position.corum_fund_code)
            .bind(&position.sofidy_fund_code)
            .bind(&position.valuation_status)
            .bind(now)
            .fetch_one(db)
            .await?;
            position.id = Some(id);
        }
    }
    Ok(())
}

/// ScpiPositionService.withdrawalValue: None when the price is absent; zero shares is worth zero
/// whatever is quoted, so a sold position never keeps its last balance.
fn withdrawal_value(share_count: Decimal, withdrawal_price: Option<Decimal>) -> Option<Decimal> {
    if share_count.is_zero() {
        return Some(set_scale(Decimal::ZERO, MONEY_SCALE, HALF_UP));
    }
    withdrawal_price.map(|price| set_scale(share_count * price, MONEY_SCALE, HALF_UP))
}

/// ScpiPositionService.applySyncedPosition: the balance is the withdrawal value and nothing else,
/// since entry fees sit between it and the subscription price.
async fn apply_synced_position(
    conn: &mut DbHandle<'_>,
    position: &mut Position,
    share_count: Decimal,
    subscription_price: Option<Decimal>,
    withdrawal_price: Option<Decimal>,
    now: DateTime<Utc>,
) -> AppResult<()> {
    let mut account = account_of(&mut *conn, position).await?;
    if account.account_type != AccountType::Scpi {
        // The fund link is data, and data can be wrong: no share balance on another asset type.
        return Err(AppError::internal("Account is not a SCPI account"));
    }
    account.currency = "EUR".to_owned();
    position.share_count = share_count;
    if subscription_price.is_some() {
        position.subscription_price_eur = subscription_price;
    }
    position.withdrawal_price_eur = withdrawal_price;
    match withdrawal_value(share_count, withdrawal_price) {
        None => position.valuation_status = PRICE_INCOMPLETE.to_owned(),
        Some(value) => {
            position.valuation_status = "OK".to_owned();
            account.current_balance = value;
            account.cash_balance = Some(Decimal::ZERO);
        }
    }
    save_position(&mut *conn, position, now).await?;
    accounts::update(&mut *conn, &account, now).await?;
    Ok(())
}

async fn fund_row<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    provider: ScpiProvider,
    code: &str,
    label: Option<&str>,
) -> sqlx::Result<FundRow> {
    let found: Option<FundRow> = db::query_as(
        "SELECT id, fund_code, label, ignored, last_seen_at FROM scpi_provider_fund \
         WHERE member_id = $1 AND provider = $2 AND fund_code = $3",
    )
    .bind(member_id)
    .bind(provider.as_str())
    .bind(code)
    .fetch_optional(db)
    .await?;
    let mut row = found.unwrap_or(FundRow {
        id: None,
        fund_code: code.to_owned(),
        label: None,
        ignored: false,
        last_seen_at: None,
    });
    if let Some(label) = label.map(str::trim).filter(|l| !l.is_empty()) {
        row.label = Some(truncate(label, LABEL_MAX));
    }
    Ok(row)
}

async fn save_fund_row<'e>(
    db: impl DbExec<'e>,
    member_id: i64,
    provider: ScpiProvider,
    row: &FundRow,
    outcome: ScpiFundOutcome,
    synced_at: DateTime<Utc>,
) -> sqlx::Result<()> {
    match row.id {
        Some(id) => {
            db::query(
                "UPDATE scpi_provider_fund SET label = $2, last_outcome = $3, last_seen_at = $4, \
                 last_synced_at = $5, updated_at = $5 WHERE id = $1",
            )
            .bind(id)
            .bind(&row.label)
            .bind(outcome.as_str())
            .bind(row.last_seen_at)
            .bind(synced_at)
            .execute(db)
            .await?;
        }
        None => {
            db::query(
                "INSERT INTO scpi_provider_fund (member_id, provider, fund_code, label, ignored, last_outcome, \
                 last_seen_at, last_synced_at, created_at, updated_at) VALUES ($1, $2, $3, $4, false, $5, $6, $7, $7, $7)",
            )
            .bind(member_id)
            .bind(provider.as_str())
            .bind(&row.fund_code)
            .bind(&row.label)
            .bind(outcome.as_str())
            .bind(row.last_seen_at)
            .bind(synced_at)
            .execute(db)
            .await?;
        }
    }
    Ok(())
}

/// Manual SCPI accounts linked to no provider, named like the fund once normalized.
async fn adoption_candidates(
    conn: &mut DbHandle<'_>,
    member_id: i64,
    label: Option<&str>,
    claimed: &HashSet<i64>,
) -> AppResult<Vec<Account>> {
    let wanted = normalize(label.unwrap_or(""));
    if wanted.is_empty() {
        return Ok(Vec::new());
    }
    let mut matches = Vec::new();
    for account in accounts::find_all_by_member(&mut *conn, member_id).await? {
        if account.account_type != AccountType::Scpi
            || claimed.contains(&account.id)
            || normalize(&account.name) != wanted
        {
            continue;
        }
        let position = position_by_account(&mut *conn, account.id, member_id).await?;
        if position.is_some_and(|p| p.links_a_provider()) {
            continue;
        }
        matches.push(account);
    }
    Ok(matches)
}

async fn apply_fund(
    conn: &mut DbHandle<'_>,
    member_id: i64,
    provider: ScpiProvider,
    fund: &SyncedFund,
    row: &FundRow,
    claimed: &mut HashSet<i64>,
    now: DateTime<Utc>,
) -> AppResult<ScpiFundOutcome> {
    if row.ignored {
        return Ok(ScpiFundOutcome::Ignored);
    }
    if let Some(mut linked) =
        position_by_code(&mut *conn, member_id, provider, &fund.fund_code).await?
    {
        apply(conn, &mut linked, fund, now).await?;
        claimed.insert(linked.account_id);
        return Ok(if linked.valuation_status == PRICE_INCOMPLETE {
            ScpiFundOutcome::PriceIncomplete
        } else {
            ScpiFundOutcome::Updated
        });
    }
    let candidates = adoption_candidates(conn, member_id, fund.label.as_deref(), claimed).await?;
    if candidates.len() > 1 {
        tracing::info!(
            "Several SCPI accounts match this {} fund, none adopted (member={member_id}; fund={}; matches={})",
            provider.as_str(),
            fund.fund_code,
            candidates.len()
        );
        return Ok(ScpiFundOutcome::Ambiguous);
    }
    if let Some(account) = candidates.into_iter().next() {
        let mut position = match position_by_account(&mut *conn, account.id, member_id).await? {
            Some(position) => position,
            None => Position::new(&account, provider, fund.quantity),
        };
        position.set_fund_code(provider, &fund.fund_code);
        apply(conn, &mut position, fund, now).await?;
        claimed.insert(account.id);
        return Ok(ScpiFundOutcome::Linked);
    }
    let name = match fund
        .label
        .as_deref()
        .map(str::trim)
        .filter(|l| !l.is_empty())
    {
        Some(label) => truncate(label, ACCOUNT_NAME_MAX),
        None => truncate(
            &format!("{} {}", provider.display_name(), fund.fund_code),
            ACCOUNT_NAME_MAX,
        ),
    };
    let mut draft = NewAccount::new(member_id, name, AccountType::Scpi);
    draft.provider = Some(provider.display_name().to_owned());
    draft.currency = "EUR".to_owned();
    draft.current_balance = Decimal::ZERO;
    // A SCPI is always manual: its balance is written from the share, never by a bank feed.
    draft.is_manual = true;
    draft.color = SCPI_COLOR.to_owned();
    let account = accounts::insert(&mut *conn, &draft, now).await?;
    let mut position = Position::new(&account, provider, fund.quantity);
    position.set_fund_code(provider, &fund.fund_code);
    apply(conn, &mut position, fund, now).await?;
    claimed.insert(account.id);
    Ok(ScpiFundOutcome::Created)
}

async fn apply(
    conn: &mut DbHandle<'_>,
    position: &mut Position,
    fund: &SyncedFund,
    now: DateTime<Utc>,
) -> AppResult<()> {
    apply_synced_position(
        conn,
        position,
        fund.quantity,
        fund.subscription_price,
        fund.withdrawal_price,
        now,
    )
    .await
}

/// Reports, without touching a balance, every fund of the provider the snapshot no longer
/// lists; a link made by hand counts even without a fund row.
async fn report_missing(
    conn: &mut DbHandle<'_>,
    member_id: i64,
    provider: ScpiProvider,
    listed: &HashSet<String>,
    synced_at: DateTime<Utc>,
    outcomes: &mut Vec<(String, ScpiFundOutcome)>,
) -> AppResult<()> {
    let rows: Vec<(String, Option<String>, bool)> = db::query_as(
        "SELECT fund_code, label, ignored FROM scpi_provider_fund WHERE member_id = $1 AND provider = $2 \
         ORDER BY id",
    )
    .bind(member_id)
    .bind(provider.as_str())
    .fetch_all(&mut *conn)
    .await?;
    let mut known: Vec<(String, Option<String>)> = rows
        .into_iter()
        .filter(|(_, _, ignored)| !ignored)
        .map(|(code, label, _)| (code, label))
        .collect();
    for (position, name) in linked_positions(&mut *conn, member_id, provider).await? {
        let Some(code) = position.fund_code(provider) else {
            continue;
        };
        if !known.iter().any(|(k, _)| k == code) {
            known.push((code.to_owned(), Some(name)));
        }
    }
    for (code, label) in known {
        if listed.contains(&code) {
            continue;
        }
        let row = fund_row(&mut *conn, member_id, provider, &code, label.as_deref()).await?;
        save_fund_row(
            &mut *conn,
            member_id,
            provider,
            &row,
            ScpiFundOutcome::MissingAtProvider,
            synced_at,
        )
        .await?;
        outcomes.push((code, ScpiFundOutcome::MissingAtProvider));
    }
    Ok(())
}

#[async_trait]
impl ScpiFunds for RecordedScpiFunds {
    async fn reconcile(
        &self,
        conn: &mut DbHandle<'_>,
        member_id: i64,
        provider: ScpiProvider,
        funds: &[SyncedFund],
        synced_at: DateTime<Utc>,
    ) -> AppResult<Vec<(String, ScpiFundOutcome)>> {
        let mut outcomes = Vec::with_capacity(funds.len());
        let mut listed = HashSet::new();
        // An account adopted or created in this run is not adopted again by a later fund.
        let mut claimed = HashSet::new();
        for fund in funds {
            listed.insert(fund.fund_code.clone());
            let mut row = fund_row(
                &mut *conn,
                member_id,
                provider,
                &fund.fund_code,
                fund.label.as_deref(),
            )
            .await?;
            row.last_seen_at = Some(synced_at);
            let outcome = apply_fund(
                conn,
                member_id,
                provider,
                fund,
                &row,
                &mut claimed,
                synced_at,
            )
            .await?;
            save_fund_row(&mut *conn, member_id, provider, &row, outcome, synced_at).await?;
            outcomes.push((fund.fund_code.clone(), outcome));
        }
        report_missing(
            &mut conn.as_handle(),
            member_id,
            provider,
            &listed,
            synced_at,
            &mut outcomes,
        )
        .await?;
        Ok(outcomes)
    }

    async fn close_unlisted(
        &self,
        conn: &mut DbHandle<'_>,
        member_id: i64,
        provider: ScpiProvider,
        listed: &HashSet<String>,
        now: DateTime<Utc>,
    ) -> AppResult<usize> {
        let mut closed = 0;
        for (mut position, _) in
            linked_positions(&mut conn.as_handle(), member_id, provider).await?
        {
            let Some(code) = position.fund_code(provider).map(str::to_owned) else {
                continue;
            };
            if listed.contains(&code) {
                continue;
            }
            tracing::info!(
                "{} no longer lists a linked fund, closing the position (member={member_id}; fund={code})",
                provider.display_name()
            );
            let price = position.withdrawal_price_eur;
            apply_synced_position(conn, &mut position, Decimal::ZERO, None, price, now).await?;
            closed += 1;
        }
        Ok(closed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_ignores_case_accents_and_punctuation_only() {
        assert_eq!(normalize("CORUM-XL"), "corum xl");
        assert_eq!(normalize("  Corum   Xl "), "corum xl");
        assert_eq!(normalize("Épargne Pierre"), "epargne pierre");
        assert_eq!(normalize("Corum Origin"), "corum origin");
        assert_ne!(normalize("Corum XL 2"), normalize("Corum XL"));
        assert_eq!(normalize("--"), "");
    }

    #[test]
    fn zero_shares_are_worth_zero_whatever_the_price() {
        let zero = withdrawal_value(Decimal::ZERO, None).unwrap();
        assert!(zero.is_zero());
        assert_eq!(zero.scale(), 8);
        assert_eq!(withdrawal_value(Decimal::ONE, None), None);
        let value = withdrawal_value("1.5".parse().unwrap(), Some("3.333333333".parse().unwrap()));
        assert_eq!(value.unwrap().to_string(), "5.00000000");
    }
}
