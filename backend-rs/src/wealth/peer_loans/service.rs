//! PeerLoanService: the reads, the writes and the cash legs written on a manual linked account.

use std::cmp::Ordering;
use std::collections::HashMap;

use chrono::{Datelike, Months, NaiveDate};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::dto::*;
use super::repo;
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize::{self, CategorizationContext};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::instrument::TickerResult;
use crate::ledger::manual_transactions::{
    ManualEntryContext, TransactionRequest, add_transaction, delete_transaction, update_transaction,
};
use crate::ledger::model::{
    Account, AccountType, PeerLoanRatePeriod, PeerLoanRepayment, PeerLoanRepaymentKind,
    PeerLoanTerms, TransactionType,
};
use crate::ledger::peer_loans::{self, PeerLoanError};
use crate::ledger::snapshots;
use crate::ledger::sync_values::{java_is_blank, java_trim};
use crate::ledger::transactions;
use crate::ledger::valuation::{self, SnapshotContext};

/// The note of the write-off a closure creates; PeerLoanDetailSection.tsx matches this text.
const CLOSURE_NOTE: &str = "Closure write-off";
/// The seeded category for money moving between the member's own accounts.
const TRANSFER_SLUG: &str = "virement-interne";
const MAX_ACCOUNT_NAME: usize = 100;
/// No real loan or payment predates this; a date seeds the history repair and the curve.
const MIN_DATE: NaiveDate = NaiveDate::from_ymd_opt(1900, 1, 1).unwrap();

/// The account types a peer loan can draw from and repay into.
const CASH_TYPES: &[AccountType] = &[
    AccountType::Checking,
    AccountType::Savings,
    AccountType::Lep,
    AccountType::LivretA,
    AccountType::Ldds,
    AccountType::LivretJeune,
    AccountType::Pel,
    AccountType::Cel,
];

fn bad(message: impl Into<String>) -> AppError {
    AppError::BadRequest(message.into())
}

fn calc_error(error: PeerLoanError) -> AppError {
    bad(error.to_string())
}

/// What the writes need besides the connection, loaded before the transaction opens: the
/// categorization context and the transfer category read through the pool (they seed a
/// member's categories, which must not wait on rows this transaction locks).
struct WriteContext<'a> {
    state: &'a AppState,
    member_id: i64,
    today: NaiveDate,
    categorization: CategorizationContext,
    transfer_category_id: Option<i64>,
}

impl<'a> WriteContext<'a> {
    async fn load(state: &'a AppState, member_id: i64) -> AppResult<Self> {
        let now = state.clock.now();
        let kb = state.ledger.knowledge_base(&state.db).await?;
        let categorization = categorize::load_context(&state.db, kb, member_id, now).await?;
        let transfer_category_id = categorization
            .categories_by_slug
            .get(TRANSFER_SLUG)
            .map(|c| c.id);
        Ok(Self {
            state,
            member_id,
            today: state.clock.today(),
            categorization,
            transfer_category_id,
        })
    }

    fn entry(&self) -> ManualEntryContext<'_> {
        ManualEntryContext {
            prices: &*self.state.ledger.prices,
            today: self.today,
            now: self.state.clock.now(),
        }
    }

    /// The seeded transfer category, created for an older member whose seeds predate it:
    /// without one the outgoing leg would count as spending.
    async fn transfer_category(&mut self, conn: &mut PgConnection) -> AppResult<i64> {
        if let Some(id) = self.transfer_category_id {
            return Ok(id);
        }
        let id: i64 = sqlx::query_scalar(
            "INSERT INTO category (member_id, slug, name, kind, color, icon, is_default, archived, sort_order, \
             created_at, updated_at) VALUES ($1, $2, 'Virement interne', 'TRANSFER'::category_kind, '#64748b', \
             'arrow-left-right', true, false, 0, $3, $3) RETURNING id",
        )
        .bind(self.member_id)
        .bind(TRANSFER_SLUG)
        .bind(self.state.clock.now())
        .fetch_one(conn)
        .await?;
        self.transfer_category_id = Some(id);
        Ok(id)
    }
}

/// The legs carry no instrument, so the ledger never asks for an ISIN lookup.
async fn no_instrument(isin: &str) -> TickerResult {
    TickerResult {
        ticker: isin.to_owned(),
        name: None,
    }
}

// --- Reads ---

pub async fn get(
    state: &AppState,
    account_id: i64,
    member_id: i64,
) -> AppResult<PeerLoanDetailResponse> {
    let account = accounts::get_or_throw(&state.db, account_id, member_id).await?;
    require_type(&account)?;
    let mut conn = state.db.acquire().await?;
    match repo::find_terms(&mut *conn, account_id).await? {
        Some(terms) => build(&mut conn, &account, &terms, state.clock.today(), &[]).await,
        None => Ok(unconfigured(&account)),
    }
}

pub fn simulate(req: &PeerLoanSimulationRequest) -> AppResult<PeerLoanSimulationResponse> {
    let simulation = match (req.months, req.monthly_payment) {
        (Some(months), None) => {
            let months =
                i32::try_from(months).map_err(|_| calc_error(PeerLoanError::TooManyMonths))?;
            peer_loans::simulate_by_months(req.principal, req.annual_rate, months)
        }
        (None, Some(payment)) => {
            peer_loans::simulate_by_payment(req.principal, req.annual_rate, Some(payment))
        }
        _ => return Err(bad("Provide exactly one of months or monthlyPayment")),
    }
    .map_err(calc_error)?;
    Ok(PeerLoanSimulationResponse {
        monthly_payment: simulation.monthly_payment,
        months: simulation.months,
        total_interest: simulation.total_interest,
        total_cost: simulation.total_cost,
    })
}

