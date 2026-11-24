//! Exact `rust_decimal::Decimal` codec and SQLite custom functions.
//!
//! Picsou NEVER stores financial quantities, amounts, prices, or rates in SQLite `REAL` columns.
//! Every PostgreSQL `NUMERIC` column is stored as canonical `TEXT` preserving exact decimal
//! precision and scale, and every SQLite connection registers deterministic, innocuous
//! `rust_decimal::Decimal` functions (`dec_add`, `dec_sub`, `dec_mul`, `dec_div`, `dec_cmp`,
//! `dec_abs`, `dec_neg`, and aggregate `dec_sum`).

use std::str::FromStr;

use rusqlite::Connection;
use rusqlite::functions::{Aggregate, Context, FunctionFlags};
use rusqlite::types::ValueRef;
use rust_decimal::{Decimal, RoundingStrategy};

/// Errors produced when encoding or decoding canonical SQLite decimal values.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum DecimalCodecError {
    #[error("binary floating-point SQLite REAL value is forbidden for financial decimals")]
    FloatValueForbidden,
    #[error("SQLite BLOB value cannot be decoded as a financial decimal")]
    BlobValueForbidden,
    #[error("invalid UTF-8 in SQLite decimal text: {0}")]
    InvalidUtf8(String),
    #[error("invalid decimal literal '{0}'")]
    InvalidDecimal(String),
    #[error("decimal arithmetic overflow")]
    Overflow,
    #[error("decimal division by zero")]
    DivisionByZero,
    #[error("invalid decimal scale {0} (expected 0..=28)")]
    InvalidScale(i64),
}

/// Encodes a `Decimal` as canonical SQLite `TEXT`, preserving its exact scale (no scientific
/// notation, no trailing-zero stripping).
pub fn to_sqlite_text(value: Decimal) -> String {
    value.to_string()
}

/// Decodes a canonical SQLite `TEXT` decimal string into a `Decimal`, preserving its exact scale.
/// Also accepts scientific notation (e.g. `0E-8`) if written by an external tool.
pub fn from_sqlite_text(raw: &str) -> Result<Decimal, DecimalCodecError> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(DecimalCodecError::InvalidDecimal(raw.to_owned()));
    }
    Decimal::from_str(trimmed)
        .or_else(|_| Decimal::from_scientific(trimmed))
        .map_err(|_| DecimalCodecError::InvalidDecimal(raw.to_owned()))
}

/// Decodes a SQLite value reference into an optional `Decimal`.
///
/// Accepts `NULL`, `TEXT`, and `INTEGER` (so SQL literals like `0` or `100` work seamlessly in
/// expressions such as `dec_cmp(amount, 0)`). Rejects SQLite `REAL` and `BLOB` values outright.
pub fn from_sqlite_value(value: ValueRef<'_>) -> Result<Option<Decimal>, DecimalCodecError> {
    match value {
        ValueRef::Null => Ok(None),
        ValueRef::Text(bytes) => {
            let text = std::str::from_utf8(bytes)
                .map_err(|e| DecimalCodecError::InvalidUtf8(e.to_string()))?;
            from_sqlite_text(text).map(Some)
        }
        ValueRef::Integer(i) => Ok(Some(Decimal::from(i))),
        ValueRef::Real(_) => Err(DecimalCodecError::FloatValueForbidden),
        ValueRef::Blob(_) => Err(DecimalCodecError::BlobValueForbidden),
    }
}

fn to_rusqlite_err(err: DecimalCodecError) -> rusqlite::Error {
    rusqlite::Error::UserFunctionError(Box::new(err))
}

fn arg_decimal(ctx: &Context<'_>, idx: usize) -> rusqlite::Result<Option<Decimal>> {
    from_sqlite_value(ctx.get_raw(idx)).map_err(to_rusqlite_err)
}

fn arg_scale(ctx: &Context<'_>, idx: usize) -> rusqlite::Result<Option<u32>> {
    match ctx.get_raw(idx) {
        ValueRef::Null => Ok(None),
        ValueRef::Integer(v) => u32::try_from(v)
            .ok()
            .filter(|&s| s <= 28)
            .map(Some)
            .ok_or_else(|| to_rusqlite_err(DecimalCodecError::InvalidScale(v))),
        ValueRef::Text(bytes) => {
            let s = std::str::from_utf8(bytes)
                .map_err(|e| to_rusqlite_err(DecimalCodecError::InvalidUtf8(e.to_string())))?;
            let parsed = s
                .trim()
                .parse::<i64>()
                .map_err(|_| to_rusqlite_err(DecimalCodecError::InvalidDecimal(s.to_owned())))?;
            u32::try_from(parsed)
                .ok()
                .filter(|&v| v <= 28)
                .map(Some)
                .ok_or_else(|| to_rusqlite_err(DecimalCodecError::InvalidScale(parsed)))
        }
        ValueRef::Real(_) => Err(to_rusqlite_err(DecimalCodecError::FloatValueForbidden)),
        ValueRef::Blob(_) => Err(to_rusqlite_err(DecimalCodecError::BlobValueForbidden)),
    }
}

