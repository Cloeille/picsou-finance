package com.picsou.export.xlsx;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.DebtResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.model.PropertyValuation;
import com.picsou.service.LoanAmortizationService;
import com.picsou.service.LoanAmortizationService.LoanInstallment;
import com.picsou.service.LoanAmortizationService.LoanSummary;
import org.apache.poi.ss.usermodel.Sheet;

import java.math.BigDecimal;
import java.util.List;

import static com.picsou.export.xlsx.LabelKey.*;

/**
 * Writes one account's sheet: an identity block, then whichever of positions, property detail
 * and loan detail the account actually has.
 */
final class AccountSheetWriter {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SheetLabels labels;
    private final WorkbookStyles styles;

    AccountSheetWriter(SheetLabels labels, WorkbookStyles styles) {
        this.labels = labels;
        this.styles = styles;
    }

    void write(Sheet sheet, AccountExportData data) {
        sheet.setColumnWidth(0, 30 * 256);
        for (int i = 1; i <= 12; i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }

        SheetCursor cursor = new SheetCursor(sheet, styles);
        writeHeader(cursor, data.account());

        if (!data.holdings().isEmpty()) {
            writePositions(cursor, data.holdings());
        }
        if (data.account().realEstate() != null) {
            writeProperty(cursor, data.account().realEstate(), data.valuations());
            writeFinancing(cursor, data.financing());
        }
        if (data.account().debt() != null) {
            writeLoan(cursor, data.account().debt(), data.schedule());
        }
    }

    private void writeHeader(SheetCursor cursor, AccountResponse a) {
        cursor.title(a.name());
        cursor.field(label(ACCOUNT_TYPE), a.type());
        // Right under the type, where a reader looks for what kind of wrapper this is: for a PEA
        // or an assurance-vie the opening date is half of that answer, since the tax treatment
        // is a function of the plan's age. Written only when stated -- and never derived from
        // createdAt, which dates the row rather than the plan.
        if (a.openedAt() != null) {
            cursor.field(label(OPENED_AT), a.openedAt());
        }
        cursor.field(label(PROVIDER), a.provider());
        cursor.field(label(CURRENCY), a.currency());
        cursor.field(label(BALANCE), a.currentBalance());
        cursor.field(label(BALANCE_EUR), a.currentBalanceEur());
        if (a.cashBalance() != null) {
            cursor.field(label(CASH_BALANCE), a.cashBalance());
        }
        // Null rather than 100 for a wholly-owned account, and the balances above are the
        // account's full value either way -- weighting is the reader's to apply.
        if (a.sharePercent() != null) {
            cursor.fieldPercent(label(SHARE_PERCENT), a.sharePercent());
        }
        cursor.field(label(LAST_SYNCED_AT), a.lastSyncedAt());
        cursor.field(label(CREATED_AT), a.createdAt());
    }

    private void writePositions(SheetCursor cursor, List<HoldingResponse> holdings) {
        cursor.blank();
        cursor.title(label(POSITIONS));
        cursor.headerRow(List.of(
            label(TICKER), label(POSITION_NAME), label(QUANTITY), label(AVERAGE_BUY_IN),
            label(CURRENT_PRICE), label(QUOTE_CURRENCY), label(CURRENT_VALUE_EUR),
            label(COST_BASIS_EUR), label(PNL_EUR), label(PNL_PERCENT),
            label(PRICE_AS_OF), label(PRICE_STALE)
        ));

        for (HoldingResponse h : holdings) {
            SheetCursor.RowCursor row = cursor.row();
            row.text(h.ticker());
            row.text(h.name());
            row.quantity(h.quantity());
            row.quantity(h.averageBuyIn());
            row.quantity(h.currentPrice());
            row.text(h.quoteCurrency());
            row.money(h.currentValueEur());
            row.money(h.costBasisEur());
            row.money(h.pnlEur());
            // Already out of 100 (AccountService multiplies by 100), so it goes in as-is under
            // a "#,##0.00 %" format. Excel's own 0.00% would rescale it a second time.
            row.percent(h.pnlPercent());
            row.date(h.priceAsOf());
            row.text(bool(h.priceStale()));
        }
    }

