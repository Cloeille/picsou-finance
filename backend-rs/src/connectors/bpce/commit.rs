//! The write of a validated snapshot, in the sync's one transaction: accounts, their daily
//! snapshot and transactions, the debts of loans, and the strict tagging of card settlements and
//! loan rows on the current accounts they hit.

use std::collections::{BTreeMap, HashMap};

use chrono::{DateTime, Datelike, Duration, Months, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::Bank;
use super::per;
use super::prepare::{PreparedAccount, PreparedSnapshot, PreparedTransaction};
use crate::AppState;
use crate::connectors::sidecar::JobResult;
use crate::error::AppError;
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::categorize;
use crate::ledger::liabilities;
use crate::ledger::members;
use crate::ledger::model::{
    Account, AccountType, CardNature, Category, CategoryKind, NewTransaction, Transaction,
};
use crate::ledger::snapshots;
use crate::ledger::transactions;
use crate::ledger::valuation::{self, SnapshotContext};

/// Slug of the seeded TRANSFER category (money moving between the member's own accounts).
const TRANSFER_SLUG: &str = "virement-interne";
/// Slug of the default EXPENSE category of loan installments, created on demand.
const CREDIT_SLUG: &str = "credit";
/// The bank's transactionTypeCode on the current-account debit that settles a card.
const SETTLEMENT_TYPE_CODE: &str = "04";
/// Word the bank puts in the label of the credit that pays a personal loan out.
const DISBURSEMENT_LABEL: &str = "FINANCEMENT";
/// How far a loan row may sit from the date it is expected on.
const LOAN_DATE_TOLERANCE_DAYS: i64 = 5;

type Rows = HashMap<String, Transaction>;

struct Write<'a> {
    state: &'a AppState,
    bank: &'a Bank,
    member_id: i64,
    snapshot: &'a PreparedSnapshot,
    synced_at: DateTime<Utc>,
    saved: HashMap<String, Account>,
    rows: HashMap<String, Rows>,
}

#[allow(clippy::too_many_arguments)]
pub async fn write(
    state: &AppState,
    conn: &mut PgConnection,
    bank: &Bank,
    member_id: i64,
    snapshot: &PreparedSnapshot,
    provider: &str,
    logo_url: Option<&str>,
    synced_at: DateTime<Utc>,
) -> JobResult<()> {
    if members::find_by_id(&mut *conn, member_id).await?.is_none() {
        return Err(AppError::NotFound("Family member not found".to_owned()).into());
    }
    let mut write = Write {
        state,
        bank,
        member_id,
        snapshot,
        synced_at,
        saved: HashMap::new(),
        rows: HashMap::new(),
    };
    for data in &snapshot.accounts {
        write.upsert_account(conn, data, provider, logo_url).await?;
    }
    write.upsert_loan_debts(conn).await?;
    write.tag_card_settlements(conn).await?;
    write.tag_loan_rows(conn).await?;
    Ok(())
}

fn color_for(account_type: AccountType) -> &'static str {
    match account_type {
        // Same colour for every savings book, as the Bourso connector does.
        AccountType::Savings
        | AccountType::LivretA
        | AccountType::LivretJeune
        | AccountType::Lep
        | AccountType::Ldds
        | AccountType::Pel => "#f59e0b",
        AccountType::Per | AccountType::AssuranceVie => "#8b5cf6",
        AccountType::Loan => "#64748b",
        AccountType::CreditCard => "#2563eb",
        _ => "#dc2626",
    }
}

fn same_to_the_cent(amount: Decimal, expected: Decimal) -> bool {
    (amount - expected).abs() <= Decimal::new(1, 2)
}

/// One month before the first due date, on the loan's nominal due day: the schedule dates
/// installment i at startDate + i months. A first due date on the last day of its month with a
/// later last-due day was clamped, so the anchor takes the last due date's day.
fn schedule_anchor(first_due: NaiveDate, last_due: NaiveDate) -> NaiveDate {
    let anchor = first_due - Months::new(1);
    let first_is_month_end = (first_due + Duration::days(1)).month0() != first_due.month0();
    if first_is_month_end && last_due.day() > first_due.day() {
        let length = days_in_month(anchor);
        anchor
            .with_day(last_due.day().min(length))
            .unwrap_or(anchor)
    } else {
        anchor
    }
}

