package com.picsou.export.xlsx;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.GoalAllocationResponse;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.MemberProfileResponse;
import com.picsou.dto.DebtResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.RealEstateMetadataResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.model.Goal;
import com.picsou.model.GoalType;
import com.picsou.model.HouseholdStatus;
import com.picsou.model.Debt;
import com.picsou.model.PropertyValuation;
import com.picsou.model.RealEstateMetadata;
import com.picsou.model.ValuationConfidence;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.service.AccountService;
import com.picsou.service.GoalService;
import com.picsou.service.MemberProfileService;
import com.picsou.service.SavingsRateCalculator;
import com.picsou.service.LoanAmortizationService;
import com.picsou.service.LoanAmortizationService.LoanInstallment;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;
import com.picsou.service.LoanAmortizationService.LoanSummary;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountsWorkbookServiceTest {

    private static final Long MEMBER = 7L;

    @Mock AccountService accountService;
    @Mock PropertyValuationRepository valuationRepository;
    @Mock DebtRepository debtRepository;
    @Mock LoanAmortizationService loanAmortizationService;
    @Mock MemberProfileService memberProfileService;
    @Mock GoalService goalService;
    // Spied, not mocked: the summary's savings rate should come out of the real arithmetic.
    @Spy SavingsRateCalculator savingsRateCalculator = new SavingsRateCalculator();

    @InjectMocks AccountsWorkbookService service;

    /** Nothing stated and no plans -- the shape every pre-existing case here assumes. */
    @BeforeEach
    void wireEmptyContext() {
        when(memberProfileService.get(MEMBER)).thenReturn(profile(null, null, null));
        when(goalService.findAll(MEMBER)).thenReturn(List.of());
        when(debtRepository.findAllByMemberId(MEMBER)).thenReturn(List.of());
        when(debtRepository.findByLinkedAccountId(anyLong())).thenReturn(List.of());
    }

    // ─── Sheet naming ────────────────────────────────────────────────────────

    @Test
    void summaryAndDebtSheetsComeFirst_thenOneSheetPerAccount() throws IOException {
        stubAccount(bank(1L, "Compte courant"));
        stubAccount(bank(2L, "Livret A"));

        Workbook wb = export(List.of(1L, 2L));

        assertThat(sheetNames(wb)).containsExactly("Summary", "Debts", "Compte courant", "Livret A");
    }

    @Test
    void identicallyNamedAccounts_getDistinctSheets() throws IOException {
        // Two "Livret A" is the common case, and POI throws on a duplicate name.
        stubAccount(bank(1L, "Livret A"));
        stubAccount(bank(2L, "Livret A"));

        Workbook wb = export(List.of(1L, 2L));

        assertThat(sheetNames(wb)).containsExactly("Summary", "Debts", "Livret A", "Livret A (2)");
    }

    @Test
    void forbiddenCharactersAndOverlongNames_areSanitizedIntoExcelsBudget() throws IOException {
        stubAccount(bank(1L, "PEA / Bourse [2026]"));
        stubAccount(bank(2L, "Assurance vie Linxea Spirit 2 en unités de compte"));

        Workbook wb = export(List.of(1L, 2L));

        List<String> names = sheetNames(wb);
        assertThat(names.get(2)).isEqualTo("PEA   Bourse  2026");
        assertThat(names.get(3)).hasSize(31);
    }

    @Test
    void blankAccountName_fallsBackToTheAccountId() throws IOException {
        stubAccount(bank(42L, "   "));

        Workbook wb = export(List.of(42L));

        assertThat(sheetNames(wb)).containsExactly("Summary", "Debts", "Account 42");
    }

    @Test
    void duplicateIdsInTheRequest_produceOneSheet() throws IOException {
        stubAccount(bank(1L, "Livret A"));

        Workbook wb = export(List.of(1L, 1L, 1L));

        assertThat(sheetNames(wb)).containsExactly("Summary", "Debts", "Livret A");
    }

    // ─── Positions ───────────────────────────────────────────────────────────

    @Test
    void positionsBlock_writesEveryHoldingWithTypedFigures() throws IOException {
        AccountResponse pea = bank(1L, "PEA", AccountType.PEA);
        stubAccount(pea);
        when(accountService.getHoldings(1L, MEMBER)).thenReturn(List.of(
            new HoldingResponse("CW8.PA", "Amundi MSCI World", new BigDecimal("12.5"),
                new BigDecimal("400.00"), new BigDecimal("520.00"), "EUR",
                new BigDecimal("6500.00"), new BigDecimal("5000.00"),
                new BigDecimal("1500.00"), new BigDecimal("30.00"),
                Instant.parse("2026-08-18T10:00:00Z"), LocalDate.parse("2026-08-18"), false),
            new HoldingResponse("ESE.PA", "BNP S&P 500", new BigDecimal("3"),
                new BigDecimal("20.00"), new BigDecimal("18.00"), "EUR",
                new BigDecimal("54.00"), new BigDecimal("60.00"),
                new BigDecimal("-6.00"), new BigDecimal("-10.00"),
                null, null, true)
        ));

        Sheet sheet = export(List.of(1L)).getSheet("PEA");
        int header = rowIndexOf(sheet, "Ticker");

        Row first = sheet.getRow(header + 1);
        assertThat(first.getCell(0).getStringCellValue()).isEqualTo("CW8.PA");
        assertThat(first.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(12.5);
        assertThat(first.getCell(8).getNumericCellValue()).isEqualTo(1500.0);
        // Already out of 100 upstream -- writing it as an Excel percentage would rescale it.
        assertThat(first.getCell(9).getNumericCellValue()).isEqualTo(30.0);
        assertThat(first.getCell(9).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00\" %\"");
        assertThat(first.getCell(11).getStringCellValue()).isEqualTo("No");

        Row second = sheet.getRow(header + 2);
        assertThat(second.getCell(8).getNumericCellValue()).isEqualTo(-6.0);
        assertThat(second.getCell(11).getStringCellValue()).isEqualTo("Yes");
    }

    @Test
    void accountWithoutHoldings_getsNoPositionsBlock() throws IOException {
        stubAccount(bank(1L, "Livret A"));

        Sheet sheet = export(List.of(1L)).getSheet("Livret A");

        assertThat(rowIndexOf(sheet, "Positions")).isEqualTo(-1);
    }

    // ─── Property ────────────────────────────────────────────────────────────

    @Test
    void propertyBlock_carriesTheCostBasisAndTheValuationHistory() throws IOException {
        RealEstateMetadata meta = RealEstateMetadata.builder()
            .purchasePrice(new BigDecimal("240000"))
            .purchaseDate(LocalDate.parse("2021-06-15"))
            .notaryFees(new BigDecimal("18000"))
            .propertyType("APARTMENT")
            .city("Nantes")
            .surfaceArea(new BigDecimal("68"))
            .build();
        AccountResponse flat = bank(1L, "Appartement Nantes", AccountType.REAL_ESTATE)
            .withRealEstate(RealEstateMetadataResponse.from(meta, LocalDate.parse("2026-08-01")));
        stubAccount(flat);
        when(valuationRepository.findByAccountIdOrderByValuedAtDesc(1L)).thenReturn(List.of(
            valuation(LocalDate.parse("2026-08-01"), "310000"),
            valuation(LocalDate.parse("2026-07-01"), "305000")
        ));

        Sheet sheet = export(List.of(1L)).getSheet("Appartement Nantes");

        // Purchase price plus every acquisition fee -- what gain is measured against.
        assertThat(numberBesideLabel(sheet, "Cost basis")).isEqualTo(258000.0);
        assertThat(stringBesideLabel(sheet, "City")).isEqualTo("Nantes");

        int header = rowIndexOf(sheet, "Valuation history") + 1;
        assertThat(sheet.getRow(header).getCell(0).getStringCellValue()).isEqualTo("Date");
        assertThat(sheet.getRow(header + 1).getCell(1).getNumericCellValue()).isEqualTo(310000.0);
        assertThat(sheet.getRow(header + 2).getCell(1).getNumericCellValue()).isEqualTo(305000.0);
    }

    @Test
    void propertyNeverValued_stillGetsItsMetadataBlock() throws IOException {
        RealEstateMetadata meta = RealEstateMetadata.builder()
            .purchasePrice(new BigDecimal("240000"))
            .propertyType("HOUSE")
            .build();
        stubAccount(bank(1L, "Maison", AccountType.REAL_ESTATE)
            .withRealEstate(RealEstateMetadataResponse.from(meta, null)));
        when(valuationRepository.findByAccountIdOrderByValuedAtDesc(1L)).thenReturn(List.of());

        Sheet sheet = export(List.of(1L)).getSheet("Maison");

        assertThat(rowIndexOf(sheet, "Property")).isGreaterThan(0);
        assertThat(rowIndexOf(sheet, "Valuation history")).isEqualTo(-1);
    }

    // ─── Loan ────────────────────────────────────────────────────────────────

    @Test
    void loanBlock_writesTheRateOutOf100_andTheWholeSchedule() throws IOException {
        DebtResponse debt = new DebtResponse(null, "Appartement Nantes",
            new BigDecimal("200000"), new BigDecimal("0.0325"), new BigDecimal("980.00"),
            "Crédit Agricole", LocalDate.parse("2021-07-01"), LocalDate.parse("2041-07-01"),
            new BigDecimal("22.00"), new BigDecimal("900.00"));
        stubAccount(bank(1L, "Prêt immobilier", AccountType.LOAN).withDebt(debt));
        Debt entity = Debt.builder().borrowedAmount(new BigDecimal("200000")).build();
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.of(entity));
        when(loanAmortizationService.compute(entity)).thenReturn(schedule(3));

        Sheet sheet = export(List.of(1L)).getSheet("Prêt immobilier");

        // Stored as a ratio; the column says "(%)".
        assertThat(numberBesideLabel(sheet, "Interest rate (%)")).isEqualTo(3.25);
        assertThat(stringBesideLabel(sheet, "Lender")).isEqualTo("Crédit Agricole");
        assertThat(numberBesideLabel(sheet, "Instalments")).isEqualTo(3.0);

        int header = rowIndexOf(sheet, "Amortization schedule") + 1;
        assertThat(sheet.getRow(header + 1).getCell(0).getNumericCellValue()).isEqualTo(1.0);
        assertThat(sheet.getRow(header + 3).getCell(0).getNumericCellValue()).isEqualTo(3.0);
        assertThat(sheet.getRow(header + 4)).isNull();
    }

    @Test
    void loanAccountWithNoDebtRow_stillExportsWithoutASchedule() throws IOException {
        stubAccount(bank(1L, "Prêt", AccountType.LOAN));
        when(debtRepository.findByAccountId(1L)).thenReturn(Optional.empty());

        Sheet sheet = export(List.of(1L)).getSheet("Prêt");

        assertThat(rowIndexOf(sheet, "Amortization schedule")).isEqualTo(-1);
    }

    // ─── Labels ──────────────────────────────────────────────────────────────

    @Test
    void suppliedLabels_replaceTheEnglishHeadings() throws IOException {
        AccountResponse pea = bank(1L, "PEA", AccountType.PEA);
        stubAccount(pea);
        when(accountService.getHoldings(1L, MEMBER)).thenReturn(List.of(
            new HoldingResponse("CW8.PA", "World", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, "EUR", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, false)
        ));

        SheetLabels labels = SheetLabels.of(Map.of(
            "summarySheet", "Synthèse",
            "positions", "Positions",
            "quantity", "Quantité"
        ));
        Workbook wb = export(List.of(1L), labels);

        assertThat(wb.getSheetName(0)).isEqualTo("Synthèse");
        Sheet sheet = wb.getSheet("PEA");
        int header = rowIndexOf(sheet, "Positions") + 1;
        assertThat(sheet.getRow(header).getCell(2).getStringCellValue()).isEqualTo("Quantité");
    }

    @Test
    void summarySheet_listsEveryExportedAccount() throws IOException {
        stubAccount(bank(1L, "Compte courant"));
        stubAccount(bank(2L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L, 2L)).getSheet("Summary");
        int header = rowIndexOf(summary, "Name");

        assertThat(summary.getRow(header + 1).getCell(0).getStringCellValue()).isEqualTo("Compte courant");
        assertThat(summary.getRow(header + 1).getCell(1).getStringCellValue()).isEqualTo("CHECKING");
        assertThat(summary.getRow(header + 2).getCell(0).getStringCellValue()).isEqualTo("PEA");
        assertThat(summary.getRow(header + 2).getCell(5).getNumericCellValue()).isEqualTo(1234.56);
    }

    // ─── Summary: member profile ─────────────────────────────────────────────

    @Test
    void profileBlock_comesBeforeTheAccountsAndSkipsWhatIsUnstated() throws IOException {
        when(memberProfileService.get(MEMBER)).thenReturn(profile(36, "30", "48000"));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        // The reader meets the person before the portfolio.
        assertThat(rowIndexOf(summary, "Profile")).isLessThan(rowIndexOf(summary, "Name"));
        assertThat(numberBesideLabel(summary, "Age")).isEqualTo(36);
        assertThat(numberBesideLabel(summary, "Annual gross income")).isEqualTo(48000);
        assertThat(stringBesideLabel(summary, "Household")).isEqualTo("COUPLE");
        // Never stated, so never printed: a label with an empty cell says less than no label.
        assertThat(rowIndexOf(summary, "Dependents")).isEqualTo(-1);
        assertThat(rowIndexOf(summary, "Monthly savings capacity")).isEqualTo(-1);
    }

    @Test
    void profileBlock_writesRatesOutOfOneHundred() throws IOException {
        // The same trap as pnlPercent: Excel's own percent format would rescale 30 to 3000%.
        when(memberProfileService.get(MEMBER)).thenReturn(profile(36, "30", "48000"));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(numberBesideLabel(summary, "Marginal tax rate (%)")).isEqualTo(30);
        Cell cell = summary.getRow(rowIndexOf(summary, "Marginal tax rate (%)")).getCell(1);
        assertThat(cell.getCellStyle().getDataFormatString()).contains("%");
    }

    @Test
    void profileBlock_isAbsentWhenNothingIsStated() throws IOException {
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(rowIndexOf(summary, "Profile")).isEqualTo(-1);
        assertThat(rowIndexOf(summary, "Name")).isGreaterThan(-1);
    }

    // ─── Summary: recurring investment plans ─────────────────────────────────

    @Test
    void recurringPlansBlock_reportsTheTotalTheRateAndEveryPlan() throws IOException {
        when(memberProfileService.get(MEMBER)).thenReturn(profile(36, "30", "48000"));
        when(goalService.findAll(MEMBER)).thenReturn(List.of(
            plan("DCA PEA", "PEA", "400", List.of()),
            plan("DCA CTO", "CTO", "200", List.of())));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(numberBesideLabel(summary, "Invested monthly")).isEqualTo(600);
        // 600 of a 3 000 net -- the same worked example SavingsRateCardTest pins on the client.
        assertThat(numberBesideLabel(summary, "Savings rate (%)")).isEqualTo(20.0);

        int header = rowIndexOf(summary, "Plan");
        assertThat(summary.getRow(header + 1).getCell(0).getStringCellValue()).isEqualTo("DCA PEA");
        assertThat(summary.getRow(header + 1).getCell(1).getStringCellValue()).isEqualTo("PEA");
        assertThat(summary.getRow(header + 1).getCell(2).getNumericCellValue()).isEqualTo(400);
    }

    @Test
    void recurringPlansBlock_dropsTheRateWhenNoIncomeIsStated() throws IOException {
        // A zero here would read as "saves nothing" rather than "we were not told what they earn".
        when(goalService.findAll(MEMBER)).thenReturn(List.of(plan("DCA PEA", "PEA", "400", List.of())));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(numberBesideLabel(summary, "Invested monthly")).isEqualTo(400);
        assertThat(rowIndexOf(summary, "Savings rate (%)")).isEqualTo(-1);
    }

    @Test
    void positionBreakdown_listsEveryLineAndStatesTheRemainder() throws IOException {
        when(goalService.findAll(MEMBER)).thenReturn(List.of(plan("DCA PEA", "PEA", "400", List.of(
            new GoalAllocationResponse("CW8", "Amundi MSCI World", new BigDecimal("250")),
            new GoalAllocationResponse("ESE", "BNP S&P 500", new BigDecimal("100"))))));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");
        int header = rowIndexOf(summary, "Monthly position breakdown") + 1;

        assertThat(summary.getRow(header + 1).getCell(2).getStringCellValue()).isEqualTo("CW8");
        assertThat(summary.getRow(header + 1).getCell(3).getStringCellValue()).isEqualTo("Amundi MSCI World");
        assertThat(summary.getRow(header + 1).getCell(4).getNumericCellValue()).isEqualTo(250);
        assertThat(summary.getRow(header + 2).getCell(2).getStringCellValue()).isEqualTo("ESE");
        // 400 planned, 350 detailed: the rows above must be seen not to add up on their own.
        assertThat(summary.getRow(header + 3).getCell(2).getStringCellValue()).isEqualTo("Unallocated");
        assertThat(summary.getRow(header + 3).getCell(4).getNumericCellValue()).isEqualTo(50);
    }

    @Test
    void positionBreakdown_isAbsentWhenNoPlanIsDetailed() throws IOException {
        when(goalService.findAll(MEMBER)).thenReturn(List.of(plan("DCA PEA", "PEA", "400", List.of())));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(rowIndexOf(summary, "Monthly position breakdown")).isEqualTo(-1);
        assertThat(rowIndexOf(summary, "Recurring investments")).isGreaterThan(-1);
    }

    @Test
    void recurringPlansBlock_isAbsentWithoutPlans_andSavingsTargetsDoNotCount() throws IOException {
        // A goal with a deadline has neither a monthly amount nor a split to print.
        when(goalService.findAll(MEMBER)).thenReturn(List.of(savingsTarget("Apport")));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(rowIndexOf(summary, "Recurring investments")).isEqualTo(-1);
    }

    // ─── Opening date ────────────────────────────────────────────────────────

    @Test
    void anOpeningDate_isWrittenOnTheAccountSheet() throws IOException {
        // A PEA's whole tax treatment is a function of this date, and createdAt cannot stand in:
        // a plan opened in 2014 and typed in last month has a decade between the two.
        stubAccount(bank(1L, "PEA", AccountType.PEA)
            .withOpenedAt(LocalDate.parse("2014-03-12")));

        Sheet sheet = export(List.of(1L)).getSheet("PEA");

        assertThat(sheet.getRow(rowIndexOf(sheet, "Opened")).getCell(1).getLocalDateTimeCellValue()
            .toLocalDate()).isEqualTo(LocalDate.parse("2014-03-12"));
    }

    @Test
    void noOpeningDate_leavesTheLineOut() throws IOException {
        // Absent rather than blank: nothing here should suggest a date was recorded.
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet sheet = export(List.of(1L)).getSheet("PEA");

        assertThat(rowIndexOf(sheet, "Opened")).isEqualTo(-1);
    }

    // ─── Debt ────────────────────────────────────────────────────────────────

    @Test
    void debtSheetAndSummaryBlock_existEvenWithNoDebt() throws IOException {
        // The point of the whole block: silence is indistinguishable from an omission. A reader
        // asking "is there a mortgage?" gets an answer either way.
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Workbook wb = export(List.of(1L));
        Sheet summary = wb.getSheet("Summary");

        assertThat(sheetNames(wb)).containsExactly("Summary", "Debts", "PEA");
        assertThat(rowIndexOf(summary, "No debt recorded")).isGreaterThan(-1);
        assertThat(numberBesideLabel(summary, "Outstanding")).isEqualTo(0);

        Sheet debts = wb.getSheet("Debts");
        assertThat(rowIndexOf(debts, "No debt recorded")).isGreaterThan(-1);
        assertThat(numberBesideLabel(debts, "Outstanding")).isEqualTo(0);
    }

    @Test
    void debtSummaryBlock_totalsEveryLoanAndListsThem() throws IOException {
        when(debtRepository.findAllByMemberId(MEMBER)).thenReturn(List.of(
            debt(10L, "Prêt maison", "BoursoBank", "200000", "180000", "980", "0.0325"),
            debt(11L, "Prêt auto", "Cetelem", "20000", "12000", "300", "0.0450")));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet summary = export(List.of(1L)).getSheet("Summary");

        assertThat(numberBesideLabel(summary, "Total borrowed")).isEqualTo(220000);
        assertThat(numberBesideLabel(summary, "Outstanding")).isEqualTo(192000);
        assertThat(numberBesideLabel(summary, "Monthly payments")).isEqualTo(1280);

        int header = rowIndexOf(summary, "Loan account");
        assertThat(summary.getRow(header + 1).getCell(0).getStringCellValue()).isEqualTo("Prêt maison");
        assertThat(summary.getRow(header + 1).getCell(1).getStringCellValue()).isEqualTo("BoursoBank");
    }

    @Test
    void debtSheet_writesTheRateOutOfOneHundred() throws IOException {
        // Debt.interestRate is a ratio (0.0325) and the column says (%): multiplied on the way
        // in, never left for Excel's percent format to rescale a second time.
        when(debtRepository.findAllByMemberId(MEMBER)).thenReturn(List.of(
            debt(10L, "Prêt maison", "BoursoBank", "200000", "180000", "980", "0.0325")));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet debts = export(List.of(1L)).getSheet("Debts");
        int header = rowIndexOf(debts, "Loan account");

        assertThat(debts.getRow(header + 1).getCell(5).getNumericCellValue()).isEqualTo(3.25);
    }

    @Test
    void debtSheet_carriesTheFiguresDerivedFromTheSchedule() throws IOException {
        when(debtRepository.findAllByMemberId(MEMBER)).thenReturn(List.of(
            debt(10L, "Prêt maison", "BoursoBank", "200000", "180000", "980", "0.0325")));
        when(loanAmortizationService.compute(any())).thenReturn(schedule(240));
        stubAccount(bank(1L, "PEA", AccountType.PEA));

        Sheet debts = export(List.of(1L)).getSheet("Debts");
        int header = rowIndexOf(debts, "Loan account");
        Row row = debts.getRow(header + 1);

        assertThat(row.getCell(11).getNumericCellValue()).isEqualTo(1);    // paid instalments
        assertThat(row.getCell(12).getNumericCellValue()).isEqualTo(240);  // total instalments
        assertThat(row.getCell(13).getNumericCellValue()).isEqualTo(30000); // total interest
    }

    @Test
    void anAccountNamedLikeAFixedSheet_doesNotCollide() throws IOException {
        // POI throws on a duplicate sheet name rather than degrading, and the two fixed sheets
        // were not in the dedup set before the debt sheet was added.
        stubAccount(bank(1L, "Summary"));
        stubAccount(bank(2L, "Debts"));

        Workbook wb = export(List.of(1L, 2L));

        assertThat(sheetNames(wb)).containsExactly("Summary", "Debts", "Summary (2)", "Debts (2)");
    }

    @Test
    void propertySheet_statesTheDebtFinancingIt() throws IOException {
        AccountResponse house = property(1L, "Maison");
        stubAccount(house);
        when(debtRepository.findByLinkedAccountId(1L)).thenReturn(List.of(
            debt(10L, "Prêt maison", "BoursoBank", "200000", "180000", "980", "0.0325")));

        Sheet sheet = export(List.of(1L)).getSheet("Maison");

        assertThat(numberBesideLabel(sheet, "Debt on this property")).isEqualTo(180000);
        int header = rowIndexOf(sheet, "Loan account");
        assertThat(sheet.getRow(header + 1).getCell(0).getStringCellValue()).isEqualTo("Prêt maison");
        assertThat(sheet.getRow(header + 1).getCell(3).getNumericCellValue()).isEqualTo(180000);
    }

    @Test
    void propertySheet_statesZeroWhenNothingFinancesIt() throws IOException {
        // A property owned outright is a statement worth making; an absent line only reads as
        // "nobody recorded the financing".
        stubAccount(property(1L, "Maison"));

        Sheet sheet = export(List.of(1L)).getSheet("Maison");

        assertThat(numberBesideLabel(sheet, "Debt on this property")).isEqualTo(0);
        assertThat(rowIndexOf(sheet, "Loan account")).isEqualTo(-1);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Workbook export(List<Long> ids) throws IOException {
        return export(ids, SheetLabels.english());
    }

    private Workbook export(List<Long> ids, SheetLabels labels) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(ids, MEMBER, labels, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    private void stubAccount(AccountResponse account) {
        when(accountService.findById(account.id(), MEMBER)).thenReturn(account);
        when(accountService.getHoldings(anyLong(), anyLong())).thenReturn(List.of());
    }

    private AccountResponse bank(Long id, String name) {
        return bank(id, name, AccountType.CHECKING);
    }

    private AccountResponse bank(Long id, String name, AccountType type) {
        return new AccountResponse(id, name, type, "BoursoBank", "EUR",
            new BigDecimal("1234.56"), new BigDecimal("1234.56"), null,
            Instant.parse("2026-08-18T06:00:00Z"), false, "#6366f1", null, null, null,
            Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null, null, false, null, true);
    }

    private PropertyValuation valuation(LocalDate valuedAt, String estimate) {
        return PropertyValuation.builder()
            .valuedAt(valuedAt)
            .estimatedValue(new BigDecimal(estimate))
            .lowValue(new BigDecimal("290000"))
            .highValue(new BigDecimal("330000"))
            .pricePerSqm(new BigDecimal("4558"))
            .provider("cerema-dv3f")
            .confidence(ValuationConfidence.MEDIUM)
            .sampleSize(140)
            .sourceYear((short) 2024)
            .build();
    }

    private LoanScheduleResponse schedule(int installments) {
        LoanSummary summary = new LoanSummary(
            installments, 1, installments - 1, LocalDate.parse("2041-07-01"),
            new BigDecimal("980.00"), new BigDecimal("400.00"), new BigDecimal("560.00"),
            new BigDecimal("20.00"), new BigDecimal("235200"), new BigDecimal("200000"),
            new BigDecimal("30000"), new BigDecimal("5200"), new BigDecimal("900"),
            new BigDecimal("980"), new BigDecimal("400"), new BigDecimal("560"),
            new BigDecimal("20"), new BigDecimal("199600"), new BigDecimal("0.2"));
        List<LoanInstallment> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= installments; i++) {
            rows.add(new LoanInstallment(i, LocalDate.parse("2021-07-01").plusMonths(i),
                new BigDecimal("400.00"), new BigDecimal("560.00"), new BigDecimal("20.00"),
                new BigDecimal("980.00"), new BigDecimal("199600.00")));
        }
        return new LoanScheduleResponse(summary, rows);
    }

    private MemberProfileResponse profile(Integer age, String tmi, String grossIncome) {
        return new MemberProfileResponse(
            age == null ? null : LocalDate.now().minusYears(age), age,
            tmi == null ? null : new BigDecimal(tmi),
            age == null ? null : HouseholdStatus.COUPLE,
            null, null,
            grossIncome == null ? null : new BigDecimal(grossIncome),
            grossIncome == null ? null : new BigDecimal("3200"),
            grossIncome == null ? null : new BigDecimal("6.25"),
            // 3 200 less 6.25% is exactly 3 000, which keeps the rate assertion a round number.
            grossIncome == null ? null : new BigDecimal("3000.00"),
            null, null, null);
    }

    private GoalProgressResponse plan(String name, String accountName, String monthly,
                                      List<GoalAllocationResponse> allocations) {
        Goal goal = Goal.builder()
            .id(1L).name(name).type(GoalType.RECURRING_INVESTMENT)
            .monthlyAmount(new BigDecimal(monthly))
            .build();
        return GoalProgressResponse.recurring(
            goal, List.of(bank(99L, accountName, AccountType.PEA)), BigDecimal.ZERO, allocations);
    }

    private GoalProgressResponse savingsTarget(String name) {
        Goal goal = Goal.builder()
            .id(2L).name(name).type(GoalType.SAVINGS_TARGET)
            .targetAmount(new BigDecimal("50000")).deadline(LocalDate.now().plusYears(2))
            .build();
        return GoalProgressResponse.from(goal, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
            24, BigDecimal.ZERO, null, true, BigDecimal.ZERO);
    }

    private AccountResponse property(Long id, String name) {
        RealEstateMetadata meta = RealEstateMetadata.builder()
            .purchasePrice(new BigDecimal("240000"))
            .propertyType("HOUSE")
            .build();
        return bank(id, name, AccountType.REAL_ESTATE)
            .withRealEstate(RealEstateMetadataResponse.from(meta, null));
    }

    private Debt debt(Long id, String loanAccountName, String lender, String borrowed,
                      String outstanding, String monthly, String rate) {
        Account loanAccount = Account.builder()
            .id(id).name(loanAccountName).type(AccountType.LOAN).currency("EUR")
            .currentBalance(new BigDecimal(outstanding))
            .build();
        return Debt.builder()
            .id(id).account(loanAccount)
            .borrowedAmount(new BigDecimal(borrowed))
            .interestRate(new BigDecimal(rate))
            .monthlyPayment(new BigDecimal(monthly))
            .lenderName(lender)
            .startDate(LocalDate.parse("2021-07-01"))
            .endDate(LocalDate.parse("2041-07-01"))
            .build();
    }

    private List<String> sheetNames(Workbook wb) {
        return java.util.stream.IntStream.range(0, wb.getNumberOfSheets())
            .mapToObj(wb::getSheetName)
            .toList();
    }

    /** Row index whose first cell holds {@code label}, or -1. */
    private int rowIndexOf(Sheet sheet, String label) {
        for (Row row : sheet) {
            Cell cell = row.getCell(0);
            if (cell != null && cell.getCellType() == CellType.STRING
                && label.equals(cell.getStringCellValue())) {
                return row.getRowNum();
            }
        }
        return -1;
    }

    private double numberBesideLabel(Sheet sheet, String label) {
        return sheet.getRow(rowIndexOf(sheet, label)).getCell(1).getNumericCellValue();
    }

    private String stringBesideLabel(Sheet sheet, String label) {
        return sheet.getRow(rowIndexOf(sheet, label)).getCell(1).getStringCellValue();
    }
}