/// The open loans a received transaction can be assigned to.
pub async fn open_loans(state: &AppState, member_id: i64) -> AppResult<Vec<PeerLoanOpenResponse>> {
    let today = state.clock.today();
    let mut result = Vec::new();
    for account in accounts::find_all_by_member(&state.db, member_id).await? {
        if account.account_type != AccountType::PeerLoan {
            continue;
        }
        let Some(terms) = repo::find_terms(&state.db, account.id).await? else {
            continue;
        };
        if terms.closed_at.is_some() {
            continue;
        }
        let repayments = repo::repayments(&state.db, account.id).await?;
        let remaining = peer_loans::compute(&terms, &repayments, today).principal_remaining;
        result.push(PeerLoanOpenResponse {
            account_id: account.id,
            name: account.name,
            remaining,
        });
    }
    result.sort_by(|a, b| case_insensitive(&a.name, &b.name));
    Ok(result)
}

/// Every configured peer loan as one view, with the total outstanding on each history point
/// (HistoryService over the same accounts, so this and the net-worth chart cannot disagree).
pub async fn overview(
    state: &AppState,
    member_id: i64,
    months: i32,
) -> AppResult<PeerLoansOverviewResponse> {
    let today = state.clock.today();
    let mut total_outstanding = Decimal::ZERO;
    let mut interest_accrued = Decimal::ZERO;
    let mut open_count = 0;
    let mut closed_count = 0;
    let mut loans = Vec::new();
    let mut configured_ids = Vec::new();

    for account in accounts::find_all_by_member(&state.db, member_id).await? {
        if account.account_type != AccountType::PeerLoan {
            continue;
        }
        let Some(terms) = repo::find_terms(&state.db, account.id).await? else {
            continue;
        };
        let repayments = repo::repayments(&state.db, account.id).await?;
        let computed = peer_loans::compute(&terms, &repayments, today);
        let plan = peer_loans::plan(&terms, Some(computed.principal_remaining), today)
            .map_err(calc_error)?;
        if terms.closed_at.is_none() {
            open_count += 1;
            total_outstanding += computed.principal_remaining;
        } else {
            closed_count += 1;
        }
        interest_accrued += computed.interest_accrued;
        let last = repayments.last();
        loans.push(LoanEntry {
            account_id: account.id,
            name: account.name.clone(),
            principal: terms.principal,
            principal_remaining: computed.principal_remaining,
            repaid_pct: peer_loans::repaid_pct(
                Some(terms.principal),
                Some(computed.principal_paid),
            ),
            interest_accrued: computed.interest_accrued,
            interest_received: computed.interest_received,
            lent_at: terms.lent_at,
            expected_back: terms.expected_back,
            installments_behind: plan.and_then(|p| p.installments_behind),
            last_payment_date: last.map(|r| r.paid_on),
            last_payment_amount: last.map(|r| r.amount),
            closed_at: terms.closed_at,
        });
        configured_ids.push(account.id);
    }

    // Open loans first, then alphabetical: the running one is what the reader came for.
    loans.sort_by(|a, b| {
        a.closed_at
            .is_some()
            .cmp(&b.closed_at.is_some())
            .then_with(|| case_insensitive(&a.name, &b.name))
    });

    let history = if configured_ids.is_empty() {
        Vec::new()
    } else {
        let points = state
            .wealth
            .history
            .build_history(state, &configured_ids, months.clamp(1, 24), true, member_id)
            .await?;
        points
            .into_iter()
            .map(|point| {
                let outstanding = configured_ids
                    .iter()
                    .filter_map(|id| point.accounts.as_ref().and_then(|a| a.get(id)))
                    .map(|p| p.total.unwrap_or_default())
                    .sum();
                OverviewPoint {
                    date: point.date,
                    outstanding,
                }
            })
            .collect()
    };

    Ok(PeerLoansOverviewResponse {
        total_outstanding,
        open_count,
        closed_count,
        interest_accrued,
        loans,
        history,
    })
}

// --- Creation from an existing movement ---

/// A loan whose lending leg is an existing outgoing transaction, linked and categorised as a
/// transfer, never copied: the money already left the account.
pub async fn create_from_transaction(
    state: &AppState,
    member_id: i64,
    req: &PeerLoanFromTransactionRequest,
) -> AppResult<PeerLoanDetailResponse> {
    let transaction_id = req.transaction_id.unwrap_or_default();
    let mut ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let today = ctx.today;
    let mut tx = state.db.begin().await?;

    let (mut transaction, source) = owned_transaction(&mut tx, transaction_id, member_id).await?;
    if transaction.amount >= Decimal::ZERO {
        return Err(bad("Only an outgoing transaction can fund a peer loan"));
    }
    if !CASH_TYPES.contains(&source.account_type) {
        return Err(bad("Only a cash movement can fund a peer loan"));
    }
    require_eur(&source, "fund a peer loan")?;
    if repo::lending_transaction_linked(&mut *tx, transaction.id).await? {
        return Err(bad("This transaction is already linked to a peer loan"));
    }
    if transaction.date > today {
        return Err(bad("The transaction date cannot be in the future"));
    }
    if transaction.date < MIN_DATE {
        return Err(bad("The transaction date is too far in the past"));
    }

    let name = beneficiary_name(req.beneficiary.as_deref(), &transaction);
    let principal = transaction.amount.abs();
    let mut draft = NewAccount::new(member_id, name.clone(), AccountType::PeerLoan);
    draft.currency = "EUR".to_owned();
    draft.current_balance = principal;
    draft.is_manual = true;
    draft.color = "#0d9488".to_owned();
    let mut account = accounts::insert(&mut *tx, &draft, now).await?;

    let counterparty_id = counterparty(&mut tx, member_id, &name, now).await?;
    let mut terms = PeerLoanTerms {
        id: 0,
        account_id: account.id,
        counterparty_id,
        principal,
        lent_at: transaction.date,
        expected_back: None,
        annual_rate: Some(Decimal::ZERO),
        rate_period: PeerLoanRatePeriod::Annual,
        planned_monthly_amount: None,
        planned_months: None,
        linked_account_id: Some(source.id),
        lending_transaction_id: Some(transaction.id),
        note: None,
        closed_at: None,
    };
    terms.id = repo::save_terms(&mut tx, &terms, now).await?;

    transaction.category_id = Some(ctx.transfer_category(&mut tx).await?);
    transaction.category_manual = true;
    transactions::update(&mut *tx, &transaction).await?;

    // The transaction is the lending event: a backdated row lands on its own date in history.
    refresh_balance(
        &mut tx,
        &ctx,
        &mut account,
        &mut terms,
        Some(transaction.date),
    )
    .await?;
    let response = build(&mut tx, &account, &terms, today, &[]).await?;
    tx.commit().await?;
    Ok(response)
}