/// SQLite aggregate `dec_sum(x)`: exact sum over canonical decimal `TEXT` values.
///
/// Mirrors PostgreSQL `SUM(numeric)`: returns `NULL` when given zero non-NULL rows, and preserves
/// exact `rust_decimal::Decimal` arithmetic with overflow checking.
struct DecSum;

impl Aggregate<Option<Decimal>, Option<String>> for DecSum {
    fn init(&self, _ctx: &mut Context<'_>) -> rusqlite::Result<Option<Decimal>> {
        Ok(None)
    }

    fn step(&self, ctx: &mut Context<'_>, acc: &mut Option<Decimal>) -> rusqlite::Result<()> {
        let Some(value) = arg_decimal(ctx, 0)? else {
            return Ok(());
        };
        *acc = match *acc {
            None => Some(value),
            Some(current) => Some(
                current
                    .checked_add(value)
                    .ok_or_else(|| to_rusqlite_err(DecimalCodecError::Overflow))?,
            ),
        };
        Ok(())
    }

    fn finalize(
        &self,
        _ctx: &mut Context<'_>,
        acc: Option<Option<Decimal>>,
    ) -> rusqlite::Result<Option<String>> {
        Ok(acc.flatten().map(to_sqlite_text))
    }
}

/// Registers all exact decimal scalar and aggregate functions on a SQLite connection.
///
/// Functions are marked `SQLITE_DETERMINISTIC | SQLITE_INNOCUOUS` so SQLite permits them inside
/// `CHECK` constraints, generated columns, and indexes.
pub fn register_decimal_functions(conn: &Connection) -> rusqlite::Result<()> {
    let flags = FunctionFlags::SQLITE_UTF8
        | FunctionFlags::SQLITE_DETERMINISTIC
        | FunctionFlags::SQLITE_INNOCUOUS;

    conn.create_scalar_function("dec_add", 2, flags, |ctx| {
        let (Some(a), Some(b)) = (arg_decimal(ctx, 0)?, arg_decimal(ctx, 1)?) else {
            return Ok(None::<String>);
        };
        let sum = a
            .checked_add(b)
            .ok_or_else(|| to_rusqlite_err(DecimalCodecError::Overflow))?;
        Ok(Some(to_sqlite_text(sum)))
    })?;

    conn.create_scalar_function("dec_sub", 2, flags, |ctx| {
        let (Some(a), Some(b)) = (arg_decimal(ctx, 0)?, arg_decimal(ctx, 1)?) else {
            return Ok(None::<String>);
        };
        let diff = a
            .checked_sub(b)
            .ok_or_else(|| to_rusqlite_err(DecimalCodecError::Overflow))?;
        Ok(Some(to_sqlite_text(diff)))
    })?;

    conn.create_scalar_function("dec_mul", 2, flags, |ctx| {
        let (Some(a), Some(b)) = (arg_decimal(ctx, 0)?, arg_decimal(ctx, 1)?) else {
            return Ok(None::<String>);
        };
        let prod = a
            .checked_mul(b)
            .ok_or_else(|| to_rusqlite_err(DecimalCodecError::Overflow))?;
        Ok(Some(to_sqlite_text(prod)))
    })?;

    conn.create_scalar_function("dec_div", 3, flags, |ctx| {
        let (Some(a), Some(b), Some(scale)) = (
            arg_decimal(ctx, 0)?,
            arg_decimal(ctx, 1)?,
            arg_scale(ctx, 2)?,
        ) else {
            return Ok(None::<String>);
        };
        if b.is_zero() {
            return Err(to_rusqlite_err(DecimalCodecError::DivisionByZero));
        }
        let quotient = a
            .checked_div(b)
            .ok_or_else(|| to_rusqlite_err(DecimalCodecError::Overflow))?
            .round_dp_with_strategy(scale, RoundingStrategy::MidpointAwayFromZero);
        Ok(Some(to_sqlite_text(quotient)))
    })?;

    conn.create_scalar_function("dec_div", 2, flags, |ctx| {
        let (Some(a), Some(b)) = (arg_decimal(ctx, 0)?, arg_decimal(ctx, 1)?) else {
            return Ok(None::<String>);
        };
        if b.is_zero() {
            return Err(to_rusqlite_err(DecimalCodecError::DivisionByZero));
        }
        let quotient = a
            .checked_div(b)
            .ok_or_else(|| to_rusqlite_err(DecimalCodecError::Overflow))?;
        Ok(Some(to_sqlite_text(quotient)))
    })?;

    conn.create_scalar_function("dec_cmp", 2, flags, |ctx| {
        let (Some(a), Some(b)) = (arg_decimal(ctx, 0)?, arg_decimal(ctx, 1)?) else {
            return Ok(None::<i64>);
        };
        let ord = match a.cmp(&b) {
            std::cmp::Ordering::Less => -1i64,
            std::cmp::Ordering::Equal => 0i64,
            std::cmp::Ordering::Greater => 1i64,
        };
        Ok(Some(ord))
    })?;

    conn.create_scalar_function("dec_abs", 1, flags, |ctx| {
        let Some(a) = arg_decimal(ctx, 0)? else {
            return Ok(None::<String>);
        };
        Ok(Some(to_sqlite_text(a.abs())))
    })?;

    conn.create_scalar_function("dec_neg", 1, flags, |ctx| {
        let Some(a) = arg_decimal(ctx, 0)? else {
            return Ok(None::<String>);
        };
        Ok(Some(to_sqlite_text(-a)))
    })?;

    conn.create_aggregate_function("dec_sum", 1, flags, DecSum)?;

    // dec_round(x, s): x rounded HALF_UP to s decimals, as PostgreSQL stores a value in a
    // NUMERIC(p, s) column. A value that already fits comes back as the same text, so a trigger
    // can tell with IS NOT whether there is anything to rewrite.
    // A value that is not a decimal (PostgreSQL's 'NaN', a REAL written by hand) comes back
    // untouched: rounding never makes a write fail that SQLite accepted before.
    conn.create_scalar_function("dec_round", 2, flags, |ctx| {
        let raw = ctx.get_raw(0);
        let (Ok(Some(value)), Some(scale)) = (from_sqlite_value(raw), arg_scale(ctx, 1)?) else {
            return Ok(rusqlite::types::Value::from(raw));
        };
        Ok(rusqlite::types::Value::Text(to_sqlite_text(
            if value.scale() > scale {
                value.round_dp_with_strategy(scale, RoundingStrategy::MidpointAwayFromZero)
            } else {
                value
            },
        )))
    })?;

    Ok(())
}