fn days_in_month(date: NaiveDate) -> u32 {
    let first = date.with_day(1).expect("first of month");
    let next = first + Months::new(1);
    u32::try_from((next - first).num_days()).expect("month length")
}

/// Tags the single candidate when it has no category yet; false when nothing was tagged.
fn tag(
    candidates: &[&PreparedTransaction],
    stored: &mut Rows,
    category: Option<&Category>,
    changed: &mut Vec<String>,
) -> bool {
    let [only] = candidates else {
        return false;
    };
    let Some(row) = stored.get_mut(&only.external_id) else {
        return false;
    };
    if row.category_id.is_some() {
        return false;
    }
    let Some(target) = category else {
        return false;
    };
    row.category_id = Some(target.id);
    changed.push(only.external_id.clone());
    true
}

impl Write<'_> {
    /// The account's upserted rows, or nothing when the user deleted it. The provider label is
    /// rewritten on every sync; a logo is set on a new account and only filled on one that has
    /// none.
    async fn upsert_account(
        &mut self,
        conn: &mut PgConnection,
        data: &PreparedAccount,
        provider: &str,
        logo_url: Option<&str>,
    ) -> JobResult<()> {
        let member_id = self.member_id;
        let existing =
            accounts::find_by_external_id_and_member(&mut *conn, &data.external_id, member_id)
                .await?;
        let mut account = match existing {
            Some(account) => account,
            None => {
                if accounts::exists_soft_deleted_by_external_id(
                    &mut *conn,
                    &data.external_id,
                    member_id,
                )
                .await?
                {
                    tracing::info!(
                        "{} skipped a soft-deleted account (member={member_id})",
                        self.bank.provider()
                    );
                    return Ok(());
                }
                let mut draft = NewAccount::new(member_id, data.name.clone(), data.account_type);
                draft.provider = Some(provider.to_owned());
                draft.is_manual = false;
                draft.color = color_for(data.account_type).to_owned();
                draft.external_account_id = Some(data.external_id.clone());
                draft.logo_url = logo_url.map(str::to_owned);
                draft.current_balance = data.balance;
                accounts::insert(&mut *conn, &draft, self.synced_at).await?
            }
        };
        account.name.clone_from(&data.name);
        account.account_type = data.account_type;
        account.provider = Some(provider.to_owned());
        if account.logo_url.is_none() {
            account.logo_url = logo_url.map(str::to_owned);
        }
        account.currency = "EUR".to_owned();
        account.is_manual = false;
        account.current_balance = data.balance;
        if let Some(per) = &data.per {
            // The euro-fund sleeve is stored, not quoted; unit lines are revalued from prices.
            account.cash_balance = Some(per.euro_fund_balance);
        }
        account.last_synced_at = Some(self.synced_at);
        if let Some(nature) = data.card_nature {
            // What is still to be debited, and when. The card never gets a parent account: the
            // dashboard skips any account with one, which would drop the card from net worth.
            account.card_nature = Some(nature);
            account.payment_due_amount = Some(data.balance.abs());
            account.payment_due_date = data.next_due_date;
        }
        // The IBAN is deliberately not stored: a stored IBAN would let the Enable Banking sync
        // adopt this account by itself.
        let account = accounts::update(&mut *conn, &account, self.synced_at).await?;

        if let Some(per) = &data.per {
            per::apply(&mut *conn, account.id, per, self.synced_at).await?;
            // The bank NAV is an observation, not an acquisition cost: paired with itself, the
            // snapshot takes no baseline from today's market quote.
            snapshots::upsert(
                &mut *conn,
                account.id,
                data.balance,
                data.balance,
                per.valuation_date,
                self.synced_at,
            )
            .await?;
        } else {
            let ctx = SnapshotContext {
                prices: &*self.state.ledger.prices,
                today: self.state.clock.today(),
                now: self.synced_at,
            };
            valuation::upsert_snapshot(&mut *conn, &ctx, &account, data.balance, ctx.today).await?;
        }
        let rows = upsert_transactions(conn, &account, &data.transactions, self.synced_at).await?;
        self.rows.insert(data.external_id.clone(), rows);
        self.saved.insert(data.external_id.clone(), account);
        Ok(())
    }

    /// Creates or updates the debt of each loan the bank described, writing only the fields the
    /// bank sends: file fees, or anything else the user typed, stay.
    async fn upsert_loan_debts(&self, conn: &mut PgConnection) -> JobResult<()> {
        for data in &self.snapshot.accounts {
            let (Some(loan), Some(account)) = (&data.loan, self.saved.get(&data.external_id))
            else {
                continue;
            };
            // A home loan has no debit IBAN: the user's own link, often the financed property,
            // survives a resync.
            let linked = match crate::ledger::sync_values::clean(loan.linked_iban.as_deref()) {
                Some(iban) => Some(self.debited_account(conn, &iban).await?),
                None => None,
            };
            let start = schedule_anchor(loan.first_due_date, loan.last_due_date);
            let lender = self.bank.provider();
            match liabilities::debt_of_account(&mut *conn, account.id).await? {
                Some(debt) => {
                    sqlx::query(
                        "UPDATE debt SET linked_account_id = $2, borrowed_amount = $3, interest_rate = $4, \
                         monthly_payment = $5, insurance_monthly = $6, lender_name = $7, start_date = $8, \
                         end_date = $9 WHERE id = $1",
                    )
                    .bind(debt.id)
                    .bind(linked.unwrap_or(debt.linked_account_id))
                    .bind(loan.borrowed_amount)
                    .bind(loan.interest_rate)
                    .bind(loan.monthly_payment)
                    .bind(loan.insurance_monthly)
                    .bind(lender)
                    .bind(start)
                    .bind(loan.last_due_date)
                    .execute(&mut *conn)
                    .await?;
                }
                None => {
                    sqlx::query(
                        "INSERT INTO debt (member_id, account_id, linked_account_id, borrowed_amount, interest_rate, \
                         monthly_payment, insurance_monthly, lender_name, start_date, end_date, created_at, updated_at) \
                         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $11)",
                    )
                    .bind(self.member_id)
                    .bind(account.id)
                    .bind(linked.flatten())
                    .bind(loan.borrowed_amount)
                    .bind(loan.interest_rate)
                    .bind(loan.monthly_payment)
                    .bind(loan.insurance_monthly)
                    .bind(lender)
                    .bind(start)
                    .bind(loan.last_due_date)
                    .bind(self.synced_at)
                    .execute(&mut *conn)
                    .await?;
                }
            }
        }
        Ok(())
    }

    /// The member's EUR account with that IBAN: one of this import (its IBAN is never stored),
    /// else one already stored, else none. Never a guess past an ambiguity in the import.
    async fn debited_account(&self, conn: &mut PgConnection, iban: &str) -> JobResult<Option<i64>> {
        let in_import: Vec<&PreparedAccount> = self
            .snapshot
            .accounts
            .iter()
            .filter(|a| a.iban.as_deref() == Some(iban))
            .collect();
        match in_import.as_slice() {
            [] => Ok(accounts::find_by_iban_currency_and_member(
                &mut *conn,
                iban,
                "EUR",
                self.member_id,
            )
            .await?
            .map(|a| a.id)),
            [only] if !only.iban_ambiguous => Ok(self.saved.get(&only.external_id).map(|a| a.id)),
            _ => Ok(None),
        }
    }

    async fn save_changed(
        &self,
        conn: &mut PgConnection,
        account_external_id: &str,
        changed: &[String],
    ) -> JobResult<()> {
        if let Some(rows) = self.rows.get(account_external_id) {
            for id in changed {
                if let Some(row) = rows.get(id) {
                    transactions::update(&mut *conn, row).await?;
                }
            }
        }
        Ok(())
    }

    /// The seeded TRANSFER category, or None when the member has none.
    async fn transfer_category(&self) -> JobResult<Option<Category>> {
        let found = categorize::categories_by_slug(&self.state.db, self.member_id, self.synced_at)
            .await?
            .remove(TRANSFER_SLUG);
        Ok(found.filter(|c| c.kind == CategoryKind::Transfer))
    }

    /// A deferred-debit card is settled on its current account by one debit per due date: found
    /// without doubt, that debit is an internal transfer, since the card operations already count
    /// as expenses. Only a due date already reached, a debit that very day for exactly the
    /// group's sum, a settlement type code (or none), one candidate and no category yet.
    async fn tag_card_settlements(&mut self, conn: &mut PgConnection) -> JobResult<()> {
        let today = self.state.clock.today();
        let mut transfer: Option<Option<Category>> = None;
        let (mut tagged, mut skipped) = (0, 0);
        let snapshot = self.snapshot;
        for card in &snapshot.accounts {
            let Some(parent) = card
                .parent_external_id
                .as_deref()
                .and_then(|p| snapshot.accounts.iter().find(|a| a.external_id == p))
            else {
                continue;
            };
            if card.card_nature != Some(CardNature::DeferredDebit)
                || !self.rows.contains_key(&card.external_id)
                || !self.rows.contains_key(&parent.external_id)
            {
                continue;
            }
            let mut groups: BTreeMap<NaiveDate, Decimal> = BTreeMap::new();
            for tx in &card.transactions {
                if let Some(due) = tx.due_date.filter(|d| *d <= today) {
                    *groups.entry(due).or_default() += tx.amount;
                }
            }
            if groups.is_empty() {
                continue;
            }
            if transfer.is_none() {
                let found = self.transfer_category().await?;
                if found.is_none() {
                    tracing::warn!(
                        "{} settlement tagging skipped: no transfer category (member={})",
                        self.bank.provider(),
                        self.member_id
                    );
                    return Ok(());
                }
                transfer = Some(found);
            }
            let category = transfer.clone().flatten();
            let mut changed = Vec::new();
            let stored = self.rows.get_mut(&parent.external_id).expect("parent rows");
            for (due, sum) in &groups {
                let candidates: Vec<&PreparedTransaction> = parent
                    .transactions
                    .iter()
                    .filter(|tx| tx.date == *due && tx.amount == *sum)
                    .filter(|tx| {
                        tx.type_code
                            .as_deref()
                            .is_none_or(|c| c == SETTLEMENT_TYPE_CODE)
                    })
                    .collect();
                if tag(&candidates, stored, category.as_ref(), &mut changed) {
                    tagged += 1;
                } else {
                    skipped += 1;
                }
            }
            self.save_changed(conn, &parent.external_id, &changed)
                .await?;
        }
        tracing::info!(
            "{} card settlements: tagged={tagged}, left alone={skipped} (member={})",
            self.bank.provider(),
            self.member_id
        );
        Ok(())
    }

    /// On the current account a loan is debited from, the disbursement is money moving in from
    /// the member's own loan (an internal transfer) and each installment a loan expense. Same
    /// guards as the card settlements.
    async fn tag_loan_rows(&mut self, conn: &mut PgConnection) -> JobResult<()> {
        let mut transfer: Option<Option<Category>> = None;
        let mut credit: Option<Option<Category>> = None;
        let (mut tagged, mut skipped) = (0, 0);
        let snapshot = self.snapshot;
        for data in &snapshot.accounts {
            let Some(loan) = &data.loan else {
                continue;
            };
            let Some(debited) = debited_in_import(snapshot, loan.linked_iban.as_deref()) else {
                continue;
            };
            if !self.rows.contains_key(&debited.external_id) {
                continue;
            }
            let mut changed = Vec::new();
            if let Some(signature) = loan.signature_date {
                let from = signature - Duration::days(LOAN_DATE_TOLERANCE_DAYS);
                let credits: Vec<&PreparedTransaction> = debited
                    .transactions
                    .iter()
                    .filter(|tx| tx.amount > Decimal::ZERO)
                    .filter(|tx| same_to_the_cent(tx.amount, loan.borrowed_amount))
                    .filter(|tx| tx.label.to_uppercase().contains(DISBURSEMENT_LABEL))
                    .filter(|tx| tx.date >= from && tx.date <= loan.first_due_date)
                    .collect();
                let category = self
                    .loan_category(&mut transfer, &credits, &debited.external_id, true, conn)
                    .await?;
                let stored = self
                    .rows
                    .get_mut(&debited.external_id)
                    .expect("debited rows");
                if tag(&credits, stored, category.as_ref(), &mut changed) {
                    tagged += 1;
                } else {
                    skipped += 1;
                }
            }
            // Always from the first due date: adding a month to the previous one would drift a
            // 31st to the 28th.
            let mut k = 0;
            while let Some(due) = loan.first_due_date.checked_add_months(Months::new(k)) {
                if due > loan.last_due_date {
                    break;
                }
                k += 1;
                let from = due - Duration::days(LOAN_DATE_TOLERANCE_DAYS);
                let to = due + Duration::days(LOAN_DATE_TOLERANCE_DAYS);
                let debits: Vec<&PreparedTransaction> = debited
                    .transactions
                    .iter()
                    .filter(|tx| tx.amount < Decimal::ZERO)
                    .filter(|tx| same_to_the_cent(-tx.amount, loan.monthly_payment))
                    .filter(|tx| tx.date >= from && tx.date <= to)
                    .collect();
                if debits.is_empty() {
                    continue;
                }
                let category = self
                    .loan_category(&mut credit, &debits, &debited.external_id, false, conn)
                    .await?;
                let stored = self
                    .rows
                    .get_mut(&debited.external_id)
                    .expect("debited rows");
                if tag(&debits, stored, category.as_ref(), &mut changed) {
                    tagged += 1;
                } else {
                    skipped += 1;
                }
            }
            self.save_changed(conn, &debited.external_id, &changed)
                .await?;
        }
        tracing::info!(
            "{} loan rows: tagged={tagged}, left alone={skipped} (member={})",
            self.bank.provider(),
            self.member_id
        );
        Ok(())
    }

    /// The category a loan row gets, loaded at most once and only when a row actually needs it:
    /// exactly one candidate without a category yet.
    async fn loan_category(
        &self,
        cache: &mut Option<Option<Category>>,
        candidates: &[&PreparedTransaction],
        account_external_id: &str,
        disbursement: bool,
        conn: &mut PgConnection,
    ) -> JobResult<Option<Category>> {
        let needed = match candidates {
            [only] => self
                .rows
                .get(account_external_id)
                .and_then(|rows| rows.get(&only.external_id))
                .is_some_and(|row| row.category_id.is_none()),
            _ => false,
        };
        if !needed {
            return Ok(None);
        }
        if let Some(loaded) = cache {
            return Ok(loaded.clone());
        }
        let provider = self.bank.provider();
        let member_id = self.member_id;
        let loaded = if disbursement {
            let found = self.transfer_category().await?;
            if found.is_none() {
                tracing::warn!(
                    "{provider} loan disbursement left alone: no transfer category (member={member_id})"
                );
            }
            found
        } else {
            // Seeded in a transaction of its own first, as Java's REQUIRES_NEW: a seed inside
            // this one would hold rows another seed then waits on.
            categorize::ensure_seeded(&self.state.db, member_id, self.synced_at).await?;
            let found = categorize::find_or_create_default(
                &mut *conn,
                member_id,
                CREDIT_SLUG,
                self.synced_at,
            )
            .await?
            .filter(|c| c.kind == CategoryKind::Expense);
            if found.is_none() {
                tracing::info!(
                    "{provider} loan installments left alone: no credit category (member={member_id})"
                );
            }
            found
        };
        *cache = Some(loaded.clone());
        Ok(loaded)
    }
}

