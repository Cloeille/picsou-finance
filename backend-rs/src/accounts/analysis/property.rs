//! The slice of RealEstateSummaryService.summarizeVisible the pyramid reads: what is still owed
//! on the visible properties and SCPI shares, and the physical loan-to-value.

use chrono::NaiveDate;
use rust_decimal::Decimal;
use sqlx::PgConnection;

use super::math::scale2;
use crate::ledger::access::{self, weigh};
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::model::{Account, AccountType};
use crate::ledger::prices::PriceRead;
use crate::ledger::{accounts, liabilities, loans, valuation};

pub struct PropertyDebt {
    /// outstandingDebt, scaled to 2 places.
    pub outstanding: Decimal,
    /// paperDebt, scaled to 2 places.
    pub paper: Decimal,
    /// loanToValue in percent, 2 places; None without a physical property.
    pub loan_to_value: Option<Decimal>,
}

pub async fn visible_property_debt(
    conn: &mut PgConnection,
    prices: &dyn PriceRead,
    member_id: i64,
    today: NaiveDate,
) -> sqlx::Result<PropertyDebt> {
    let readable: Vec<Account> = access::readable_accounts(&mut *conn, member_id)
        .await?
        .into_iter()
        .filter(|a| !a.hidden)
        .collect();
    let shares = access::shares_for(&mut *conn, &readable, member_id).await?;
    let mut gross_total = Decimal::ZERO;
    let mut debt_total = Decimal::ZERO;
    let mut paper_debt = Decimal::ZERO;
    for account in &readable {
        let share = shares.get(&account.id).copied().unwrap_or_default();
        match account.account_type {
            AccountType::Scpi if share > Decimal::ZERO => {
                paper_debt += loans_for(conn, account, member_id, today).await?;
            }
            AccountType::RealEstate if share > Decimal::ZERO => {
                let live = valuation::live_balance_eur(&mut *conn, prices, account, today).await?;
                gross_total += weigh(live, share);
                debt_total += loans_for(conn, account, member_id, today).await?;
            }
            _ => {}
        }
    }
    let loan_to_value = (gross_total > Decimal::ZERO)
        .then(|| divide(debt_total * Decimal::ONE_HUNDRED, gross_total, 2, HALF_UP));
    Ok(PropertyDebt {
        outstanding: scale2(debt_total),
        paper: scale2(paper_debt),
        loan_to_value,
    })
}

/// The visible loans pointing at the property, each weighted by the member's share of the loan.
async fn loans_for(
    conn: &mut PgConnection,
    property: &Account,
    member_id: i64,
    today: NaiveDate,
) -> sqlx::Result<Decimal> {
    let debts = liabilities::debts_linked_to(&mut *conn, property.id).await?;
    if debts.is_empty() {
        return Ok(Decimal::ZERO);
    }
    let mut loan_accounts = Vec::new();
    for debt in &debts {
        loan_accounts.push(accounts::find_by_id(&mut *conn, debt.account_id).await?);
    }
    let present: Vec<Account> = loan_accounts.iter().flatten().cloned().collect();
    let loan_shares = access::shares_for(&mut *conn, &present, member_id).await?;
    let mut total = Decimal::ZERO;
    for (debt, loan) in debts.iter().zip(&loan_accounts) {
        let Some(loan) = loan.as_ref().filter(|l| !l.hidden) else {
            continue;
        };
        let share = loan_shares.get(&loan.id).copied().unwrap_or_default();
        if share <= Decimal::ZERO {
            continue;
        }
        let remaining = if loans::has_schedule(debt) {
            loans::compute_remaining_balance(debt, today)
        } else {
            loan.current_balance
        };
        total += weigh(remaining, share);
    }
    Ok(total)
}
