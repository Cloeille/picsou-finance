//! The headings the workbook prints (LabelKey, SheetLabels): the client sends the localized
//! wording, anything missing falls back to the English default.

use std::collections::HashMap;

macro_rules! label_keys {
    ($($variant:ident = ($name:literal, $english:literal),)+) => {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
        pub enum LabelKey {
            $($variant),+
        }

        impl LabelKey {
            pub const ALL: &'static [LabelKey] = &[$(LabelKey::$variant),+];

            pub fn name(self) -> &'static str {
                match self {
                    $(LabelKey::$variant => $name),+
                }
            }

            pub fn english_default(self) -> &'static str {
                match self {
                    $(LabelKey::$variant => $english),+
                }
            }
        }
    };
}

label_keys! {
    SummarySheet = ("SUMMARY_SHEET", "Summary"),
    ExportedAt = ("EXPORTED_AT", "Exported at"),
    AccountFallbackName = ("ACCOUNT_FALLBACK_NAME", "Account"),
    Profile = ("PROFILE", "Profile"),
    Age = ("AGE", "Age"),
    TargetRetirementAge = ("TARGET_RETIREMENT_AGE", "Target retirement age"),
    MarginalTaxRate = ("MARGINAL_TAX_RATE", "Marginal tax rate (%)"),
    HouseholdStatus = ("HOUSEHOLD_STATUS", "Household"),
    TaxHouseholdParts = ("TAX_HOUSEHOLD_PARTS", "Tax household shares"),
    Dependents = ("DEPENDENTS", "Dependents"),
    AnnualGrossIncome = ("ANNUAL_GROSS_INCOME", "Annual gross income"),
    MonthlyNetBeforeTax = ("MONTHLY_NET_BEFORE_TAX", "Monthly net before tax"),
    WithholdingTaxRate = ("WITHHOLDING_TAX_RATE", "Withholding tax rate (%)"),
    MonthlyNetIncome = ("MONTHLY_NET_INCOME", "Monthly net income"),
    MonthlySavingsCapacity = ("MONTHLY_SAVINGS_CAPACITY", "Monthly savings capacity"),
    RiskProfile = ("RISK_PROFILE", "Risk profile"),
    RecurringInvestments = ("RECURRING_INVESTMENTS", "Recurring investments"),
    SavingsRate = ("SAVINGS_RATE", "Savings rate (%)"),
    MonthlyInvestedTotal = ("MONTHLY_INVESTED_TOTAL", "Invested monthly"),
    PlanName = ("PLAN_NAME", "Plan"),
    PlanAccount = ("PLAN_ACCOUNT", "Account"),
    MonthlyAmount = ("MONTHLY_AMOUNT", "Monthly amount"),
    ExpectedReturn = ("EXPECTED_RETURN", "Expected return (%)"),
    PositionBreakdown = ("POSITION_BREAKDOWN", "Monthly position breakdown"),
    Unallocated = ("UNALLOCATED", "Unallocated"),
    DebtSheet = ("DEBT_SHEET", "Debts"),
    Debts = ("DEBTS", "Debt"),
    NoDebt = ("NO_DEBT", "No debt recorded"),
    DebtScopeNote = ("DEBT_SCOPE_NOTE", "Covers every loan recorded, including accounts left out of this export"),
    TotalBorrowed = ("TOTAL_BORROWED", "Total borrowed"),
    TotalOutstanding = ("TOTAL_OUTSTANDING", "Outstanding"),
    TotalMonthlyPayment = ("TOTAL_MONTHLY_PAYMENT", "Monthly payments"),
    LoanAccount = ("LOAN_ACCOUNT", "Loan account"),
    PropertyDebt = ("PROPERTY_DEBT", "Debt on this property"),
    AccountName = ("ACCOUNT_NAME", "Name"),
    AccountType = ("ACCOUNT_TYPE", "Type"),
    Provider = ("PROVIDER", "Provider"),
    Currency = ("CURRENCY", "Currency"),
    Balance = ("BALANCE", "Balance"),
    BalanceEur = ("BALANCE_EUR", "Balance (EUR)"),
    CashBalance = ("CASH_BALANCE", "Cash balance"),
    SharePercent = ("SHARE_PERCENT", "Ownership share"),
    LastSyncedAt = ("LAST_SYNCED_AT", "Last synced"),
    CreatedAt = ("CREATED_AT", "Created"),
    OpenedAt = ("OPENED_AT", "Opened"),
    Positions = ("POSITIONS", "Positions"),
    Ticker = ("TICKER", "Ticker"),
    PositionName = ("POSITION_NAME", "Name"),
    Quantity = ("QUANTITY", "Quantity"),
    AverageBuyIn = ("AVERAGE_BUY_IN", "Average cost"),
    CurrentPrice = ("CURRENT_PRICE", "Price"),
    QuoteCurrency = ("QUOTE_CURRENCY", "Quote currency"),
    CurrentValueEur = ("CURRENT_VALUE_EUR", "Value (EUR)"),
    CostBasisEur = ("COST_BASIS_EUR", "Cost basis (EUR)"),
    PnlEur = ("PNL_EUR", "Gain / loss (EUR)"),
    PnlPercent = ("PNL_PERCENT", "Gain / loss (%)"),
    PriceAsOf = ("PRICE_AS_OF", "Price as of"),
    PriceStale = ("PRICE_STALE", "Stale price"),
    Property = ("PROPERTY", "Property"),
    PurchasePrice = ("PURCHASE_PRICE", "Purchase price"),
    PurchaseDate = ("PURCHASE_DATE", "Purchase date"),
    AgencyFees = ("AGENCY_FEES", "Agency fees"),
    NotaryFees = ("NOTARY_FEES", "Notary fees"),
    WorksCost = ("WORKS_COST", "Works"),
    CostBasis = ("COST_BASIS", "Cost basis"),
    PropertyType = ("PROPERTY_TYPE", "Property type"),
    PropertyCategory = ("PROPERTY_CATEGORY", "Category"),
    Address = ("ADDRESS", "Address"),
    PostalCode = ("POSTAL_CODE", "Postal code"),
    City = ("CITY", "City"),
    Country = ("COUNTRY", "Country"),
    SurfaceArea = ("SURFACE_AREA", "Living area (m²)"),
    LandArea = ("LAND_AREA", "Land area (m²)"),
    ConstructionYear = ("CONSTRUCTION_YEAR", "Construction year"),
    Rooms = ("ROOMS", "Rooms"),
    EnergyClass = ("ENERGY_CLASS", "Energy class"),
    RentalIncome = ("RENTAL_INCOME", "Monthly rental income"),
    ValuationMode = ("VALUATION_MODE", "Valuation mode"),
    LastValuedAt = ("LAST_VALUED_AT", "Last valued"),
    ValuationHistory = ("VALUATION_HISTORY", "Valuation history"),
    ValuedAt = ("VALUED_AT", "Date"),
    EstimatedValue = ("ESTIMATED_VALUE", "Estimate"),
    LowValue = ("LOW_VALUE", "Low"),
    HighValue = ("HIGH_VALUE", "High"),
    PricePerSqm = ("PRICE_PER_SQM", "Price per m²"),
    ValuationProvider = ("VALUATION_PROVIDER", "Source"),
    Confidence = ("CONFIDENCE", "Confidence"),
    SampleSize = ("SAMPLE_SIZE", "Sample size"),
    SourceYear = ("SOURCE_YEAR", "Source year"),
    Loan = ("LOAN", "Loan"),
    Lender = ("LENDER", "Lender"),
    BorrowedAmount = ("BORROWED_AMOUNT", "Borrowed amount"),
    InterestRate = ("INTEREST_RATE", "Interest rate (%)"),
    MonthlyPayment = ("MONTHLY_PAYMENT", "Monthly payment"),
    InsuranceMonthly = ("INSURANCE_MONTHLY", "Monthly insurance"),
    FileFees = ("FILE_FEES", "File fees"),
    StartDate = ("START_DATE", "Start date"),
    EndDate = ("END_DATE", "End date"),
    LinkedAccount = ("LINKED_ACCOUNT", "Financed account"),
    RemainingBalance = ("REMAINING_BALANCE", "Remaining balance"),
    TotalInstallments = ("TOTAL_INSTALLMENTS", "Instalments"),
    PaidInstallments = ("PAID_INSTALLMENTS", "Instalments paid"),
    TotalInterestCost = ("TOTAL_INTEREST_COST", "Total interest"),
    TotalInsuranceCost = ("TOTAL_INSURANCE_COST", "Total insurance"),
    CapitalRepaid = ("CAPITAL_REPAID", "Capital repaid"),
    Amortization = ("AMORTIZATION", "Amortization schedule"),
    InstallmentNumber = ("INSTALLMENT_NUMBER", "No."),
    InstallmentDate = ("INSTALLMENT_DATE", "Date"),
    Capital = ("CAPITAL", "Capital"),
    Interest = ("INTEREST", "Interest"),
    Insurance = ("INSURANCE", "Insurance"),
    TotalPayment = ("TOTAL_PAYMENT", "Payment"),
    PeerLoan = ("PEER_LOAN", "Peer loan"),
    Borrower = ("BORROWER", "Borrower"),
    LentAt = ("LENT_AT", "Lent on"),
    ExpectedBack = ("EXPECTED_BACK", "Expected back"),
    RatePeriod = ("RATE_PERIOD", "Rate period"),
    PlannedMonthly = ("PLANNED_MONTHLY", "Planned monthly"),
    PlannedMonths = ("PLANNED_MONTHS", "Planned months"),
    PlannedInterest = ("PLANNED_INTEREST", "Planned interest"),
    Principal = ("PRINCIPAL", "Principal"),
    PrincipalRemaining = ("PRINCIPAL_REMAINING", "Remaining capital"),
    InterestAccrued = ("INTEREST_ACCRUED", "Interest accrued"),
    InterestReceived = ("INTEREST_RECEIVED", "Interest received"),
    WrittenOff = ("WRITTEN_OFF", "Written off"),
    ClosedAt = ("CLOSED_AT", "Closed on"),
    CashAccount = ("CASH_ACCOUNT", "Cash account"),
    RepaymentJournal = ("REPAYMENT_JOURNAL", "Repayment journal"),
    PaidOn = ("PAID_ON", "Paid on"),
    Amount = ("AMOUNT", "Amount"),
    Kind = ("KIND", "Kind"),
    Note = ("NOTE", "Note"),
    Yes = ("YES", "Yes"),
    No = ("NO", "No"),
}

