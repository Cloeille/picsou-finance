//! SavingsInterestService: the year's interest of a savings book under the règle de la
//! quinzaine. Read-only: nothing here writes a balance or a snapshot.

use crate::db::DbHandle;
use chrono::{Datelike, NaiveDate};
use rust_decimal::Decimal;
use serde::Serialize;

use super::model::{RateBasis, SavingsConfig, SavingsProduct};
use crate::error::{AppError, AppResult};
use crate::ledger::decimal::{HALF_UP, divide, set_scale};
use crate::ledger::model::Account;
use crate::ledger::{snapshots, transactions};

/// SavingsInterestProjection.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SavingsInterestProjection {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub estimated_interest_ytd: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub projected_interest_full_year: Decimal,
    pub next_capitalization_date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub annual_rate_pct: Decimal,
    pub basis: RateBasis,
    pub net_of_tax: bool,
}

/// A regulated book is tax-exempt: a GROSS rate basis makes no sense for it.
pub fn validate(product: SavingsProduct, basis: RateBasis) -> AppResult<()> {
    if product.is_regulated() && basis == RateBasis::Gross {
        return Err(AppError::BadRequest(format!(
            "Regulated savings products ({}) must use a NET rate basis. \
             Their interest is tax-exempt; a GROSS/NET distinction does not apply.",
            product.as_str()
        )));
    }
    Ok(())
}

/// The net annual rate in percent; COMMERCIAL + GROSS loses its tax rate, 30% (PFU) by default.
pub fn effective_net_rate_pct(config: &SavingsConfig) -> Decimal {
    if config.product.is_regulated() || config.rate_basis == RateBasis::Net {
        return config.annual_rate;
    }
    let tax = config.tax_rate_pct.unwrap_or(Decimal::from(30));
    let retention = Decimal::ONE - divide(tax, Decimal::ONE_HUNDRED, 10, HALF_UP);
    set_scale(config.annual_rate * retention, 10, HALF_UP)
}

/// The 24 quinzaine starts of the year (the 1st and the 16th) and Jan 1 of the next.
fn quinzaine_starts(year: i32) -> Vec<NaiveDate> {
    let mut starts: Vec<NaiveDate> = (1..=12)
        .flat_map(|m| [1, 16].map(|d| NaiveDate::from_ymd_opt(year, m, d).expect("a valid day")))
        .collect();
    starts.push(NaiveDate::from_ymd_opt(year + 1, 1, 1).expect("a valid day"));
    starts
}

/// The quinzaine loop over the year's movements (date, signed amount): a deposit earns from the
/// next quinzaine, a withdrawal stops earning from the start of its own; the quinzaines after
/// as_of are extrapolated at the capital reached by then.
pub fn project(
    config: &SavingsConfig,
    starting_capital: Decimal,
    movements: &[(NaiveDate, Decimal)],
    as_of: NaiveDate,
) -> SavingsInterestProjection {
    let year = as_of.year();
    let divisor = Decimal::from(2400);
    let rate = effective_net_rate_pct(config);
    let starts = quinzaine_starts(year);
    let in_range = |from: NaiveDate, to: NaiveDate| {
        movements
            .iter()
            .filter(move |(d, _)| *d >= from && *d <= to)
            .map(|(_, a)| *a)
    };

    let mut ytd = Decimal::ZERO;
    let mut projected = Decimal::ZERO;
    let mut deposits = Decimal::ZERO;
    let mut withdrawals = Decimal::ZERO;
    let mut capital_at_as_of = starting_capital;
    for k in 0..24 {
        let start = starts[k];
        let end = starts[k + 1].pred_opt().expect("a valid day");
        withdrawals += in_range(start, end)
            .filter(|a| a.is_sign_negative() && !a.is_zero())
            .map(|a| a.abs())
            .sum::<Decimal>();
        let capital = starting_capital + deposits - withdrawals;
        if start <= as_of {
            let interest = divide(capital * rate, divisor, 10, HALF_UP);
            ytd += interest;
            projected += interest;
            capital_at_as_of = capital;
        } else {
            projected += divide(capital_at_as_of * rate, divisor, 10, HALF_UP);
        }
        deposits += in_range(start, end)
            .filter(|a| a.is_sign_positive() && !a.is_zero())
            .sum::<Decimal>();
    }
    SavingsInterestProjection {
        estimated_interest_ytd: set_scale(ytd, 2, HALF_UP),
        projected_interest_full_year: set_scale(projected, 2, HALF_UP),
        next_capitalization_date: NaiveDate::from_ymd_opt(year, 12, 31).expect("a valid day"),
        annual_rate_pct: rate,
        basis: config.rate_basis,
        net_of_tax: config.rate_basis == RateBasis::Net,
    }
}