/// Every NUMERIC column of the schema with the scale PostgreSQL gives it: (table, column, scale).
pub fn numeric_columns() -> Vec<(&'static str, &'static str, u32)> {
    crate::db::schema::EXPECTED
        .lines()
        .filter_map(|line| {
            let mut fields = line.split('\t');
            match (fields.next(), fields.next(), fields.next(), fields.next()) {
                (Some("column"), Some(table), Some(column), Some("numeric")) => {
                    pg_numeric_column_scale(&format!("FROM {table}"), column)
                        .map(|scale| (table, column, scale))
                }
                _ => None,
            }
        })
        .collect()
}

/// Makes this connection round what it writes into a NUMERIC column to the column's scale, as
/// PostgreSQL does on write. Without it SQLite keeps every decimal it is given: a read rounds
/// the value, but a sum adds the unrounded ones and can differ from PostgreSQL's by a step.
///
/// The triggers are TEMP: they belong to the connection, not to the file, so the schema stays
/// the one the migrations describe. Only the writer connection needs them.
pub fn install_write_rounding(conn: &Connection) -> rusqlite::Result<()> {
    let mut by_table: std::collections::BTreeMap<&str, Vec<(&str, u32)>> = Default::default();
    for (table, column, scale) in numeric_columns() {
        by_table.entry(table).or_default().push((column, scale));
    }
    for (table, columns) in by_table {
        let needs_rounding = columns
            .iter()
            .map(|(c, s)| format!("NEW.\"{c}\" IS NOT dec_round(NEW.\"{c}\", {s})"))
            .collect::<Vec<_>>()
            .join(" OR ");
        let round = columns
            .iter()
            .map(|(c, s)| format!("\"{c}\" = dec_round(\"{c}\", {s})"))
            .collect::<Vec<_>>()
            .join(", ");
        let updated = columns
            .iter()
            .map(|(c, _)| format!("\"{c}\""))
            .collect::<Vec<_>>()
            .join(", ");
        for (event, name) in [
            ("INSERT".to_owned(), "ins"),
            (format!("UPDATE OF {updated}"), "upd"),
        ] {
            conn.execute_batch(&format!(
                "CREATE TEMP TRIGGER IF NOT EXISTS \"picsou_round_{name}_{table}\" \
                 AFTER {event} ON main.\"{table}\" WHEN {needs_rounding} \
                 BEGIN UPDATE \"{table}\" SET {round} WHERE rowid = NEW.rowid; END;"
            ))?;
        }
    }
    Ok(())
}