/// Long enough for any real heading, short enough that a hostile payload cannot bloat cells.
const MAX_LENGTH: usize = 120;

pub struct SheetLabels(HashMap<LabelKey, String>);

impl SheetLabels {
    /// Keys match the enum name in any case, with or without underscores and dashes; unknown
    /// keys are ignored.
    pub fn of(supplied: Option<&serde_json::Map<String, serde_json::Value>>) -> Self {
        let normalized: HashMap<String, Option<&str>> = supplied
            .into_iter()
            .flatten()
            .map(|(k, v)| (normalize_key(k), v.as_str()))
            .collect();
        Self(
            LabelKey::ALL
                .iter()
                .map(|key| {
                    let value = normalized
                        .get(&normalize_key(key.name()))
                        .copied()
                        .flatten()
                        .and_then(sanitize)
                        .unwrap_or_else(|| key.english_default().to_owned());
                    (*key, value)
                })
                .collect(),
        )
    }

    pub fn get(&self, key: LabelKey) -> &str {
        &self.0[&key]
    }
}

fn normalize_key(raw: &str) -> String {
    raw.replace(['_', '-'], "").to_lowercase()
}

fn sanitize(raw: &str) -> Option<String> {
    let mut out = String::new();
    let mut length = 0;
    for c in raw.chars() {
        if length >= MAX_LENGTH {
            break;
        }
        if !c.is_control() {
            out.push(c);
            length += c.len_utf16();
        }
    }
    let trimmed = crate::ledger::sync_values::java_trim(&out);
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn supplied_labels_win_and_are_sanitized() {
        let supplied = serde_json::json!({"accountName": " Nom\n ", "SHARE-percent": "Part", "date": "x", "balance": "  "});
        let labels = SheetLabels::of(supplied.as_object());
        assert_eq!(labels.get(LabelKey::AccountName), "Nom");
        assert_eq!(labels.get(LabelKey::SharePercent), "Part");
        assert_eq!(labels.get(LabelKey::Balance), "Balance");
        assert_eq!(labels.get(LabelKey::SummarySheet), "Summary");
    }
}
