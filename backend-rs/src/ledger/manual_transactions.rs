//! ManualTransactionService: hand-entered transactions on a member's account, and the derived
//! state they drive (holdings of an investment account, balance and history of a ledger one).

use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::accounts;
use super::categorize::{self, CategorizationContext};
use super::holding_compute;
use super::instrument::{self, TickerResult};
use super::manual_ledger;
use super::model::{Account, AccountType, Category, NewTransaction, Transaction, TransactionType};
use super::prices::PriceRead;
use super::sync_values::{java_is_blank, java_trim};
use super::transactions;
use crate::db::DbExec;
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Lang, Validate};

/// The TransactionRequest body of the manual-entry endpoints.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransactionRequest {
    pub date: Option<NaiveDate>,
    pub description: Option<String>,
    pub amount: Option<Decimal>,
    pub tx_type: Option<TransactionType>,
    pub ticker: Option<String>,
    pub name: Option<String>,
    pub quantity: Option<Decimal>,
    pub price_per_unit: Option<Decimal>,
    pub currency: Option<String>,
    pub category_id: Option<i64>,
    pub fees: Option<Decimal>,
}

/// The @NotNull and @NotBlank fields once validated.
struct Required {
    date: NaiveDate,
    description: String,
    amount: Decimal,
}

impl TransactionRequest {
    fn required(&self, errors: &mut Errors) -> Option<Required> {
        let description = self.description.as_deref();
        errors
            .not_null("date", self.date.as_ref())
            .not_blank("description", description)
            .not_null("amount", self.amount.as_ref());
        Some(Required {
            date: self.date?,
            description: description
                .filter(|d| !crate::ledger::sync_values::java_trim(d).is_empty())?
                .to_owned(),
            amount: self.amount?,
        })
    }
}

impl Validate for TransactionRequest {
    fn validate(&self, errors: &mut Errors) {
        self.required(errors);
    }
}

/// The bean validation the controller runs, for callers that skipped the Json extractor.
fn validated(req: &TransactionRequest) -> AppResult<Required> {
    let mut errors = Errors::new(Lang::English);
    let required = req.required(&mut errors);
    errors.into_result()?;
    required.ok_or_else(|| AppError::BadRequest("Failed to read request".to_owned()))
}

/// A transaction's role in a peer loan, serialized as its name.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PeerLoanLinkKind {
    Lending,
    Repayment,
}

/// The peer loan a transaction belongs to, if any.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanLink {
    pub kind: PeerLoanLinkKind,
    pub account_id: Option<i64>,
    pub account_name: Option<String>,
}

/// TransactionResponse: a transaction as the API returns it (nulls omitted, as Jackson's
/// non_null inclusion does).
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TransactionResponse {
    pub id: i64,
    pub date: NaiveDate,
    pub description: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount: Decimal,
    #[serde(rename = "type")]
    pub kind: Option<String>,
    pub category: Option<String>,
    pub native_currency: String,
    pub created_at: DateTime<Utc>,
    pub is_manual: bool,
    pub tx_type: Option<TransactionType>,
    pub ticker: Option<String>,
    pub name: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub quantity: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub price_per_unit: Option<Decimal>,
    pub category_id: Option<i64>,
    pub category_name: Option<String>,
    pub counterparty: Option<String>,
    pub merchant_label: Option<String>,
    pub merchant_brand_id: Option<i64>,
    pub ai_suggested_category_id: Option<i64>,
    pub ai_confidence: Option<i32>,
    pub account_id: i64,
    pub account_name: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub fees: Option<Decimal>,
    pub budget_neutral: bool,
    pub peer_loan_link: Option<PeerLoanLink>,
}

impl TransactionResponse {
    /// TransactionResponse.from: the row with its account's name, its category's name and the
    /// peer-loan link the caller resolved.
    pub fn from_row(
        tx: &Transaction,
        account_name: &str,
        category_name: Option<String>,
        peer_loan_link: Option<PeerLoanLink>,
    ) -> Self {
        Self {
            id: tx.id,
            date: tx.date,
            description: tx.description.clone(),
            amount: tx.amount,
            kind: tx.kind.clone(),
            category: tx.category.clone(),
            native_currency: tx.native_currency.clone(),
            created_at: tx.created_at,
            is_manual: tx.is_manual,
            tx_type: tx.tx_type,
            ticker: tx.ticker.clone(),
            name: tx.name.clone(),
            quantity: tx.quantity,
            price_per_unit: tx.price_per_unit,
            category_id: tx.category_id,
            category_name,
            counterparty: tx.counterparty.clone(),
            merchant_label: tx.merchant_label.clone(),
            merchant_brand_id: tx.merchant_brand_id,
            ai_suggested_category_id: tx.ai_suggested_category_id,
            ai_confidence: tx.ai_confidence,
            account_id: tx.account_id,
            account_name: account_name.to_owned(),
            fees: tx.fees,
            budget_neutral: tx.budget_neutral,
            peer_loan_link,
        }
    }
}