// --- Writes ---

pub async fn save_terms(
    state: &AppState,
    account_id: i64,
    member_id: i64,
    req: &PeerLoanTermsRequest,
) -> AppResult<PeerLoanDetailResponse> {
    let (Some(principal), Some(lent_at), Some(linked_account_id)) =
        (req.principal, req.lent_at, req.linked_account_id)
    else {
        return Err(bad("Failed to read request"));
    };
    let mut ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let today = ctx.today;
    let mut tx = state.db.begin().await?;

    let mut account = accounts::get_or_throw(&mut *tx, account_id, member_id).await?;
    require_type(&account)?;
    validate_plan(req)?;
    if lent_at > today {
        return Err(bad("The lending date cannot be in the future"));
    }
    if lent_at < MIN_DATE {
        return Err(bad("The lending date is too far in the past"));
    }
    if req.expected_back.is_some_and(|back| back < lent_at) {
        return Err(bad(
            "The expected return date cannot precede the lending date",
        ));
    }
    if linked_account_id == account_id {
        return Err(bad("A loan cannot be linked to itself"));
    }

    let existing = repo::find_terms(&mut *tx, account_id).await?;
    // The lending outflow is a first-configuration decision only: a loan recorded after the
    // fact may already be in the cash account's balance, and a second outflow would double it.
    let first_configuration = existing.is_none();
    let with_cash_leg = req.with_cash_leg.unwrap_or(true);
    if existing.as_ref().is_some_and(|t| t.closed_at.is_some()) {
        return Err(bad(
            "This loan is closed. Delete its write-off to reopen it before editing the terms.",
        ));
    }

    let linked = accounts::get_or_throw(&mut *tx, linked_account_id, member_id).await?;
    if !CASH_TYPES.contains(&linked.account_type) {
        return Err(bad("A peer loan must be linked to a cash account"));
    }
    require_eur(&linked, "be linked to a peer loan")?;

    // The cash movement is a fact: once it exists, changing what it says would rewrite it.
    if let Some(t) = existing
        .as_ref()
        .filter(|t| t.lending_transaction_id.is_some())
    {
        if t.principal != principal {
            return Err(bad(
                "The amount cannot change once the cash movement is recorded. Delete the loan and create it again.",
            ));
        }
        if t.linked_account_id.is_some_and(|id| id != linked.id) {
            return Err(bad(
                "The cash account cannot change once the cash movement is recorded. Delete the loan and create it again.",
            ));
        }
        if t.lent_at != lent_at {
            return Err(bad(
                "The lending date cannot change once the cash movement is recorded. Delete the loan and create it again.",
            ));
        }
    }

    let counterparty_id = counterparty(&mut tx, member_id, &account.name, now).await?;
    let previous_lent_at = existing.as_ref().map(|t| t.lent_at);
    let mut terms = existing.unwrap_or(PeerLoanTerms {
        id: 0,
        account_id,
        counterparty_id,
        principal,
        lent_at,
        expected_back: None,
        annual_rate: None,
        rate_period: PeerLoanRatePeriod::Annual,
        planned_monthly_amount: None,
        planned_months: None,
        linked_account_id: None,
        lending_transaction_id: None,
        note: None,
        closed_at: None,
    });
    terms.counterparty_id = counterparty_id;
    terms.principal = principal;
    terms.lent_at = lent_at;
    terms.expected_back = req.expected_back;
    terms.annual_rate = Some(peer_loans::rate(req.annual_rate));
    terms.rate_period = req.rate_period.unwrap_or(PeerLoanRatePeriod::Annual);
    terms.planned_monthly_amount = req.planned_monthly_amount;
    terms.planned_months = req.planned_months.map(|m| m as i32);
    terms.linked_account_id = Some(linked.id);
    terms.note = req.note.clone();
    terms.id = repo::save_terms(&mut tx, &terms, now).await?;

    // The principal and the ledger are EUR figures: another currency would be converted twice.
    account.currency = "EUR".to_owned();
    if first_configuration && with_cash_leg {
        sync_lending_leg(&mut tx, &mut ctx, &account, &mut terms, &linked).await?;
        repo::save_terms(&mut tx, &terms, now).await?;
    }

    // Postponing the lending date repairs history from the earlier date too.
    let affected_from = match previous_lent_at {
        Some(previous) if previous < lent_at => previous,
        _ => lent_at,
    };
    refresh_balance(&mut tx, &ctx, &mut account, &mut terms, Some(affected_from)).await?;
    let response = build(&mut tx, &account, &terms, today, &[]).await?;
    tx.commit().await?;
    Ok(response)
}

