//! The PeerLoan* request and response bodies.

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::http::json::{Errors, Validate};
use crate::ledger::model::{PeerLoanRatePeriod, PeerLoanRepayment, PeerLoanRepaymentKind};
use crate::ledger::peer_loans::PaymentSplit;
use crate::wealth::validation::{decimal_max_msg, decimal_min, decimal_min_msg, digits, max, min};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanTermsRequest {
    pub principal: Option<Decimal>,
    pub lent_at: Option<NaiveDate>,
    pub expected_back: Option<NaiveDate>,
    pub annual_rate: Option<Decimal>,
    pub rate_period: Option<PeerLoanRatePeriod>,
    pub planned_monthly_amount: Option<Decimal>,
    pub planned_months: Option<i64>,
    pub linked_account_id: Option<i64>,
    pub note: Option<String>,
    pub with_cash_leg: Option<bool>,
}

impl Validate for PeerLoanTermsRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("principal", self.principal.as_ref());
        decimal_min_msg(
            errors,
            "principal",
            self.principal,
            "0.01",
            Some("Principal must be greater than zero"),
        );
        digits(
            errors,
            "principal",
            self.principal,
            12,
            2,
            Some("Principal is too large"),
        );
        errors.not_null("lentAt", self.lent_at.as_ref());
        decimal_min(errors, "annualRate", self.annual_rate, "0");
        decimal_max_msg(
            errors,
            "annualRate",
            self.annual_rate,
            "1000",
            Some("The rate is too high"),
        );
        digits(
            errors,
            "annualRate",
            self.annual_rate,
            6,
            30,
            Some("The rate has too many digits"),
        );
        decimal_min(
            errors,
            "plannedMonthlyAmount",
            self.planned_monthly_amount,
            "0",
        );
        digits(
            errors,
            "plannedMonthlyAmount",
            self.planned_monthly_amount,
            12,
            2,
            Some("The planned payment is too large"),
        );
        min(errors, "plannedMonths", self.planned_months, 1);
        max(errors, "plannedMonths", self.planned_months, 1200);
        errors.not_null("linkedAccountId", self.linked_account_id.as_ref());
        errors.size("note", self.note.as_deref(), 0, 500);
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanRepaymentRequest {
    pub paid_on: Option<NaiveDate>,
    pub amount: Option<Decimal>,
    pub kind: Option<PeerLoanRepaymentKind>,
    pub note: Option<String>,
    pub with_cash_leg: Option<bool>,
}

impl Validate for PeerLoanRepaymentRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("paidOn", self.paid_on.as_ref());
        errors.not_null("amount", self.amount.as_ref());
        decimal_min_msg(
            errors,
            "amount",
            self.amount,
            "0.01",
            Some("Amount must be greater than zero"),
        );
        digits(errors, "amount", self.amount, 12, 2, Some("Amount is too large"));
        errors.size("note", self.note.as_deref(), 0, 255);
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanRepaymentFromTransactionRequest {
    pub transaction_id: Option<i64>,
    pub kind: Option<PeerLoanRepaymentKind>,
}

impl Validate for PeerLoanRepaymentFromTransactionRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("transactionId", self.transaction_id.as_ref());
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanFromTransactionRequest {
    pub transaction_id: Option<i64>,
    pub beneficiary: Option<String>,
}

impl Validate for PeerLoanFromTransactionRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("transactionId", self.transaction_id.as_ref());
        errors.size("beneficiary", self.beneficiary.as_deref(), 0, 100);
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanSimulationRequest {
    pub principal: Option<Decimal>,
    pub annual_rate: Option<Decimal>,
    pub months: Option<i64>,
    pub monthly_payment: Option<Decimal>,
}

impl Validate for PeerLoanSimulationRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("principal", self.principal.as_ref());
        decimal_min(errors, "principal", self.principal, "0.01");
        digits(errors, "principal", self.principal, 12, 2, None);
        decimal_min(errors, "annualRate", self.annual_rate, "0");
        decimal_max_msg(errors, "annualRate", self.annual_rate, "1000", None);
        digits(
            errors,
            "annualRate",
            self.annual_rate,
            6,
            30,
            Some("The rate has too many digits"),
        );
        min(errors, "months", self.months, 1);
        max(errors, "months", self.months, 1200);
        decimal_min(errors, "monthlyPayment", self.monthly_payment, "0.01");
        digits(errors, "monthlyPayment", self.monthly_payment, 12, 2, None);
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CurvePoint {
    pub date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub actual: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub scheduled: Option<Decimal>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanDetailResponse {
    pub account_id: i64,
    pub account_name: String,
    pub configured: bool,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal: Decimal,
    pub lent_at: Option<NaiveDate>,
    pub expected_back: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub annual_rate: Option<Decimal>,
    pub rate_period: PeerLoanRatePeriod,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub planned_monthly_amount: Option<Decimal>,
    pub planned_months: Option<i32>,
    pub linked_account_id: Option<i64>,
    pub linked_account_name: Option<String>,
    pub note: Option<String>,
    pub closed_at: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal_remaining: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_accrued: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_received: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal_repaid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_repaid: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub written_off: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub repaid_pct: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub scheduled_remaining: Option<Decimal>,
    pub installments_behind: Option<i32>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub planned_interest: Option<Decimal>,
    pub repayments: Vec<PeerLoanRepaymentResponse>,
    pub curve: Vec<CurvePoint>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanRepaymentResponse {
    pub id: i64,
    pub paid_on: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub amount: Decimal,
    pub kind: PeerLoanRepaymentKind,
    pub note: Option<String>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub interest_part: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub capital_part: Option<Decimal>,
    pub has_cash_leg: bool,
    pub cash_leg_owned: bool,
}

impl PeerLoanRepaymentResponse {
    pub fn from(repayment: &PeerLoanRepayment, split: Option<&PaymentSplit>) -> Self {
        Self {
            id: repayment.id,
            paid_on: repayment.paid_on,
            amount: repayment.amount,
            kind: repayment.kind,
            note: repayment.note.clone(),
            interest_part: split.map(|s| s.interest),
            capital_part: split.map(|s| s.capital),
            has_cash_leg: repayment.cash_transaction_id.is_some(),
            cash_leg_owned: repayment.cash_leg_owned,
        }
    }
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanSimulationResponse {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub monthly_payment: Decimal,
    pub months: i32,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_interest: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_cost: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoanOpenResponse {
    pub account_id: i64,
    pub name: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub remaining: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoanEntry {
    pub account_id: i64,
    pub name: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub principal_remaining: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub repaid_pct: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_accrued: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_received: Decimal,
    pub lent_at: NaiveDate,
    pub expected_back: Option<NaiveDate>,
    pub installments_behind: Option<i32>,
    pub last_payment_date: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub last_payment_amount: Option<Decimal>,
    pub closed_at: Option<NaiveDate>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OverviewPoint {
    pub date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub outstanding: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerLoansOverviewResponse {
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub total_outstanding: Decimal,
    pub open_count: i32,
    pub closed_count: i32,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub interest_accrued: Decimal,
    pub loans: Vec<LoanEntry>,
    pub history: Vec<OverviewPoint>,
}