/// Returns the fixed PostgreSQL `NUMERIC(p, s)` fractional scale for a column read from SQLite,
/// matching every `NUMERIC` column in `schema.tsv` so SQLite returns identical decimal scales
/// (and HALF_UP rounding) to PostgreSQL.
pub fn pg_numeric_column_scale(sql: &str, col_name: &str) -> Option<u32> {
    match col_name {
        // Scale 8: NUMERIC(20, 8)
        "cash_balance"
        | "current_balance"
        | "payment_due_amount"
        | "provider_invested_eur"
        | "provider_paid_in_eur"
        | "scheduled_payment_eur"
        | "average_buy_in"
        | "current_price"
        | "provider_pnl_eur"
        | "provider_value_eur"
        | "quantity"
        | "balance"
        | "invested_amount"
        | "native_balance"
        | "interest"
        | "principal"
        | "borrowed_amount"
        | "file_fees"
        | "insurance_monthly"
        | "monthly_payment"
        | "amount_eur"
        | "planned_monthly_amount"
        | "price_eur"
        | "estimated_value"
        | "high_value"
        | "low_value"
        | "price_per_sqm"
        | "agency_fees"
        | "notary_fees"
        | "purchase_price"
        | "rental_income"
        | "works_cost"
        | "annual_distribution_per_share_eur"
        | "share_count"
        | "subscription_price_eur"
        | "withdrawal_price_eur"
        | "fees"
        | "price_per_unit" => Some(8),

        // Scale 6: NUMERIC(9, 6) / NUMERIC(12, 6)
        "latitude" | "longitude" => Some(6),

        // Scale 4: NUMERIC(5, 4) / NUMERIC(6, 4)
        "interest_rate" => Some(4),

        // Scale 3: NUMERIC(6, 3) / NUMERIC(4, 3)
        "share_percent" | "expected_return" | "geocode_score" | "percent" | "ter_percent" => {
            Some(3)
        }

        // Scale 2: NUMERIC(20, 2) / NUMERIC(10, 2) / NUMERIC(5, 2) / NUMERIC(4, 2)
        "monthly_limit"
        | "monthly_amount"
        | "target_amount"
        | "alternative_pct"
        | "crypto_pct"
        | "equity_pct"
        | "monthly_essential_expenses"
        | "real_estate_pct"
        | "annual_gross_income"
        | "marginal_tax_rate"
        | "monthly_net_before_tax"
        | "monthly_savings_capacity"
        | "tax_household_parts"
        | "withholding_tax_rate"
        | "land_area"
        | "surface_area"
        | "amount_max"
        | "amount_min"
        | "expected_amount"
        | "previous_amount"
        | "ceiling"
        | "tax_rate_pct" => Some(2),

        // Disambiguated columns (different PostgreSQL scales across tables)
        "amount" => {
            if sql.contains("goal_manual_contribution") || sql.contains("goal_month_override") {
                Some(2)
            } else if sql.contains("transaction")
                || sql.contains("dividend_event")
                || sql.contains("peer_loan_repayment")
            {
                Some(8)
            } else {
                None
            }
        }
        "annual_rate" => {
            if sql.contains("peer_loan_terms") {
                Some(6)
            } else if sql.contains("savings_interest_config") {
                Some(4)
            } else {
                None
            }
        }
        "confidence" => {
            if sql.contains("recurring_series") {
                Some(3)
            } else {
                None
            }
        }
        _ => None,
    }
}

