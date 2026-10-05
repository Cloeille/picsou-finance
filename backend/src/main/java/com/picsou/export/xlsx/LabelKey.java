package com.picsou.export.xlsx;

/**
 * Every heading the workbook can print, with the English wording used when the client sends
 * none.
 *
 * <p>The client supplies the localized text (see
 * {@code docs/decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md}); these defaults are
 * what keeps the endpoint self-sufficient for a caller that has no i18n catalogue of its own —
 * curl, the MCP server, an integration test.
 *
 * <p>The wire key is the enum name lowerCamelCased by the frontend; matching is done on the
 * enum name case-insensitively, so {@code accountName} and {@code ACCOUNT_NAME} both resolve.
 */
public enum LabelKey {

    // ─── Workbook / sheet structure ──────────────────────────────────────────
    SUMMARY_SHEET("Summary"),
    EXPORTED_AT("Exported at"),
    ACCOUNT_FALLBACK_NAME("Account"),

    // ─── Member profile (summary sheet) ──────────────────────────────────────
    PROFILE("Profile"),
    AGE("Age"),
    TARGET_RETIREMENT_AGE("Target retirement age"),
    MARGINAL_TAX_RATE("Marginal tax rate (%)"),
    HOUSEHOLD_STATUS("Household"),
    TAX_HOUSEHOLD_PARTS("Tax household shares"),
    DEPENDENTS("Dependents"),
    ANNUAL_GROSS_INCOME("Annual gross income"),
    MONTHLY_NET_BEFORE_TAX("Monthly net before tax"),
    WITHHOLDING_TAX_RATE("Withholding tax rate (%)"),
    MONTHLY_NET_INCOME("Monthly net income"),
    MONTHLY_SAVINGS_CAPACITY("Monthly savings capacity"),
    RISK_PROFILE("Risk profile"),

    // ─── Recurring investment plans (summary sheet) ──────────────────────────
    RECURRING_INVESTMENTS("Recurring investments"),
    SAVINGS_RATE("Savings rate (%)"),
    MONTHLY_INVESTED_TOTAL("Invested monthly"),
    PLAN_NAME("Plan"),
    PLAN_ACCOUNT("Account"),
    MONTHLY_AMOUNT("Monthly amount"),
    EXPECTED_RETURN("Expected return (%)"),
    POSITION_BREAKDOWN("Monthly position breakdown"),
    UNALLOCATED("Unallocated"),

    // ─── Debt (summary block + its own sheet) ────────────────────────────────
    DEBT_SHEET("Debts"),
    DEBTS("Debt"),
    NO_DEBT("No debt recorded"),
    DEBT_SCOPE_NOTE("Covers every loan recorded, including accounts left out of this export"),
    TOTAL_BORROWED("Total borrowed"),
    TOTAL_OUTSTANDING("Outstanding"),
    TOTAL_MONTHLY_PAYMENT("Monthly payments"),
    LOAN_ACCOUNT("Loan account"),
    PROPERTY_DEBT("Debt on this property"),

    // ─── Account header ──────────────────────────────────────────────────────
    ACCOUNT_NAME("Name"),
    ACCOUNT_TYPE("Type"),
    PROVIDER("Provider"),
    CURRENCY("Currency"),
    BALANCE("Balance"),
    BALANCE_EUR("Balance (EUR)"),
    CASH_BALANCE("Cash balance"),
    SHARE_PERCENT("Ownership share"),
    LAST_SYNCED_AT("Last synced"),
    CREATED_AT("Created"),
    OPENED_AT("Opened"),

    // ─── Positions ───────────────────────────────────────────────────────────
    POSITIONS("Positions"),
    TICKER("Ticker"),
    POSITION_NAME("Name"),
    QUANTITY("Quantity"),
    AVERAGE_BUY_IN("Average cost"),
    CURRENT_PRICE("Price"),
    QUOTE_CURRENCY("Quote currency"),
    CURRENT_VALUE_EUR("Value (EUR)"),
    COST_BASIS_EUR("Cost basis (EUR)"),
    PNL_EUR("Gain / loss (EUR)"),
    PNL_PERCENT("Gain / loss (%)"),
    PRICE_AS_OF("Price as of"),
    PRICE_STALE("Stale price"),

    // ─── Property ────────────────────────────────────────────────────────────
    PROPERTY("Property"),
    PURCHASE_PRICE("Purchase price"),
    PURCHASE_DATE("Purchase date"),
    AGENCY_FEES("Agency fees"),
    NOTARY_FEES("Notary fees"),
    WORKS_COST("Works"),
    COST_BASIS("Cost basis"),
    PROPERTY_TYPE("Property type"),
    PROPERTY_CATEGORY("Category"),
    ADDRESS("Address"),
    POSTAL_CODE("Postal code"),
    CITY("City"),
    COUNTRY("Country"),
    SURFACE_AREA("Living area (m²)"),
    LAND_AREA("Land area (m²)"),
    CONSTRUCTION_YEAR("Construction year"),
    ROOMS("Rooms"),
    ENERGY_CLASS("Energy class"),
    RENTAL_INCOME("Monthly rental income"),
    VALUATION_MODE("Valuation mode"),
    LAST_VALUED_AT("Last valued"),

    VALUATION_HISTORY("Valuation history"),
    VALUED_AT("Date"),
    ESTIMATED_VALUE("Estimate"),
    LOW_VALUE("Low"),
    HIGH_VALUE("High"),
    PRICE_PER_SQM("Price per m²"),
    VALUATION_PROVIDER("Source"),
    CONFIDENCE("Confidence"),
    SAMPLE_SIZE("Sample size"),
    SOURCE_YEAR("Source year"),

    // ─── Loan ────────────────────────────────────────────────────────────────
    LOAN("Loan"),
    LENDER("Lender"),
    BORROWED_AMOUNT("Borrowed amount"),
    INTEREST_RATE("Interest rate (%)"),
    MONTHLY_PAYMENT("Monthly payment"),
    INSURANCE_MONTHLY("Monthly insurance"),
    FILE_FEES("File fees"),
    START_DATE("Start date"),
    END_DATE("End date"),
    LINKED_ACCOUNT("Financed account"),
    REMAINING_BALANCE("Remaining balance"),
    TOTAL_INSTALLMENTS("Instalments"),
    PAID_INSTALLMENTS("Instalments paid"),
    TOTAL_INTEREST_COST("Total interest"),
    TOTAL_INSURANCE_COST("Total insurance"),
    CAPITAL_REPAID("Capital repaid"),

    AMORTIZATION("Amortization schedule"),
    INSTALLMENT_NUMBER("No."),
    INSTALLMENT_DATE("Date"),
    CAPITAL("Capital"),
    INTEREST("Interest"),
    INSURANCE("Insurance"),
    TOTAL_PAYMENT("Payment"),

    // ─── Values ──────────────────────────────────────────────────────────────
    YES("Yes"),
    NO("No");

    private final String englishDefault;

    LabelKey(String englishDefault) {
        this.englishDefault = englishDefault;
    }

    public String englishDefault() {
        return englishDefault;
    }
}
