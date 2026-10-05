package com.picsou.export.xlsx;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.GoalAllocationResponse;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.MemberProfileResponse;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.model.GoalType;
import com.picsou.model.PropertyValuation;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.service.AccountService;
import com.picsou.service.GoalService;
import com.picsou.service.LoanAmortizationService;
import com.picsou.service.LoanAmortizationService.LoanSummary;
import com.picsou.service.MemberProfileService;
import com.picsou.service.SavingsRateCalculator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import static com.picsou.export.xlsx.LabelKey.*;

/**
 * Builds the "one sheet per account" spreadsheet: a summary sheet, then each selected account's
 * identity, positions and property / loan detail.
 *
 * <p>Distinct from {@code DataExportService}, which answers the GDPR question — everything about
 * one user, flattened to CSV and JSON. This answers "let me analyse these accounts in a
 * spreadsheet", so the shape is per-account and the figures are typed cells.
 *
 * <p>Every read goes through the member-scoped path
 * ({@link AccountService#findById(Long, Long)} raises for an account the member may not read),
 * so an id from outside the caller's perimeter never reaches a sheet.
 */
@Service
public class AccountsWorkbookService {

    private static final Logger log = LoggerFactory.getLogger(AccountsWorkbookService.class);

    /** Rows kept in memory per sheet; the rest spill to a temp file. */
    private static final int ROW_ACCESS_WINDOW = 200;

    /** Excel's own cap. {@code createSafeSheetName} enforces it; the dedup suffix must respect it too. */
    private static final int MAX_SHEET_NAME = 31;

    private final AccountService accountService;
    private final PropertyValuationRepository valuationRepository;
    private final DebtRepository debtRepository;
    private final LoanAmortizationService loanAmortizationService;
    private final MemberProfileService memberProfileService;
    private final GoalService goalService;
    private final SavingsRateCalculator savingsRateCalculator;

    public AccountsWorkbookService(AccountService accountService,
                                   PropertyValuationRepository valuationRepository,
                                   DebtRepository debtRepository,
                                   LoanAmortizationService loanAmortizationService,
                                   MemberProfileService memberProfileService,
                                   GoalService goalService,
                                   SavingsRateCalculator savingsRateCalculator) {
        this.accountService = accountService;
        this.valuationRepository = valuationRepository;
        this.debtRepository = debtRepository;
        this.loanAmortizationService = loanAmortizationService;
        this.memberProfileService = memberProfileService;
        this.goalService = goalService;
        this.savingsRateCalculator = savingsRateCalculator;
    }

    /**
     * Streams the workbook for {@code accountIds} into {@code out}.
     *
     * <p>Read-only, but transactional so the lazy associations behind the loan and property
     * lookups resolve on one connection instead of one per account.
     */
    @Transactional(readOnly = true)
    public void export(List<Long> accountIds, Long memberId, SheetLabels labels, OutputStream out)
        throws IOException {

        List<AccountExportData> data = accountIds.stream()
            .distinct()
            .map(id -> gather(id, memberId))
            .toList();

        SXSSFWorkbook wb = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        try {
            WorkbookStyles styles = new WorkbookStyles(wb);
            // Every loan the member has, not only those among the selected accounts. A property
            // is financed by a loan the reader may not have ticked, and a "0" that means "you
            // did not select it" is exactly the misreading a debt figure must not produce.
            List<DebtExportData> debts = gatherDebts(memberId);

            writeSummarySheet(wb, styles, labels, data,
                memberProfileService.get(memberId), goalService.findAll(memberId), debts);
            writeDebtSheet(wb, styles, labels, debts);

            AccountSheetWriter writer = new AccountSheetWriter(labels, styles);
            // Seeded with the two fixed sheets: an account genuinely called "Summary" would
            // otherwise reach createSheet with a name already taken, and POI throws on a
            // duplicate rather than degrading.
            Set<String> used = new HashSet<>(Set.of(
                safeName(labels.get(SUMMARY_SHEET), "Summary").toLowerCase(Locale.ROOT),
                safeName(labels.get(DEBT_SHEET), "Debts").toLowerCase(Locale.ROOT)));
            for (AccountExportData d : data) {
                Sheet sheet = wb.createSheet(uniqueSheetName(d.account(), labels, used));
                writer.write(sheet, d);
            }
            wb.write(out);
        } finally {
            // SXSSF spills rows to temp files; without this they outlive the request.
            wb.dispose();
            wb.close();
        }
    }