pub async fn add_repayment(
    state: &AppState,
    account_id: i64,
    member_id: i64,
    req: &PeerLoanRepaymentRequest,
) -> AppResult<PeerLoanRepaymentResponse> {
    let (Some(paid_on), Some(amount)) = (req.paid_on, req.amount) else {
        return Err(bad("Failed to read request"));
    };
    let mut ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;

    let mut account = accounts::get_or_throw(&mut *tx, account_id, member_id).await?;
    require_type(&account)?;
    let mut terms = lock_configured_terms(&mut tx, account_id).await?;
    require_open(&terms)?;
    require_completed_repayment_date(paid_on, ctx.today)?;
    let kind = req.kind.unwrap_or(PeerLoanRepaymentKind::Principal);
    // A write-off is a closure decision, recorded by close() next to closedAt.
    if kind == PeerLoanRepaymentKind::WriteOff {
        return Err(bad(
            "A write-off is recorded by closing the loan, not by adding a repayment",
        ));
    }
    validate_interest_repayment(&mut tx, &terms, kind, amount, paid_on, None).await?;
    validate_repayment_amount(&mut tx, &terms, amount, paid_on, None).await?;

    let mut repayment = PeerLoanRepayment {
        id: 0,
        account_id,
        paid_on,
        amount,
        kind,
        note: req.note.clone(),
        cash_transaction_id: None,
        cash_leg_owned: true,
    };
    repayment.id = repo::insert_repayment(&mut tx, &repayment, now).await?;

    // A compensation or an already-booked payment leaves the cash account alone.
    if req.with_cash_leg.unwrap_or(true) {
        sync_repayment_leg(&mut tx, &mut ctx, &account, &terms, &mut repayment).await?;
        repo::update_repayment(&mut tx, &repayment, now).await?;
    }

    refresh_balance(&mut tx, &ctx, &mut account, &mut terms, Some(paid_on)).await?;
    tx.commit().await?;
    Ok(PeerLoanRepaymentResponse::from(&repayment, None))
}

/// Links an existing incoming transaction as a repayment; the transaction is the cash row.
pub async fn add_repayment_from_transaction(
    state: &AppState,
    account_id: i64,
    member_id: i64,
    req: &PeerLoanRepaymentFromTransactionRequest,
) -> AppResult<PeerLoanDetailResponse> {
    let transaction_id = req.transaction_id.unwrap_or_default();
    let mut ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let today = ctx.today;
    let mut tx = state.db.begin().await?;

    let mut account = accounts::get_or_throw(&mut *tx, account_id, member_id).await?;
    require_type(&account)?;
    let mut terms = lock_configured_terms(&mut tx, account_id).await?;
    require_open(&terms)?;
    let kind = req.kind.unwrap_or(PeerLoanRepaymentKind::Principal);
    if kind == PeerLoanRepaymentKind::WriteOff {
        return Err(bad("A received transaction cannot be a write-off"));
    }

    let (mut transaction, source) = owned_transaction(&mut tx, transaction_id, member_id).await?;
    if transaction.amount <= Decimal::ZERO {
        return Err(bad("Only an incoming transaction can repay a peer loan"));
    }
    if !CASH_TYPES.contains(&source.account_type) {
        return Err(bad("Only a cash movement can repay a peer loan"));
    }
    require_eur(&source, "repay a peer loan")?;
    if repo::cash_transaction_linked(&mut *tx, transaction.id).await? {
        return Err(bad("This transaction is already linked to a repayment"));
    }
    if repo::lending_transaction_linked(&mut *tx, transaction.id).await? {
        return Err(bad("This transaction is already linked to a peer loan"));
    }
    require_completed_repayment_date(transaction.date, today)?;
    validate_interest_repayment(
        &mut tx,
        &terms,
        kind,
        transaction.amount,
        transaction.date,
        None,
    )
    .await?;
    // No overpayment check: the transaction is already counted in net worth, so linking it
    // creates no money; the ledger clamps to what the loan can absorb.

    let mut repayment = PeerLoanRepayment {
        id: 0,
        account_id,
        paid_on: transaction.date,
        amount: transaction.amount,
        kind,
        note: None,
        cash_transaction_id: Some(transaction.id),
        cash_leg_owned: false,
    };
    repayment.id = repo::insert_repayment(&mut tx, &repayment, now).await?;

    // An explicit interest payment is real income and stays uncategorised; any other kind is
    // one transfer, its interest part living in the loan ledger.
    transaction.category_id = if kind == PeerLoanRepaymentKind::Interest {
        None
    } else {
        Some(ctx.transfer_category(&mut tx).await?)
    };
    transaction.category_manual = true;
    transactions::update(&mut *tx, &transaction).await?;

    refresh_balance(
        &mut tx,
        &ctx,
        &mut account,
        &mut terms,
        Some(transaction.date),
    )
    .await?;
    let response = build(&mut tx, &account, &terms, today, &[repayment]).await?;
    tx.commit().await?;
    Ok(response)
}

pub async fn update_repayment(
    state: &AppState,
    account_id: i64,
    repayment_id: i64,
    member_id: i64,
    req: &PeerLoanRepaymentRequest,
) -> AppResult<PeerLoanRepaymentResponse> {
    let (Some(paid_on), Some(amount)) = (req.paid_on, req.amount) else {
        return Err(bad("Failed to read request"));
    };
    let mut ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;

    let mut account = accounts::get_or_throw(&mut *tx, account_id, member_id).await?;
    require_type(&account)?;
    let mut terms = lock_configured_terms(&mut tx, account_id).await?;
    require_open(&terms)?;

    let mut repayment = repo::find_repayment(&mut *tx, repayment_id, account_id)
        .await?
        .ok_or_else(repayment_not_found)?;
    if !repayment.cash_leg_owned {
        return Err(bad(
            "This repayment is linked to an existing transaction. Delete it and assign the transaction again to change it.",
        ));
    }
    require_completed_repayment_date(paid_on, ctx.today)?;
    let effective_kind = req.kind.unwrap_or(repayment.kind);
    if effective_kind == PeerLoanRepaymentKind::WriteOff {
        return Err(bad(
            "A write-off is recorded by closing the loan, not by editing a repayment",
        ));
    }
    validate_interest_repayment(
        &mut tx,
        &terms,
        effective_kind,
        amount,
        paid_on,
        Some(repayment.id),
    )
    .await?;
    validate_repayment_amount(&mut tx, &terms, amount, paid_on, Some(repayment.id)).await?;

    // Moving a repayment changes the balance from the earlier of its two dates.
    let affected_from = repayment.paid_on.min(paid_on);

    delete_cash_leg(&mut tx, &ctx, &terms, &mut repayment).await?;
    repayment.paid_on = paid_on;
    repayment.amount = amount;
    // An absent kind leaves it as it was: the dialog may only change the amount or the date.
    if let Some(kind) = req.kind {
        repayment.kind = kind;
    }
    repayment.note = req.note.clone();
    repayment.cash_transaction_id = None;
    repayment.cash_leg_owned = true;
    if req.with_cash_leg.unwrap_or(true) {
        sync_repayment_leg(&mut tx, &mut ctx, &account, &terms, &mut repayment).await?;
    }
    repo::update_repayment(&mut tx, &repayment, now).await?;

    refresh_balance(&mut tx, &ctx, &mut account, &mut terms, Some(affected_from)).await?;
    tx.commit().await?;
    Ok(PeerLoanRepaymentResponse::from(&repayment, None))
}