/// What the writes read besides the database: prices for the ledger's FX, and the clock.
#[derive(Clone, Copy)]
pub struct ManualEntryContext<'a> {
    pub prices: &'a dyn PriceRead,
    pub today: NaiveDate,
    pub now: DateTime<Utc>,
}

/// The PreciousMetal catalogue tickers, in their canonical spelling.
const PRECIOUS_METAL_TICKERS: [&str; 16] = [
    "GOLD:GRAM",
    "GOLD:NAPOLEON-20F",
    "GOLD:NAPOLEON-10F",
    "GOLD:SWISS-20F",
    "GOLD:LATIN-UNION-20F",
    "GOLD:TUNISIA-20F",
    "GOLD:SOVEREIGN",
    "GOLD:SOVEREIGN-EII",
    "GOLD:HALF-SOVEREIGN",
    "GOLD:KRUGERRAND",
    "GOLD:USD-20",
    "GOLD:USD-10",
    "GOLD:USD-5",
    "GOLD:PESO-50",
    "GOLD:FLORIN-10",
    "GOLD:REICHSMARK-20",
];

/// PreciousMetal.canonicalTickerFor: a metals account takes catalogue tickers only, returned in
/// their canonical spelling; a blank ticker and any other account type pass through.
pub fn canonical_ticker_for(
    account_type: AccountType,
    ticker: Option<&str>,
) -> AppResult<Option<String>> {
    let Some(ticker) = ticker else {
        return Ok(None);
    };
    if account_type != AccountType::PreciousMetals || java_is_blank(ticker) {
        return Ok(Some(ticker.to_owned()));
    }
    let wanted = java_trim(ticker).to_uppercase();
    PRECIOUS_METAL_TICKERS
        .iter()
        .find(|t| **t == wanted)
        .map(|t| Some((*t).to_owned()))
        .ok_or_else(|| {
            AppError::BadRequest(format!(
                "'{}' is not a precious metals product",
                java_trim(ticker)
            ))
        })
}

/// The resolved (ticker, name, description) of a trade, None for a cash movement.
async fn instrument_fields(
    account_type: AccountType,
    req: &TransactionRequest,
    lookup: impl AsyncFnOnce(&str) -> TickerResult,
) -> AppResult<Option<instrument::ResolvedInstrument>> {
    let ticker = canonical_ticker_for(account_type, req.ticker.as_deref())?;
    Ok(instrument::resolve(ticker.as_deref(), req.name.as_deref(), lookup).await)
}

fn validate_fees(fees: Option<Decimal>) -> AppResult<()> {
    if fees.is_some_and(|f| f < Decimal::ZERO) {
        return Err(AppError::BadRequest("Fees cannot be negative".to_owned()));
    }
    Ok(())
}

async fn resolve_category<'e>(
    db: impl DbExec<'e>,
    category_id: i64,
    member_id: i64,
) -> AppResult<Category> {
    categorize::category_of_member(db, category_id, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Category not found".to_owned()))
}

async fn find_manual_transaction<'e>(
    db: impl DbExec<'e>,
    account_id: i64,
    tx_id: i64,
    refusal: &str,
) -> AppResult<Transaction> {
    let tx = transactions::find_by_id_and_account(db, tx_id, account_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Transaction not found".to_owned()))?;
    if !tx.is_manual {
        return Err(AppError::BadRequest(refusal.to_owned()));
    }
    Ok(tx)
}

async fn respond<'e>(
    db: impl DbExec<'e>,
    tx: &Transaction,
    account: &Account,
) -> AppResult<TransactionResponse> {
    let category_name = match tx.category_id {
        Some(id) => categorize::category_of_member(db, id, account.member_id)
            .await?
            .map(|c| c.name),
        None => None,
    };
    Ok(TransactionResponse::from_row(
        tx,
        &account.name,
        category_name,
        None,
    ))
}