    private AccountExportData gather(Long accountId, Long memberId) {
        AccountResponse account = accountService.findById(accountId, memberId);

        List<HoldingResponse> holdings = accountService.getHoldings(accountId, memberId);

        List<PropertyValuation> valuations = account.type() == AccountType.REAL_ESTATE
            ? valuationRepository.findByAccountIdOrderByValuedAtDesc(accountId)
            : List.of();

        LoanAmortizationService.LoanScheduleResponse schedule = null;
        if (account.type() == AccountType.LOAN) {
            Debt debt = debtRepository.findByAccountId(accountId).orElse(null);
            // A LOAN account can exist with no Debt row behind it (typed in, never detailed);
            // the sheet then shows whatever DebtResponse carried and no schedule.
            if (debt != null) {
                schedule = loanAmortizationService.compute(debt);
            } else {
                log.debug("accounts_export.loan_without_debt accountId={}", accountId);
            }
        }

        return new AccountExportData(account, holdings, valuations, schedule,
            debtRepository.findByLinkedAccountId(accountId).stream().map(this::gatherDebt).toList());
    }

    private List<DebtExportData> gatherDebts(Long memberId) {
        return debtRepository.findAllByMemberId(memberId).stream().map(this::gatherDebt).toList();
    }

    private DebtExportData gatherDebt(Debt debt) {
        return new DebtExportData(debt, loanAmortizationService.compute(debt));
    }

    private void writeSummarySheet(SXSSFWorkbook wb, WorkbookStyles styles, SheetLabels labels,
                                   List<AccountExportData> data, MemberProfileResponse profile,
                                   List<GoalProgressResponse> goals, List<DebtExportData> debts) {
        Sheet sheet = wb.createSheet(safeName(labels.get(SUMMARY_SHEET), "Summary"));
        sheet.setColumnWidth(0, 34 * 256);
        for (int i = 1; i <= 7; i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }

        SheetCursor cursor = new SheetCursor(sheet, styles);
        cursor.field(labels.get(EXPORTED_AT), Instant.now());

        // Who the figures belong to, then what they are committed to every month, then the
        // accounts themselves. A portfolio read without knowing the reader's age or bracket is a
        // list of numbers; this is the context that makes it a situation.
        writeProfileBlock(cursor, labels, profile);
        writeRecurringPlansBlock(cursor, labels, profile, goals);
        writeDebtBlock(cursor, labels, debts);

        cursor.blank();
        cursor.headerRow(List.of(
            labels.get(ACCOUNT_NAME), labels.get(ACCOUNT_TYPE), labels.get(PROVIDER),
            labels.get(CURRENCY), labels.get(BALANCE), labels.get(BALANCE_EUR),
            labels.get(SHARE_PERCENT), labels.get(LAST_SYNCED_AT)
        ));
        for (AccountExportData d : data) {
            AccountResponse a = d.account();
            SheetCursor.RowCursor row = cursor.row();
            row.text(a.name());
            row.text(a.type() == null ? null : a.type().name());
            row.text(a.provider());
            row.text(a.currency());
            row.money(a.currentBalance());
            row.money(a.currentBalanceEur());
            row.percent(a.sharePercent());
            row.dateTime(a.lastSyncedAt());
        }
    }

