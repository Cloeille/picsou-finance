use std::str::FromStr;

use picsou::ledger::holding_dedup::{HoldingAgg, vwap_merge};
use rust_decimal::Decimal;

fn bd(s: &str) -> Decimal {
    Decimal::from_str(s).unwrap()
}

fn agg(
    quantity: &str,
    average: Option<&str>,
    price: Option<&str>,
    name: Option<&str>,
) -> HoldingAgg {
    HoldingAgg {
        quantity: bd(quantity),
        average_buy_in: average.map(bd),
        current_price: price.map(bd),
        name: name.map(str::to_owned),
    }
}

#[test]
fn vwap_merge_total_quantity_zero_returns_zero_quantity_sentinel() {
    let a = agg("0", Some("10"), Some("100"), Some("A"));
    let b = agg("0", Some("20"), Some("110"), Some("B"));
    let merged = vwap_merge(&a, &b);
    assert_eq!(merged.quantity, Decimal::ZERO);
    assert_eq!(merged.average_buy_in, None);
}

#[test]
fn vwap_merge_opposite_signs_netting_to_zero_returns_zero_quantity_sentinel() {
    let long_leg = agg("40", Some("10"), Some("12"), Some("Acme"));
    let short_leg = agg("-40", Some("15"), Some("12"), Some("Acme"));
    let merged = vwap_merge(&long_leg, &short_leg);
    assert_eq!(merged.quantity.to_string(), "0");
    assert_eq!(merged.average_buy_in, None);
}

#[test]
fn vwap_merge_opposite_signs_non_zero_net_nets_quantity_and_keeps_larger_side_average() {
    let big = agg("100", Some("10"), Some("12"), Some("Acme"));
    let small = agg("-40", Some("20"), Some("12"), Some("Acme"));
    let merged = vwap_merge(&big, &small);
    assert_eq!(merged.quantity, bd("60"));
    assert_eq!(merged.average_buy_in, Some(bd("10")));
}

#[test]
fn vwap_merge_opposite_signs_non_zero_net_is_order_independent() {
    let big = agg("100", Some("10"), Some("12"), Some("Acme"));
    let small = agg("-40", Some("20"), Some("12"), Some("Acme"));
    let merged = vwap_merge(&small, &big);
    assert_eq!(merged.quantity, bd("60"));
    assert_eq!(merged.average_buy_in, Some(bd("10")));
}

#[test]
fn vwap_merge_computes_weighted_average() {
    let a = agg("2", Some("10"), Some("100"), Some("Foo"));
    let b = agg("3", Some("20"), Some("110"), Some("Foo"));
    let merged = vwap_merge(&a, &b);
    assert_eq!(merged.quantity, bd("5"));
    assert_eq!(merged.average_buy_in.unwrap().to_string(), "16.00000000");
}

#[test]
fn vwap_merge_handles_non_integer_vwap() {
    let a = agg("10", Some("100"), None, None);
    let b = agg("20", Some("200"), None, None);
    let merged = vwap_merge(&a, &b);
    assert_eq!(merged.quantity, bd("30"));
    assert_eq!(merged.average_buy_in.unwrap().to_string(), "166.66666667");
}

#[test]
fn vwap_merge_is_order_independent() {
    let a = agg("2", Some("10"), Some("100"), Some("A"));
    let b = agg("3", Some("20"), Some("110"), Some("B"));
    assert_eq!(
        vwap_merge(&a, &b).average_buy_in,
        vwap_merge(&b, &a).average_buy_in
    );
}

#[test]
fn vwap_merge_treats_null_average_as_zero() {
    let a = agg("2", None, None, None);
    let b = agg("3", Some("20"), None, None);
    assert_eq!(
        vwap_merge(&a, &b).average_buy_in.unwrap().to_string(),
        "12.00000000"
    );
}

#[test]
fn vwap_merge_keeps_first_non_null_name() {
    let with_name = agg("1", Some("1"), None, Some("FromIsinA"));
    let without_name = agg("1", Some("1"), None, None);
    assert_eq!(
        vwap_merge(&with_name, &without_name).name.as_deref(),
        Some("FromIsinA")
    );
    assert_eq!(
        vwap_merge(&without_name, &with_name).name.as_deref(),
        Some("FromIsinA")
    );
}

#[test]
fn vwap_merge_falls_back_to_next_current_price_when_prev_is_null() {
    let a = agg("1", Some("1"), None, Some("A"));
    let b = agg("1", Some("1"), Some("42"), Some("B"));
    assert_eq!(vwap_merge(&a, &b).current_price, Some(bd("42")));
}

#[test]
fn vwap_merge_same_signs_unchanged_vwap_regression() {
    let a = agg("10", Some("5"), Some("100"), Some("Acme"));
    let b = agg("30", Some("9"), Some("100"), Some("Acme"));
    let merged = vwap_merge(&a, &b);
    assert_eq!(merged.quantity, bd("40"));
    assert_eq!(merged.average_buy_in.unwrap().to_string(), "8.00000000");
}