/// Normalizes a raw SQLite decimal text string to exact PostgreSQL `NUMERIC(p, scale)`
/// HALF_UP rounding and trailing-zero formatting.
pub fn format_pg_numeric_scale(raw: &str, scale: u32) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let parsed = Decimal::from_str(trimmed)
        .or_else(|_| Decimal::from_scientific(trimmed))
        .ok()?;
    let rounded = parsed.round_dp_with_strategy(scale, RoundingStrategy::MidpointAwayFromZero);
    Some(format!("{rounded:.prec$}", prec = scale as usize))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codec_preserves_exact_scale() {
        let d = Decimal::from_str("12345.67000000").unwrap();
        let encoded = to_sqlite_text(d);
        assert_eq!(encoded, "12345.67000000");
        let decoded = from_sqlite_text(&encoded).unwrap();
        assert_eq!(decoded, d);
        assert_eq!(decoded.scale(), 8);
    }

    #[test]
    fn codec_accepts_scientific_and_rejects_garbage() {
        let zero = from_sqlite_text("0E-8").unwrap();
        assert!(zero.is_zero());
        assert_eq!(zero.scale(), 8);
        assert!(from_sqlite_text("NaN").is_err());
        assert!(from_sqlite_text("").is_err());
    }

    #[test]
    fn scalar_and_aggregate_functions_are_exact() {
        let conn = Connection::open_in_memory().unwrap();
        register_decimal_functions(&conn).unwrap();

        let sum: String = conn
            .query_row("SELECT dec_add('0.1', '0.2')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(sum, "0.3");

        let sub: String = conn
            .query_row("SELECT dec_sub('10.00', '0.01')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(sub, "9.99");

        let mul: String = conn
            .query_row("SELECT dec_mul('12345.6789', '100')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(mul, "1234567.8900");

        let div: String = conn
            .query_row("SELECT dec_div('10', '3', 8)", [], |r| r.get(0))
            .unwrap();
        assert_eq!(div, "3.33333333");

        let cmp_lt: i64 = conn
            .query_row("SELECT dec_cmp('20.00', '100.00')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(cmp_lt, -1);

        let cmp_eq: i64 = conn
            .query_row("SELECT dec_cmp('10.00', '10.00000000')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(cmp_eq, 0);

        let abs: String = conn
            .query_row("SELECT dec_abs('-42.50')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(abs, "42.50");

        let neg: String = conn
            .query_row("SELECT dec_neg('42.50')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(neg, "-42.50");

        let null_prop: Option<String> = conn
            .query_row("SELECT dec_add('1.00', NULL)", [], |r| r.get(0))
            .unwrap();
        assert_eq!(null_prop, None);

        // Passing a SQLite REAL literal must be rejected.
        let float_err = conn.query_row("SELECT dec_add(0.1, '0.2')", [], |r| r.get::<_, String>(0));
        assert!(float_err.is_err());

        // Division by zero must error.
        let div0 = conn.query_row("SELECT dec_div('1', '0', 2)", [], |r| r.get::<_, String>(0));
        assert!(div0.is_err());
    }

    #[test]
    fn every_numeric_column_of_the_schema_has_its_postgresql_scale() {
        // A column missing from pg_numeric_column_scale is neither formatted on read nor rounded
        // on write.
        let without_scale: Vec<String> = crate::db::schema::EXPECTED
            .lines()
            .filter_map(|line| {
                let fields: Vec<&str> = line.split('\t').collect();
                match fields.as_slice() {
                    ["column", table, column, "numeric"] => {
                        pg_numeric_column_scale(&format!("FROM {table}"), column)
                            .is_none()
                            .then(|| format!("{table}.{column}"))
                    }
                    _ => None,
                }
            })
            .collect();
        assert_eq!(without_scale, Vec::<String>::new());
    }

    #[test]
    fn pg_numeric_scale_formatting_matches_postgres() {
        assert_eq!(
            format_pg_numeric_scale("100", 8).as_deref(),
            Some("100.00000000")
        );
        assert_eq!(
            format_pg_numeric_scale("-29.999", 2).as_deref(),
            Some("-30.00")
        );
        assert_eq!(
            format_pg_numeric_scale("1.0e-07", 8).as_deref(),
            Some("0.00000010")
        );
        assert_eq!(format_pg_numeric_scale("30", 3).as_deref(), Some("30.000"));
        assert_eq!(
            pg_numeric_column_scale("SELECT amount FROM \"transaction\"", "amount"),
            Some(8)
        );
        assert_eq!(
            pg_numeric_column_scale("SELECT amount FROM goal_manual_contribution", "amount"),
            Some(2)
        );
        assert_eq!(
            pg_numeric_column_scale("SELECT annual_rate FROM peer_loan_terms", "annual_rate"),
            Some(6)
        );
        assert_eq!(
            pg_numeric_column_scale(
                "SELECT annual_rate FROM savings_interest_config",
                "annual_rate"
            ),
            Some(4)
        );
        assert_eq!(
            pg_numeric_column_scale("SELECT confidence FROM property_valuation", "confidence"),
            None
        );
        assert_eq!(
            pg_numeric_column_scale("SELECT confidence FROM recurring_series", "confidence"),
            Some(3)
        );
    }
}