    /**
     * The member's personal and fiscal context.
     *
     * <p>Every field is skipped when unstated, and the block disappears entirely when nothing is:
     * a column of "Age: " with nothing after it says less than no column at all, and this table
     * is read by a person rather than parsed.
     *
     * <p>The birth date is deliberately not written. The age is the figure that bears on a
     * portfolio; the date is personal data with nothing further to say, and an export travels.
     */
    private void writeProfileBlock(SheetCursor cursor, SheetLabels labels,
                                   MemberProfileResponse profile) {
        List<Map.Entry<String, Object>> fields = new ArrayList<>();
        addIfPresent(fields, labels.get(AGE), profile.age());
        addIfPresent(fields, labels.get(TARGET_RETIREMENT_AGE), profile.targetRetirementAge());
        addIfPresent(fields, labels.get(HOUSEHOLD_STATUS), profile.householdStatus());
        addIfPresent(fields, labels.get(TAX_HOUSEHOLD_PARTS), profile.taxHouseholdParts());
        addIfPresent(fields, labels.get(DEPENDENTS), profile.dependents());
        addIfPresent(fields, labels.get(ANNUAL_GROSS_INCOME), profile.annualGrossIncome());
        addIfPresent(fields, labels.get(MONTHLY_NET_BEFORE_TAX), profile.monthlyNetBeforeTax());
        addIfPresent(fields, labels.get(MONTHLY_NET_INCOME), profile.monthlyNetIncome());
        addIfPresent(fields, labels.get(MONTHLY_SAVINGS_CAPACITY), profile.monthlySavingsCapacity());
        addIfPresent(fields, labels.get(RISK_PROFILE), profile.riskProfile());

        boolean hasRates = profile.marginalTaxRate() != null || profile.withholdingTaxRate() != null;
        if (fields.isEmpty() && !hasRates) return;

        cursor.blank();
        cursor.title(labels.get(PROFILE));
        for (Map.Entry<String, Object> field : fields) {
            cursor.field(field.getKey(), field.getValue());
        }
        // Written through fieldPercent, never as a plain number: both are already out of 100, and
        // Excel's own percent format would rescale them to 3000 %. Same trap as pnlPercent.
        if (profile.marginalTaxRate() != null) {
            cursor.fieldPercent(labels.get(MARGINAL_TAX_RATE), profile.marginalTaxRate());
        }
        if (profile.withholdingTaxRate() != null) {
            cursor.fieldPercent(labels.get(WITHHOLDING_TAX_RATE), profile.withholdingTaxRate());
        }
    }

    /**
     * What goes out every month, into which account, and — where the member has said so — into
     * which positions.
     *
     * <p>Savings targets are left out: the columns here are a monthly amount and a split, and a
     * goal with a deadline has neither. They remain in the GDPR export's {@code goals.csv}.
     */
    private void writeRecurringPlansBlock(SheetCursor cursor, SheetLabels labels,
                                          MemberProfileResponse profile,
                                          List<GoalProgressResponse> goals) {
        List<GoalProgressResponse> plans = goals.stream()
            .filter(g -> g.type() == GoalType.RECURRING_INVESTMENT)
            .toList();
        if (plans.isEmpty()) return;

        BigDecimal monthly = savingsRateCalculator.monthlyContributions(goals, LocalDate.now());
        BigDecimal rate = savingsRateCalculator.savingsRate(monthly, profile.monthlyNetIncome());

        cursor.blank();
        cursor.title(labels.get(RECURRING_INVESTMENTS));
        cursor.field(labels.get(MONTHLY_INVESTED_TOTAL), monthly);
        // Null when no net income is stated. The row is dropped rather than showing a zero, which
        // would read as "saves nothing" instead of "we were not told what they earn".
        if (rate != null) {
            cursor.fieldPercent(labels.get(SAVINGS_RATE), rate);
        }

        cursor.blank();
        cursor.headerRow(List.of(
            labels.get(PLAN_NAME), labels.get(PLAN_ACCOUNT), labels.get(MONTHLY_AMOUNT),
            labels.get(EXPECTED_RETURN), labels.get(START_DATE), labels.get(END_DATE)
        ));
        for (GoalProgressResponse plan : plans) {
            SheetCursor.RowCursor row = cursor.row();
            row.text(plan.name());
            row.text(accountNameOf(plan));
            row.money(plan.monthlyAmount());
            row.percent(plan.expectedReturn());
            row.date(plan.startDate());
            row.date(plan.endDate());
        }

        writeAllocationBlock(cursor, labels, plans);
    }