pub async fn delete_repayment(
    state: &AppState,
    account_id: i64,
    repayment_id: i64,
    member_id: i64,
) -> AppResult<()> {
    let ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let mut tx = state.db.begin().await?;

    let mut account = accounts::get_or_throw(&mut *tx, account_id, member_id).await?;
    require_type(&account)?;
    let mut terms = lock_configured_terms(&mut tx, account_id).await?;

    let mut repayment = repo::find_repayment(&mut *tx, repayment_id, account_id)
        .await?
        .ok_or_else(repayment_not_found)?;
    let paid_on = repayment.paid_on;
    delete_cash_leg(&mut tx, &ctx, &terms, &mut repayment).await?;
    repo::delete_repayment(&mut tx, repayment.id).await?;

    // Deleting the write-off that closed the loan reopens it.
    let remaining = repo::repayments(&mut *tx, account_id).await?;
    let outstanding = peer_loans::compute(&terms, &remaining, ctx.today).principal_remaining;
    if terms.closed_at.is_some() && outstanding > Decimal::ZERO {
        terms.closed_at = None;
        repo::save_terms(&mut tx, &terms, now).await?;
    }

    refresh_balance(&mut tx, &ctx, &mut account, &mut terms, Some(paid_on)).await?;
    tx.commit().await?;
    Ok(())
}

/// Writes the residual off as a dated WRITE_OFF row: the account is worth zero from that date
/// and the past is never rewritten. Closing a closed loan is a no-op.
pub async fn close(
    state: &AppState,
    account_id: i64,
    member_id: i64,
    closed_at: Option<NaiveDate>,
) -> AppResult<PeerLoanDetailResponse> {
    let ctx = WriteContext::load(state, member_id).await?;
    let now = state.clock.now();
    let today = ctx.today;
    let mut tx = state.db.begin().await?;

    let mut account = accounts::get_or_throw(&mut *tx, account_id, member_id).await?;
    require_type(&account)?;
    let mut terms = lock_configured_terms(&mut tx, account_id).await?;

    let date = closed_at.unwrap_or(today);
    if terms.closed_at.is_some() {
        let response = build(&mut tx, &account, &terms, today, &[]).await?;
        tx.commit().await?;
        return Ok(response);
    }
    if date < terms.lent_at {
        return Err(bad("The closure date cannot precede the lending date"));
    }
    if date > today {
        return Err(bad("The closure date cannot be in the future"));
    }
    if date < MIN_DATE {
        return Err(bad("The closure date is too far in the past"));
    }

    // Only the ledger up to the closure date counts: a later repayment is not erased.
    let repayments = repo::repayments(&mut *tx, account_id).await?;
    let up_to_date = up_to(&repayments, date);
    let residual = peer_loans::compute(&terms, &up_to_date, date).principal_remaining;
    let mut fresh = Vec::new();
    if residual > Decimal::ZERO {
        let mut write_off = PeerLoanRepayment {
            id: 0,
            account_id,
            paid_on: date,
            amount: residual,
            kind: PeerLoanRepaymentKind::WriteOff,
            note: Some(CLOSURE_NOTE.to_owned()),
            cash_transaction_id: None,
            cash_leg_owned: true,
        };
        write_off.id = repo::insert_repayment(&mut tx, &write_off, now).await?;
        fresh.push(write_off);
    }
    terms.closed_at = Some(date);
    repo::save_terms(&mut tx, &terms, now).await?;

    refresh_balance(&mut tx, &ctx, &mut account, &mut terms, Some(date)).await?;
    let response = build(&mut tx, &account, &terms, today, &fresh).await?;
    tx.commit().await?;
    Ok(response)
}

// --- Cash legs ---

/// Writes the lending outflow on a manual linked account, once; a synced account already has
/// the provider's row.
async fn sync_lending_leg(
    conn: &mut PgConnection,
    ctx: &mut WriteContext<'_>,
    account: &Account,
    terms: &mut PeerLoanTerms,
    linked: &Account,
) -> AppResult<()> {
    if terms.lending_transaction_id.is_some() || !linked.is_manual {
        return Ok(());
    }
    let leg = TransactionRequest {
        date: Some(terms.lent_at),
        description: Some(format!("Prêt — {}", account.name)),
        amount: Some(-terms.principal),
        tx_type: Some(TransactionType::Withdrawal),
        currency: Some("EUR".to_owned()),
        category_id: Some(ctx.transfer_category(conn).await?),
        ..TransactionRequest::default()
    };
    let created = add_transaction(
        conn,
        ctx.entry(),
        &ctx.categorization,
        linked.id,
        ctx.member_id,
        &leg,
        no_instrument,
    )
    .await?;
    terms.lending_transaction_id = Some(created.id);
    Ok(())
}