/// addTransaction: records a manual row on the member's account (404 otherwise). An explicit
/// category is kept as a manual choice; without one the automatic pipeline runs. lookup is the
/// OpenFIGI resolution of an ISIN ticker.
pub async fn add_transaction(
    conn: &mut DbHandle<'_>,
    ctx: ManualEntryContext<'_>,
    categorization: &CategorizationContext,
    account_id: i64,
    member_id: i64,
    req: &TransactionRequest,
    lookup: impl AsyncFnOnce(&str) -> TickerResult,
) -> AppResult<TransactionResponse> {
    let required = validated(req)?;
    let account = accounts::get_or_throw(&mut *conn, account_id, member_id).await?;
    validate_fees(req.fees)?;

    let mut tx = NewTransaction::new(
        account.id,
        required.date,
        required.description,
        required.amount,
    );
    tx.tx_type = req.tx_type;
    tx.quantity = req.quantity;
    tx.price_per_unit = req.price_per_unit;
    tx.fees = req.fees;
    tx.is_manual = true;
    tx.native_currency = req.currency.clone().unwrap_or_else(|| "EUR".to_owned());
    if let Some(resolved) = instrument_fields(account.account_type, req, lookup).await? {
        tx.ticker = Some(resolved.ticker);
        tx.name = resolved.name;
        tx.description = resolved.description;
    }

    match req.category_id {
        Some(category_id) => {
            let category = resolve_category(&mut *conn, category_id, member_id).await?;
            tx.category_id = Some(category.id);
            tx.category_manual = true;
            categorize::enrich(&mut tx, &categorization.knowledge_base);
        }
        None => {
            categorize::auto_categorize(&mut tx, categorization);
        }
    }

    let saved = transactions::insert(&mut *conn, &tx, ctx.now).await?;
    recompute_derived_state(&mut *conn, ctx, &account).await?;
    // Java answers with the entity it saved: the figures as entered, not at the column scale.
    let entered = Transaction {
        amount: tx.amount,
        quantity: tx.quantity,
        price_per_unit: tx.price_per_unit,
        fees: tx.fees,
        ..saved
    };
    respond(&mut *conn, &entered, &account).await
}

/// updateTransaction: rewrites a manual row of the member's account; a synced row is refused.
/// Optional fields left null keep their stored value.
pub async fn update_transaction(
    conn: &mut DbHandle<'_>,
    ctx: ManualEntryContext<'_>,
    account_id: i64,
    tx_id: i64,
    member_id: i64,
    req: &TransactionRequest,
    lookup: impl AsyncFnOnce(&str) -> TickerResult,
) -> AppResult<TransactionResponse> {
    let required = validated(req)?;
    let account = accounts::get_or_throw(&mut *conn, account_id, member_id).await?;
    let mut tx = find_manual_transaction(
        &mut *conn,
        account.id,
        tx_id,
        "Cannot edit a synced transaction",
    )
    .await?;
    validate_fees(req.fees)?;

    tx.date = required.date;
    tx.description = required.description;
    tx.amount = required.amount;
    if req.tx_type.is_some() {
        tx.tx_type = req.tx_type;
    }
    if req.quantity.is_some() {
        tx.quantity = req.quantity;
    }
    if req.price_per_unit.is_some() {
        tx.price_per_unit = req.price_per_unit;
    }
    if req.fees.is_some() {
        tx.fees = req.fees;
    }
    if let Some(currency) = &req.currency {
        tx.native_currency.clone_from(currency);
    }
    if let Some(resolved) = instrument_fields(account.account_type, req, lookup).await? {
        tx.ticker = Some(resolved.ticker);
        tx.name = resolved.name;
        tx.description = resolved.description;
    }
    if let Some(category_id) = req.category_id {
        let category = resolve_category(&mut *conn, category_id, member_id).await?;
        tx.category_id = Some(category.id);
        tx.category_manual = true;
    }

    transactions::update(&mut *conn, &tx).await?;
    recompute_derived_state(&mut *conn, ctx, &account).await?;
    // The managed entity, as Java answers: entered figures as entered, stored ones as stored.
    respond(&mut *conn, &tx, &account).await
}

/// deleteTransaction: removes a manual row of the member's account; a synced row is refused.
pub async fn delete_transaction(
    conn: &mut DbHandle<'_>,
    ctx: ManualEntryContext<'_>,
    account_id: i64,
    tx_id: i64,
    member_id: i64,
) -> AppResult<()> {
    let account = accounts::get_or_throw(&mut *conn, account_id, member_id).await?;
    let tx = find_manual_transaction(
        &mut *conn,
        account.id,
        tx_id,
        "Cannot delete a synced transaction",
    )
    .await?;
    transactions::delete(&mut *conn, tx.id).await?;
    recompute_derived_state(&mut *conn, ctx, &account).await
}

/// Only a manual account derives state from its rows: an investment one its holdings, a ledger
/// one its balance and history. A valued account (SCPI, property, employee savings) keeps its
/// valuation, and a synced one its provider's figures.
async fn recompute_derived_state(
    conn: &mut DbHandle<'_>,
    ctx: ManualEntryContext<'_>,
    account: &Account,
) -> AppResult<()> {
    if !account.is_manual {
        return Ok(());
    }
    if account.account_type.is_investment() {
        holding_compute::recompute_holdings(&mut *conn, account.id, ctx.now).await?;
    } else if account.account_type.is_ledger() {
        let balance = manual_ledger::compute_manual_ledger_balance(
            &mut *conn, account, ctx.prices, ctx.today,
        )
        .await?;
        accounts::set_balance(&mut *conn, account.id, balance, None, ctx.now).await?;
        let rebalanced = Account {
            current_balance: balance,
            ..account.clone()
        };
        manual_ledger::reconstruct_snapshots_from_db(
            &mut *conn,
            &rebalanced,
            ctx.prices,
            ctx.today,
            ctx.now,
        )
        .await?;
    }
    Ok(())
}