    /** One row per detailed line, plus the remainder a plan has not allocated. */
    private void writeAllocationBlock(SheetCursor cursor, SheetLabels labels,
                                      List<GoalProgressResponse> plans) {
        if (plans.stream().allMatch(p -> p.allocations().isEmpty())) return;

        cursor.blank();
        cursor.title(labels.get(POSITION_BREAKDOWN));
        cursor.headerRow(List.of(
            labels.get(PLAN_NAME), labels.get(PLAN_ACCOUNT), labels.get(TICKER),
            labels.get(POSITION_NAME), labels.get(MONTHLY_AMOUNT)
        ));
        for (GoalProgressResponse plan : plans) {
            if (plan.allocations().isEmpty()) continue;
            BigDecimal allocated = BigDecimal.ZERO;
            for (GoalAllocationResponse line : plan.allocations()) {
                SheetCursor.RowCursor row = cursor.row();
                row.text(plan.name());
                row.text(accountNameOf(plan));
                row.text(line.ticker());
                row.text(line.name());
                row.money(line.monthlyAmount());
                allocated = allocated.add(line.monthlyAmount());
            }
            // A split may cover only part of the amount. Stating the remainder is what keeps the
            // rows above from silently failing to add up to the plan's own line.
            BigDecimal remainder = plan.monthlyAmount() == null
                ? BigDecimal.ZERO
                : plan.monthlyAmount().subtract(allocated);
            if (remainder.signum() > 0) {
                SheetCursor.RowCursor row = cursor.row();
                row.text(plan.name());
                row.text(accountNameOf(plan));
                row.text(labels.get(UNALLOCATED));
                row.text(null);
                row.money(remainder);
            }
        }
    }

    /**
     * What is owed, in the summary — **always written, including when there is none**.
     *
     * <p>An absent block is indistinguishable from a block nobody thought to add, and a reader
     * asking "does this person have a mortgage?" needs the answer either way. So the totals are
     * printed at zero and the fact is stated in words.
     */
    private void writeDebtBlock(SheetCursor cursor, SheetLabels labels, List<DebtExportData> debts) {
        cursor.blank();
        cursor.title(labels.get(DEBTS));

        if (debts.isEmpty()) {
            cursor.field(labels.get(NO_DEBT), null);
            cursor.field(labels.get(TOTAL_OUTSTANDING), BigDecimal.ZERO);
            return;
        }

        cursor.field(labels.get(TOTAL_BORROWED), sum(debts, d -> d.debt().getBorrowedAmount()));
        cursor.field(labels.get(TOTAL_OUTSTANDING), sum(debts, DebtExportData::outstanding));
        cursor.field(labels.get(TOTAL_MONTHLY_PAYMENT),
            sum(debts, d -> nz(d.debt().getMonthlyPayment()).add(nz(d.debt().getInsuranceMonthly()))));

        cursor.blank();
        cursor.headerRow(List.of(
            labels.get(LOAN_ACCOUNT), labels.get(LENDER), labels.get(LINKED_ACCOUNT),
            labels.get(BORROWED_AMOUNT), labels.get(REMAINING_BALANCE),
            labels.get(MONTHLY_PAYMENT), labels.get(INTEREST_RATE), labels.get(END_DATE)
        ));
        for (DebtExportData d : debts) {
            SheetCursor.RowCursor row = cursor.row();
            row.text(d.loanAccountName());
            row.text(d.debt().getLenderName());
            row.text(d.financedAccountName());
            row.money(d.debt().getBorrowedAmount());
            row.money(d.outstanding());
            row.money(d.debt().getMonthlyPayment());
            row.percent(ratePercent(d.debt().getInterestRate()));
            row.date(d.debt().getEndDate());
        }
    }