/// Mirrors one repayment on the manual cash account: capital is a transfer, interest is real
/// income left uncategorised so the cashflow counts it by sign; a write-off moves no cash.
async fn sync_repayment_leg(
    conn: &mut PgConnection,
    ctx: &mut WriteContext<'_>,
    account: &Account,
    terms: &PeerLoanTerms,
    repayment: &mut PeerLoanRepayment,
) -> AppResult<()> {
    let Some(linked_id) = terms.linked_account_id else {
        return Ok(());
    };
    let Some(linked) = accounts::find_by_id(&mut *conn, linked_id).await? else {
        return Ok(());
    };
    if !linked.is_manual || repayment.kind == PeerLoanRepaymentKind::WriteOff {
        return Ok(());
    }
    // The member may have changed the account's currency since it was linked.
    require_eur(&linked, "receive a repayment")?;
    let interest = repayment.kind == PeerLoanRepaymentKind::Interest;
    let leg = TransactionRequest {
        date: Some(repayment.paid_on),
        description: Some(if interest {
            format!("Intérêts — {}", account.name)
        } else {
            format!("Remboursement — {}", account.name)
        }),
        amount: Some(repayment.amount),
        tx_type: Some(TransactionType::Deposit),
        currency: Some("EUR".to_owned()),
        category_id: if interest {
            None
        } else {
            Some(ctx.transfer_category(conn).await?)
        },
        ..TransactionRequest::default()
    };
    match repayment.cash_transaction_id {
        None => {
            let created = add_transaction(
                conn,
                ctx.entry(),
                &ctx.categorization,
                linked.id,
                ctx.member_id,
                &leg,
                no_instrument,
            )
            .await?;
            repayment.cash_transaction_id = Some(created.id);
            repayment.cash_leg_owned = true;
        }
        Some(cash_id) => {
            update_transaction(
                conn,
                ctx.entry(),
                linked.id,
                cash_id,
                ctx.member_id,
                &leg,
                no_instrument,
            )
            .await?;
        }
    }
    Ok(())
}

/// Removes a leg this service wrote; a linked transaction is only unlinked.
async fn delete_cash_leg(
    conn: &mut PgConnection,
    ctx: &WriteContext<'_>,
    terms: &PeerLoanTerms,
    repayment: &mut PeerLoanRepayment,
) -> AppResult<()> {
    let Some(cash_id) = repayment.cash_transaction_id else {
        return Ok(());
    };
    if repayment.cash_leg_owned
        && let Some(linked_id) = terms.linked_account_id
        && let Some(linked) = accounts::find_by_id(&mut *conn, linked_id).await?
        && linked.is_manual
    {
        match delete_transaction(conn, ctx.entry(), linked.id, cash_id, ctx.member_id).await {
            // The member may have deleted the cash row themselves; the loan stays editable.
            Ok(()) | Err(AppError::NotFound(_)) => {}
            Err(e) => return Err(e),
        }
    }
    repayment.cash_transaction_id = None;
    Ok(())
}

// --- Helpers ---

/// Writes today's balance, then repairs every persisted snapshot from the event's date on: a
/// backdated repayment changes every later balance. Reaching zero closes the loan.
async fn refresh_balance(
    conn: &mut PgConnection,
    ctx: &WriteContext<'_>,
    account: &mut Account,
    terms: &mut PeerLoanTerms,
    event_date: Option<NaiveDate>,
) -> AppResult<()> {
    let today = ctx.today;
    let now = ctx.state.clock.now();
    let repayments = repo::repayments(&mut *conn, account.id).await?;
    let remaining = peer_loans::compute(terms, &repayments, today).principal_remaining;
    if remaining.is_zero() && terms.closed_at.is_none() {
        terms.closed_at = Some(repayments.last().map_or(today, |r| r.paid_on));
        repo::save_terms(conn, terms, now).await?;
    }
    account.current_balance = remaining;
    accounts::update(&mut *conn, account, now).await?;

    let snapshot_ctx = SnapshotContext {
        prices: &*ctx.state.ledger.prices,
        today,
        now,
    };
    let affected_from = event_date.unwrap_or(today);
    let mut event_date_persisted = false;
    for snapshot in snapshots::find_by_account(&mut *conn, account.id).await? {
        if snapshot.date < affected_from {
            continue;
        }
        if snapshot.date == affected_from {
            event_date_persisted = true;
        }
        let balance = balance_at(terms, &repayments, snapshot.date);
        valuation::upsert_snapshot(conn, &snapshot_ctx, account, balance, snapshot.date).await?;
    }
    if !event_date_persisted {
        let balance = balance_at(terms, &repayments, affected_from);
        valuation::upsert_snapshot(conn, &snapshot_ctx, account, balance, affected_from).await?;
    }
    Ok(())
}

/// The receivable on a date: later repayments are not part of it, and the loan does not exist
/// before the money was lent.
fn balance_at(terms: &PeerLoanTerms, repayments: &[PeerLoanRepayment], date: NaiveDate) -> Decimal {
    if date < terms.lent_at {
        return Decimal::ZERO;
    }
    peer_loans::compute(terms, &up_to(repayments, date), date).principal_remaining
}

fn up_to(rows: &[PeerLoanRepayment], date: NaiveDate) -> Vec<PeerLoanRepayment> {
    rows.iter().filter(|r| r.paid_on <= date).cloned().collect()
}

/// The ledger holds completed payments; a forecast belongs in the plan.
fn require_completed_repayment_date(paid_on: NaiveDate, today: NaiveDate) -> AppResult<()> {
    if paid_on > today {
        return Err(bad("A repayment date cannot be in the future"));
    }
    if paid_on < MIN_DATE {
        return Err(bad("A repayment date is too far in the past"));
    }
    Ok(())
}

fn require_type(account: &Account) -> AppResult<()> {
    if account.account_type != AccountType::PeerLoan {
        return Err(bad("Account is not a peer loan"));
    }
    Ok(())
}

fn repayment_not_found() -> AppError {
    AppError::NotFound("Repayment not found".to_owned())
}

/// The terms, locked until the transaction ends: the cap check reads the whole ledger, so two
/// writers must not validate against the same state.
async fn lock_configured_terms(
    conn: &mut PgConnection,
    account_id: i64,
) -> AppResult<PeerLoanTerms> {
    repo::find_terms_for_update(conn, account_id)
        .await?
        .ok_or_else(|| {
            AppError::NotFound("Peer loan terms are not set for this account".to_owned())
        })
}