/// computeProjection: the opening capital is the year's earliest snapshot less the movements it
/// already contains, else the current balance less the year's net flows.
pub async fn compute_projection(
    conn: &mut DbHandle<'_>,
    account: &Account,
    config: &SavingsConfig,
    as_of: NaiveDate,
) -> AppResult<SavingsInterestProjection> {
    validate(config.product, config.rate_basis)?;
    let year = as_of.year();
    let jan1 = NaiveDate::from_ymd_opt(year, 1, 1).expect("a valid day");
    let dec31 = NaiveDate::from_ymd_opt(year, 12, 31).expect("a valid day");
    let movements: Vec<(NaiveDate, Decimal)> =
        transactions::find_by_accounts_between(&mut *conn, &[account.id], jan1, dec31)
            .await?
            .into_iter()
            .map(|t| (t.date, t.amount))
            .collect();
    let snapshots = snapshots::find_by_account_between(&mut *conn, account.id, jan1, dec31).await?;
    let starting_capital = match snapshots.first() {
        Some(earliest) => {
            let booked: Decimal = movements
                .iter()
                .filter(|(d, _)| *d <= earliest.date)
                .map(|(_, a)| *a)
                .sum();
            earliest.balance - booked
        }
        None => account.current_balance - movements.iter().map(|(_, a)| *a).sum::<Decimal>(),
    };
    Ok(project(config, starting_capital, &movements, as_of))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> Decimal {
        s.parse().unwrap()
    }

    fn day(s: &str) -> NaiveDate {
        s.parse().unwrap()
    }

    fn config(
        product: SavingsProduct,
        rate: &str,
        basis: RateBasis,
        tax: Option<&str>,
    ) -> SavingsConfig {
        SavingsConfig {
            account_id: 1,
            product,
            annual_rate: d(rate),
            rate_basis: basis,
            tax_rate_pct: tax.map(d),
            ceiling: None,
        }
    }

    #[test]
    fn a_flat_year_earns_exactly_the_rate() {
        let c = config(SavingsProduct::LivretA, "3.0000", RateBasis::Net, None);
        let p = project(&c, d("10000.00"), &[], day("2025-12-31"));
        assert_eq!(p.estimated_interest_ytd.to_string(), "300.00");
        assert_eq!(p.projected_interest_full_year.to_string(), "300.00");
        assert_eq!(p.next_capitalization_date, day("2025-12-31"));
        assert!(p.net_of_tax);
    }

    #[test]
    fn a_deposit_earns_from_the_next_quinzaine() {
        let c = config(SavingsProduct::LivretA, "2.4000", RateBasis::Net, None);
        // 12000 deposited on 2025-06-10 earns from 06-16: 13 quinzaines of 12000 * 2.4 / 2400.
        let p = project(
            &c,
            Decimal::ZERO,
            &[(day("2025-06-10"), d("12000"))],
            day("2025-12-31"),
        );
        assert_eq!(p.projected_interest_full_year.to_string(), "156.00");
    }

    #[test]
    fn a_withdrawal_stops_earning_from_its_own_quinzaine() {
        let c = config(SavingsProduct::LivretA, "2.4000", RateBasis::Net, None);
        // 2400 withdrawn on 2025-06-20 stops earning from 06-16: 11 quinzaines lost of 2.40.
        let p = project(
            &c,
            d("2400"),
            &[(day("2025-06-20"), d("-2400"))],
            day("2025-12-31"),
        );
        assert_eq!(p.projected_interest_full_year.to_string(), "26.40");
    }

    #[test]
    fn commercial_gross_loses_the_flat_tax() {
        let c = config(
            SavingsProduct::Commercial,
            "4.0000",
            RateBasis::Gross,
            Some("30.00"),
        );
        assert_eq!(effective_net_rate_pct(&c).to_string(), "2.8000000000");
        let defaulted = config(SavingsProduct::Commercial, "4.0000", RateBasis::Gross, None);
        assert_eq!(
            effective_net_rate_pct(&defaulted).to_string(),
            "2.8000000000"
        );
        let p = project(&c, d("10000"), &[], day("2025-12-31"));
        assert_eq!(p.projected_interest_full_year.to_string(), "280.00");
        assert!(!p.net_of_tax);
        let net = config(SavingsProduct::Commercial, "2.8000", RateBasis::Net, None);
        assert_eq!(effective_net_rate_pct(&net).to_string(), "2.8000");
    }

    #[test]
    fn a_partial_year_extrapolates_the_rest() {
        let c = config(SavingsProduct::LivretA, "2.4000", RateBasis::Net, None);
        let p = project(&c, d("12000"), &[], day("2025-03-20"));
        // Six quinzaines started by 03-20 (03-16 included).
        assert_eq!(p.estimated_interest_ytd.to_string(), "72.00");
        assert_eq!(p.projected_interest_full_year.to_string(), "288.00");
    }

    #[test]
    fn regulated_books_refuse_a_gross_basis() {
        for product in [
            SavingsProduct::LivretA,
            SavingsProduct::Ldds,
            SavingsProduct::Lep,
        ] {
            assert!(validate(product, RateBasis::Gross).is_err());
            assert!(validate(product, RateBasis::Net).is_ok());
        }
        assert!(validate(SavingsProduct::Commercial, RateBasis::Gross).is_ok());
    }
}