    /**
     * The dedicated debt sheet — created whether or not there is any debt, for the same reason
     * the summary block is: a missing sheet answers nothing.
     *
     * <p>It carries the terms and the figures derived from them, but not the instalment rows;
     * those are on the loan's own account sheet, and repeating a 25-year schedule here would
     * multiply the file for a table already present.
     */
    private void writeDebtSheet(SXSSFWorkbook wb, WorkbookStyles styles, SheetLabels labels,
                                List<DebtExportData> debts) {
        Sheet sheet = wb.createSheet(safeName(labels.get(DEBT_SHEET), "Debts"));
        sheet.setColumnWidth(0, 30 * 256);
        for (int i = 1; i <= 14; i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }

        SheetCursor cursor = new SheetCursor(sheet, styles);
        cursor.title(labels.get(DEBTS));
        cursor.field(labels.get(DEBT_SCOPE_NOTE), null);

        if (debts.isEmpty()) {
            cursor.blank();
            cursor.field(labels.get(NO_DEBT), null);
            cursor.field(labels.get(TOTAL_OUTSTANDING), BigDecimal.ZERO);
            return;
        }

        cursor.blank();
        cursor.headerRow(List.of(
            labels.get(LOAN_ACCOUNT), labels.get(LENDER), labels.get(LINKED_ACCOUNT),
            labels.get(BORROWED_AMOUNT), labels.get(REMAINING_BALANCE),
            labels.get(INTEREST_RATE), labels.get(MONTHLY_PAYMENT),
            labels.get(INSURANCE_MONTHLY), labels.get(FILE_FEES),
            labels.get(START_DATE), labels.get(END_DATE),
            labels.get(PAID_INSTALLMENTS), labels.get(TOTAL_INSTALLMENTS),
            labels.get(TOTAL_INTEREST_COST), labels.get(TOTAL_INSURANCE_COST)
        ));
        for (DebtExportData d : debts) {
            LoanSummary summary = d.schedule() == null ? null : d.schedule().summary();
            SheetCursor.RowCursor row = cursor.row();
            row.text(d.loanAccountName());
            row.text(d.debt().getLenderName());
            row.text(d.financedAccountName());
            row.money(d.debt().getBorrowedAmount());
            row.money(d.outstanding());
            row.percent(ratePercent(d.debt().getInterestRate()));
            row.money(d.debt().getMonthlyPayment());
            row.money(d.debt().getInsuranceMonthly());
            row.money(d.debt().getFileFees());
            row.date(d.debt().getStartDate());
            row.date(d.debt().getEndDate());
            row.integer(summary == null ? null : summary.paidInstallments());
            row.integer(summary == null ? null : summary.totalInstallments());
            row.money(summary == null ? null : summary.totalInterestCost());
            row.money(summary == null ? null : summary.totalInsuranceCost());
        }
    }

    /**
     * {@code Debt.interestRate} is stored as a ratio (0.0325); the column says (%).
     *
     * <p>Multiplied here rather than formatted as a percentage by Excel, which would rescale it
     * a second time — the same trap {@code pnlPercent} carries.
     */
    private static BigDecimal ratePercent(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(new BigDecimal("100"));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal sum(List<DebtExportData> debts,
                                  java.util.function.Function<DebtExportData, BigDecimal> field) {
        return debts.stream().map(field).map(AccountsWorkbookService::nz)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A recurring plan funds exactly one account, but an empty list must not throw here. */
    private static String accountNameOf(GoalProgressResponse plan) {
        return plan.accounts().isEmpty() ? null : plan.accounts().getFirst().name();
    }

    private static void addIfPresent(List<Map.Entry<String, Object>> fields, String label,
                                     Object value) {
        if (value != null) fields.add(Map.entry(label, value));
    }

    /**
     * A sheet name Excel will accept, unique within the workbook.
     *
     * <p>Two accounts genuinely called "Livret A" is the common case, not an edge one, and a
     * duplicate name makes POI throw rather than degrade — so the suffix is mandatory, and it
     * has to eat into the 31-character budget rather than push past it.
     */
    private String uniqueSheetName(AccountResponse account, SheetLabels labels, Set<String> used) {
        String fallback = labels.get(ACCOUNT_FALLBACK_NAME) + " " + account.id();
        String base = safeName(account.name(), fallback);

        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            String tail = " (" + suffix++ + ")";
            int keep = Math.min(base.length(), MAX_SHEET_NAME - tail.length());
            candidate = base.substring(0, keep) + tail;
        }
        return candidate;
    }

    /** Replaces the characters Excel forbids and trims to 31 chars, falling back if nothing is left. */
    private String safeName(String raw, String fallback) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) candidate = fallback;
        String safe = WorkbookUtil.createSafeSheetName(candidate).trim();
        return safe.isEmpty() ? WorkbookUtil.createSafeSheetName(fallback) : safe;
    }
}