fn require_open(terms: &PeerLoanTerms) -> AppResult<()> {
    if terms.closed_at.is_some() {
        return Err(bad(
            "This loan is closed. Delete its write-off to reopen it before recording a repayment.",
        ));
    }
    Ok(())
}

fn validate_plan(req: &PeerLoanTermsRequest) -> AppResult<()> {
    let missing = req.planned_monthly_amount.is_none() && req.planned_months.is_none();
    let complete = req.planned_monthly_amount.is_some() && req.planned_months.is_some();
    if !missing && !complete {
        return Err(bad("A plan needs both a monthly amount and a month count"));
    }
    if req.planned_months.is_some_and(|m| m > 1200) {
        return Err(bad("A plan cannot exceed 1200 months"));
    }
    Ok(())
}

/// The ledger is in EUR: a cash account in another currency would link an unconverted amount.
fn require_eur(account: &Account, action: &str) -> AppResult<()> {
    if !account.currency.eq_ignore_ascii_case("EUR") {
        return Err(bad(format!(
            "Only a EUR cash account can {action} (this one is in {})",
            account.currency
        )));
    }
    Ok(())
}

/// The transaction and its account, when it belongs to one of the member's own accounts.
async fn owned_transaction(
    conn: &mut PgConnection,
    transaction_id: i64,
    member_id: i64,
) -> AppResult<(crate::ledger::model::Transaction, Account)> {
    let not_found = || AppError::NotFound("Transaction not found".to_owned());
    let transaction = transactions::find_by_id_and_member(&mut *conn, transaction_id, member_id)
        .await?
        .ok_or_else(not_found)?;
    let account = accounts::find_by_id(&mut *conn, transaction.account_id)
        .await?
        .ok_or_else(not_found)?;
    Ok((transaction, account))
}

/// An explicit interest payment pays interest that has accrued: more would hide real income.
async fn validate_interest_repayment(
    conn: &mut PgConnection,
    terms: &PeerLoanTerms,
    kind: PeerLoanRepaymentKind,
    amount: Decimal,
    paid_on: NaiveDate,
    excluded: Option<i64>,
) -> AppResult<()> {
    if kind != PeerLoanRepaymentKind::Interest {
        return Ok(());
    }
    let ledger = up_to(&ledger_excluding(conn, terms, excluded).await?, paid_on);
    let accrued = peer_loans::compute(terms, &ledger, paid_on).interest_accrued;
    if amount > accrued {
        return Err(bad(format!(
            "An interest repayment cannot exceed the accrued interest of {} EUR",
            set_scale(accrued, 2, HALF_UP)
        )));
    }
    Ok(())
}

/// Refuses a repayment that leaves more cash unabsorbed by the ledger than there already was,
/// replaying the whole ledger: a backdated payment can push later rows over the cap.
async fn validate_repayment_amount(
    conn: &mut PgConnection,
    terms: &PeerLoanTerms,
    amount: Decimal,
    paid_on: NaiveDate,
    excluded: Option<i64>,
) -> AppResult<()> {
    let others = ledger_excluding(conn, terms, excluded).await?;
    // The candidate sorts where the saved row will: an edited row keeps its id, a new one last.
    let candidate = PeerLoanRepayment {
        id: excluded.unwrap_or(i64::MAX),
        account_id: terms.account_id,
        paid_on,
        amount,
        kind: PeerLoanRepaymentKind::Principal,
        note: None,
        cash_transaction_id: None,
        cash_leg_owned: true,
    };
    let mut with_candidate = others.clone();
    with_candidate.push(candidate);
    if unabsorbed(terms, &with_candidate, paid_on) > unabsorbed(terms, &others, paid_on) {
        let interest =
            peer_loans::compute(terms, &up_to(&others, paid_on), paid_on).interest_accrued;
        let remaining = peer_loans::compute(terms, &others, paid_on).principal_remaining;
        return Err(bad(format!(
            "A repayment cannot exceed {} EUR (accrued interest plus remaining capital)",
            set_scale(interest + remaining, 2, HALF_UP)
        )));
    }
    Ok(())
}

/// Cash recorded as paid that the ledger could not apply to interest or capital.
fn unabsorbed(terms: &PeerLoanTerms, rows: &[PeerLoanRepayment], as_of: NaiveDate) -> Decimal {
    let paid: Decimal = rows
        .iter()
        .filter(|r| r.kind != PeerLoanRepaymentKind::WriteOff)
        .map(|r| r.amount)
        .sum();
    let absorbed: Decimal = peer_loans::compute(terms, rows, as_of)
        .splits
        .iter()
        .map(|s| s.interest + s.capital)
        .sum();
    paid - absorbed
}

async fn ledger_excluding(
    conn: &mut PgConnection,
    terms: &PeerLoanTerms,
    excluded: Option<i64>,
) -> AppResult<Vec<PeerLoanRepayment>> {
    Ok(repo::repayments(conn, terms.account_id)
        .await?
        .into_iter()
        .filter(|r| Some(r.id) != excluded)
        .collect())
}

async fn counterparty(
    conn: &mut PgConnection,
    member_id: i64,
    name: &str,
    now: chrono::DateTime<chrono::Utc>,
) -> AppResult<i64> {
    let normalized = java_trim(name);
    if normalized.is_empty() {
        return Err(bad("The account name is required as the borrower"));
    }
    Ok(repo::counterparty_id(conn, member_id, normalized, now).await?)
}

fn beneficiary_name(
    requested: Option<&str>,
    transaction: &crate::ledger::model::Transaction,
) -> String {
    fn pick(value: Option<&str>) -> Option<&str> {
        value.filter(|v| !java_is_blank(v)).map(java_trim)
    }
    let name = pick(requested)
        .or_else(|| pick(transaction.merchant_label.as_deref()))
        .or_else(|| pick(Some(transaction.description.as_str())))
        .unwrap_or("Prêt");
    let units: Vec<u16> = name.encode_utf16().collect();
    if units.len() > MAX_ACCOUNT_NAME {
        String::from_utf16_lossy(&units[..MAX_ACCOUNT_NAME])
    } else {
        name.to_owned()
    }
}