/// Upsert by (account, external id); rows are never deleted, manual rows never touched.
async fn upsert_transactions(
    conn: &mut PgConnection,
    account: &Account,
    prepared: &[PreparedTransaction],
    now: DateTime<Utc>,
) -> JobResult<Rows> {
    let mut upserted = Rows::new();
    if prepared.is_empty() {
        return Ok(upserted);
    }
    let mut stored: Rows = transactions::find_synced_by_account(&mut *conn, account.id, None)
        .await?
        .into_iter()
        .filter_map(|row| row.external_id.clone().map(|id| (id, row)))
        .collect();
    for tx in prepared {
        let row = match stored.remove(&tx.external_id) {
            Some(mut row) => {
                row.date = tx.date;
                row.description.clone_from(&tx.label);
                row.amount = tx.amount;
                transactions::update(&mut *conn, &row).await?
            }
            None => {
                let mut draft =
                    NewTransaction::new(account.id, tx.date, tx.label.clone(), tx.amount);
                draft.external_id = Some(tx.external_id.clone());
                draft.native_currency = "EUR".to_owned();
                transactions::insert(&mut *conn, &draft, now).await?
            }
        };
        upserted.insert(tx.external_id.clone(), row);
    }
    Ok(upserted)
}

/// The loan's debited account in this import, only under an IBAN the bank shows once.
fn debited_in_import<'a>(
    snapshot: &'a PreparedSnapshot,
    iban: Option<&str>,
) -> Option<&'a PreparedAccount> {
    let iban = crate::ledger::sync_values::clean(iban)?;
    let in_import: Vec<&PreparedAccount> = snapshot
        .accounts
        .iter()
        .filter(|a| a.iban.as_deref() == Some(iban.as_str()))
        .collect();
    match in_import.as_slice() {
        [only] if !only.iban_ambiguous => Some(only),
        _ => None,
    }
}
