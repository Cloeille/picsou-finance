//! Merges external positions (several ISINs, or long and short legs) that resolve to the same
//! ticker, for the sync services (IBKR, Trade Republic, BoursoBank).
//!
//! The merge is a volume-weighted average so the result does not depend on which position was
//! seen first. IBKR can carry short positions (negative quantity), so it is sign-aware.

use rust_decimal::Decimal;

use crate::ledger::decimal::{HALF_UP, divide};

/// One ticker's aggregated position during a sync.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HoldingAgg {
    pub quantity: Decimal,
    pub average_buy_in: Option<Decimal>,
    pub current_price: Option<Decimal>,
    pub name: Option<String>,
}

/// Merges two aggregates of the same ticker; current price and name come from `prev` first.
///
/// - Zero net quantity: a flat sentinel (quantity 0, no average). Callers must skip it before
///   persisting, so a stale non-zero holding does not survive a flat net position.
/// - Opposite signs, non-zero net: the quantity is netted and the average comes from the side
///   with the larger absolute quantity, the cost basis of the residual exposure.
/// - Same signs, or one side flat: (q1 * a1 + q2 * a2) / (q1 + q2) at scale 8 half-up, a
///   missing average counting as zero.
pub fn vwap_merge(prev: &HoldingAgg, next: &HoldingAgg) -> HoldingAgg {
    let total_quantity = prev.quantity + next.quantity;
    let current_price = prev.current_price.or(next.current_price);
    let name = prev.name.clone().or_else(|| next.name.clone());

    if total_quantity.is_zero() {
        return HoldingAgg {
            quantity: Decimal::ZERO,
            average_buy_in: None,
            current_price,
            name,
        };
    }

    let mixed_signs = !prev.quantity.is_zero()
        && !next.quantity.is_zero()
        && prev.quantity.is_sign_negative() != next.quantity.is_sign_negative();

    if mixed_signs {
        tracing::warn!(
            "IBKR: netting mixed-sign positions for '{}': {} @ {:?} vs {} @ {:?} -> net {}",
            name.as_deref().unwrap_or("null"),
            prev.quantity,
            prev.average_buy_in,
            next.quantity,
            next.average_buy_in,
            total_quantity
        );
        let larger = if prev.quantity.abs() >= next.quantity.abs() {
            prev
        } else {
            next
        };
        return HoldingAgg {
            quantity: total_quantity,
            average_buy_in: larger.average_buy_in,
            current_price,
            name,
        };
    }

    let prev_average = prev.average_buy_in.unwrap_or(Decimal::ZERO);
    let next_average = next.average_buy_in.unwrap_or(Decimal::ZERO);
    let weighted = divide(
        prev.quantity * prev_average + next.quantity * next_average,
        total_quantity,
        8,
        HALF_UP,
    );
    HoldingAgg {
        quantity: total_quantity,
        average_buy_in: Some(weighted),
        current_price,
        name,
    }
}