/// String.CASE_INSENSITIVE_ORDER: per character, upper-cased then lower-cased.
fn case_insensitive(a: &str, b: &str) -> Ordering {
    let fold = |c: char| {
        c.to_uppercase()
            .flat_map(char::to_lowercase)
            .collect::<String>()
    };
    let left = a.chars().map(fold);
    let right = b.chars().map(fold);
    left.cmp(right)
}

fn unconfigured(account: &Account) -> PeerLoanDetailResponse {
    PeerLoanDetailResponse {
        account_id: account.id,
        account_name: account.name.clone(),
        configured: false,
        principal: crate::accounts::scale::column(account.current_balance, 8),
        lent_at: Some(account.created_at.date_naive()),
        expected_back: None,
        annual_rate: None,
        rate_period: PeerLoanRatePeriod::Annual,
        planned_monthly_amount: None,
        planned_months: None,
        linked_account_id: None,
        linked_account_name: None,
        note: None,
        closed_at: None,
        principal_remaining: Decimal::ZERO,
        interest_accrued: Decimal::ZERO,
        interest_received: Decimal::ZERO,
        principal_repaid: Decimal::ZERO,
        total_repaid: Decimal::ZERO,
        written_off: Decimal::ZERO,
        repaid_pct: Decimal::ZERO,
        scheduled_remaining: None,
        installments_behind: None,
        planned_interest: None,
        repayments: Vec::new(),
        curve: Vec::new(),
    }
}

/// The detail view. fresh holds the rows this request wrote, as Hibernate hands back the
/// managed instance (amount as sent) rather than the re-read column.
async fn build(
    conn: &mut PgConnection,
    account: &Account,
    terms: &PeerLoanTerms,
    as_of: NaiveDate,
    fresh: &[PeerLoanRepayment],
) -> AppResult<PeerLoanDetailResponse> {
    let mut repayments = repo::repayments(&mut *conn, account.id).await?;
    for row in &mut repayments {
        if let Some(written) = fresh.iter().find(|f| f.id == row.id) {
            *row = written.clone();
        }
    }
    let computed = peer_loans::compute(terms, &repayments, as_of);
    let plan =
        peer_loans::plan(terms, Some(computed.principal_remaining), as_of).map_err(calc_error)?;
    let splits: HashMap<i64, _> = computed
        .splits
        .iter()
        .map(|s| (s.repayment_id, s))
        .collect();
    let linked = match terms.linked_account_id {
        Some(id) => accounts::find_by_id(&mut *conn, id).await?,
        None => None,
    };
    Ok(PeerLoanDetailResponse {
        account_id: account.id,
        account_name: account.name.clone(),
        configured: true,
        principal: terms.principal,
        lent_at: Some(terms.lent_at),
        expected_back: terms.expected_back,
        annual_rate: terms.annual_rate,
        rate_period: terms.rate_period,
        planned_monthly_amount: terms.planned_monthly_amount,
        planned_months: terms.planned_months,
        linked_account_id: terms.linked_account_id,
        linked_account_name: linked.map(|a| a.name),
        note: terms.note.clone(),
        closed_at: terms.closed_at,
        principal_remaining: computed.principal_remaining,
        interest_accrued: computed.interest_accrued,
        interest_received: computed.interest_received,
        principal_repaid: computed.principal_paid,
        total_repaid: set_scale(
            computed.principal_paid + computed.interest_received,
            2,
            HALF_UP,
        ),
        written_off: computed.written_off,
        repaid_pct: peer_loans::repaid_pct(Some(terms.principal), Some(computed.principal_paid)),
        scheduled_remaining: plan.as_ref().map(|p| p.scheduled_remaining),
        installments_behind: plan.as_ref().and_then(|p| p.installments_behind),
        planned_interest: plan.as_ref().map(|p| p.planned_interest),
        repayments: repayments
            .iter()
            .map(|r| PeerLoanRepaymentResponse::from(r, splits.get(&r.id).copied()))
            .collect(),
        curve: build_curve(terms, &repayments, as_of)?,
    })
}

/// Monthly points from the lending date to today, or to the planned end when later.
fn build_curve(
    terms: &PeerLoanTerms,
    repayments: &[PeerLoanRepayment],
    as_of: NaiveDate,
) -> AppResult<Vec<CurvePoint>> {
    let start = terms.lent_at;
    let mut end = as_of;
    if let Some(months) = terms.planned_months.filter(|m| *m > 0)
        && let Some(planned_end) = start.checked_add_months(Months::new(months.unsigned_abs()))
        && planned_end > end
    {
        end = planned_end;
    }
    let mut points = Vec::new();
    let mut month = start.with_day(1).unwrap_or(start);
    let last_month = end.with_day(1).unwrap_or(end);
    while month <= last_month {
        points.push(curve_point(terms, repayments, month.max(start))?);
        let Some(next) = month.checked_add_months(Months::new(1)) else {
            break;
        };
        month = next;
    }
    if points.last().is_none_or(|p: &CurvePoint| p.date < as_of) {
        points.push(curve_point(terms, repayments, as_of)?);
    }
    Ok(points)
}

fn curve_point(
    terms: &PeerLoanTerms,
    repayments: &[PeerLoanRepayment],
    date: NaiveDate,
) -> AppResult<CurvePoint> {
    let computed = peer_loans::compute(terms, &up_to(repayments, date), date);
    let plan =
        peer_loans::plan(terms, Some(computed.principal_remaining), date).map_err(calc_error)?;
    Ok(CurvePoint {
        date,
        actual: computed.principal_remaining,
        scheduled: plan.map(|p| p.scheduled_remaining),
    })
}
