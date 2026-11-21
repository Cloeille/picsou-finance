//! sqlx decodes a numeric zero as Decimal::ZERO and drops its scale, where JDBC keeps the
//! column's (`0E-8` from numeric(20,8)). Rows read for a response get the column scale back.

use rust_decimal::Decimal;

use crate::ledger::model::{Account, BalanceSnapshot};

/// A zero read from a numeric(p, scale) column, with that scale.
pub fn column(d: Decimal, scale: u32) -> Decimal {
    if d.is_zero() && d.scale() < scale {
        Decimal::new(0, scale)
    } else {
        d
    }
}

pub fn column_opt(d: Option<Decimal>, scale: u32) -> Option<Decimal> {
    d.map(|d| column(d, scale))
}

/// BigDecimal.add: the scale of the sum is the larger one, even when an operand is zero (rust_decimal
/// returns the other operand as it is).
pub fn add(a: Decimal, b: Decimal) -> Decimal {
    widen(a + b, a.scale().max(b.scale()))
}

/// BigDecimal.subtract, scaled like add.
pub fn sub(a: Decimal, b: Decimal) -> Decimal {
    widen(a - b, a.scale().max(b.scale()))
}

fn widen(mut d: Decimal, scale: u32) -> Decimal {
    if d.scale() < scale {
        d.rescale(scale);
    }
    d
}

/// The numeric(20,8) columns of an account row.
pub fn fix_account(a: &mut Account) {
    a.current_balance = column(a.current_balance, 8);
    a.cash_balance = column_opt(a.cash_balance, 8);
    a.payment_due_amount = column_opt(a.payment_due_amount, 8);
    a.provider_invested_eur = column_opt(a.provider_invested_eur, 8);
    a.provider_paid_in_eur = column_opt(a.provider_paid_in_eur, 8);
    a.scheduled_payment_eur = column_opt(a.scheduled_payment_eur, 8);
}

pub fn fix_accounts(list: &mut [Account]) {
    list.iter_mut().for_each(fix_account);
}

pub fn fixed(mut a: Account) -> Account {
    fix_account(&mut a);
    a
}

/// The numeric(20,8) columns of a balance_snapshot row.
pub fn fix_snapshot(s: &mut BalanceSnapshot) {
    s.balance = column(s.balance, 8);
    s.invested_amount = column(s.invested_amount, 8);
    s.native_balance = column_opt(s.native_balance, 8);
}