    private void writeProperty(SheetCursor cursor, RealEstateMetadataResponse re,
                               List<PropertyValuation> valuations) {
        cursor.blank();
        cursor.title(label(PROPERTY));
        cursor.field(label(PURCHASE_PRICE), re.purchasePrice());
        cursor.field(label(PURCHASE_DATE), re.purchaseDate());
        cursor.field(label(AGENCY_FEES), re.agencyFees());
        cursor.field(label(NOTARY_FEES), re.notaryFees());
        cursor.field(label(WORKS_COST), re.worksCost());
        cursor.field(label(COST_BASIS), re.costBasis());
        cursor.field(label(PROPERTY_TYPE), re.propertyKind() != null ? re.propertyKind() : re.propertyType());
        cursor.field(label(PROPERTY_CATEGORY), re.category());
        cursor.field(label(ADDRESS), re.address());
        cursor.field(label(POSTAL_CODE), re.postalCode());
        cursor.field(label(CITY), re.city());
        cursor.field(label(COUNTRY), re.country());
        cursor.field(label(SURFACE_AREA), re.surfaceArea());
        cursor.field(label(LAND_AREA), re.landArea());
        cursor.field(label(CONSTRUCTION_YEAR), re.constructionYear());
        cursor.field(label(ROOMS), re.rooms());
        cursor.field(label(ENERGY_CLASS), re.energyClass());
        cursor.field(label(RENTAL_INCOME), re.rentalIncome());
        cursor.field(label(VALUATION_MODE), re.valuationMode());
        cursor.field(label(LAST_VALUED_AT), re.lastValuedAt());

        if (valuations.isEmpty()) return;

        cursor.blank();
        cursor.title(label(VALUATION_HISTORY));
        cursor.headerRow(List.of(
            label(VALUED_AT), label(ESTIMATED_VALUE), label(LOW_VALUE), label(HIGH_VALUE),
            label(PRICE_PER_SQM), label(VALUATION_PROVIDER), label(CONFIDENCE),
            label(SAMPLE_SIZE), label(SOURCE_YEAR)
        ));
        for (PropertyValuation v : valuations) {
            SheetCursor.RowCursor row = cursor.row();
            row.date(v.getValuedAt());
            row.money(v.getEstimatedValue());
            row.money(v.getLowValue());
            row.money(v.getHighValue());
            row.money(v.getPricePerSqm());
            row.text(v.getProvider());
            row.text(v.getConfidence() == null ? null : v.getConfidence().name());
            row.integer(v.getSampleSize());
            row.integer(v.getSourceYear());
        }
    }

    /**
     * What is still owed on this property — **written even when nothing is**, as an explicit
     * zero.
     *
     * <p>A property sheet with no debt line reads as a property whose financing nobody recorded.
     * A property that is genuinely owned outright is a different statement, and it is one this
     * sheet should be able to make.
     *
     * <p>The loans come from the member's whole set, not the export's selection: a mortgage
     * whose loan account was left unticked would otherwise turn a financed property into a
     * debt-free one.
     */
    private void writeFinancing(SheetCursor cursor, List<DebtExportData> financing) {
        BigDecimal outstanding = financing.stream()
            .map(DebtExportData::outstanding)
            .map(v -> v == null ? BigDecimal.ZERO : v)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        cursor.blank();
        cursor.field(label(PROPERTY_DEBT), outstanding);
        if (financing.isEmpty()) return;

        cursor.headerRow(List.of(
            label(LOAN_ACCOUNT), label(LENDER), label(BORROWED_AMOUNT),
            label(REMAINING_BALANCE), label(MONTHLY_PAYMENT), label(END_DATE)
        ));
        for (DebtExportData d : financing) {
            SheetCursor.RowCursor row = cursor.row();
            row.text(d.loanAccountName());
            row.text(d.debt().getLenderName());
            row.money(d.debt().getBorrowedAmount());
            row.money(d.outstanding());
            row.money(d.debt().getMonthlyPayment());
            row.date(d.debt().getEndDate());
        }
    }

    private void writeLoan(SheetCursor cursor, DebtResponse debt,
                           LoanAmortizationService.LoanScheduleResponse schedule) {
        cursor.blank();
        cursor.title(label(LOAN));
        cursor.field(label(LENDER), debt.lenderName());
        cursor.field(label(BORROWED_AMOUNT), debt.borrowedAmount());
        // Stored as a ratio (0.0325); the sheet says "(%)", so it goes out out of 100.
        cursor.fieldPercent(label(INTEREST_RATE),
            debt.interestRate() == null ? null : debt.interestRate().multiply(HUNDRED));
        cursor.field(label(MONTHLY_PAYMENT), debt.monthlyPayment());
        cursor.field(label(INSURANCE_MONTHLY), debt.insuranceMonthly());
        cursor.field(label(FILE_FEES), debt.fileFees());
        cursor.field(label(START_DATE), debt.startDate());
        cursor.field(label(END_DATE), debt.endDate());
        cursor.field(label(LINKED_ACCOUNT), debt.linkedAccountName());

        if (schedule == null) return;

        LoanSummary s = schedule.summary();
        cursor.field(label(REMAINING_BALANCE), s.remainingBalance());
        cursor.field(label(TOTAL_INSTALLMENTS), s.totalInstallments());
        cursor.field(label(PAID_INSTALLMENTS), s.paidInstallments());
        cursor.field(label(TOTAL_INTEREST_COST), s.totalInterestCost());
        cursor.field(label(TOTAL_INSURANCE_COST), s.totalInsuranceCost());
        cursor.field(label(CAPITAL_REPAID), s.capitalRepaid());

        if (schedule.schedule().isEmpty()) return;

        cursor.blank();
        cursor.title(label(AMORTIZATION));
        cursor.headerRow(List.of(
            label(INSTALLMENT_NUMBER), label(INSTALLMENT_DATE), label(CAPITAL), label(INTEREST),
            label(INSURANCE), label(TOTAL_PAYMENT), label(REMAINING_BALANCE)
        ));
        for (LoanInstallment i : schedule.schedule()) {
            SheetCursor.RowCursor row = cursor.row();
            row.integer(i.number());
            row.date(i.date());
            row.money(i.capital());
            row.money(i.interest());
            row.money(i.insurance());
            row.money(i.totalPayment());
            row.money(i.remainingBalance());
        }
    }

    private String label(LabelKey key) {
        return labels.get(key);
    }

    private String bool(boolean value) {
        return label(value ? YES : NO);
    }
}
