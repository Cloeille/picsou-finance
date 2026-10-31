package com.picsou.imports.homebank;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeBankQifParserTest {
    private final HomeBankQifParser parser = new HomeBankQifParser();

    @Test
    void parsesAccountsTransactionsCategoriesTransfersAndSplits() throws Exception {
        ParsedHomeBankData parsed = parser.parse(fixture(), "EUR");

        assertThat(parsed.accounts()).hasSize(2);
        assertThat(parsed.accounts()).extracting(ParsedHomeBankData.SourceAccount::name)
                .containsExactly("Compte courant", "Épargne");
        assertThat(parsed.accounts()).allSatisfy(account -> {
            assertThat(account.currency()).isEqualTo("EUR");
            assertThat(account.initialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.id()).isEqualTo(UUID.fromString(account.id()).toString());
        });
        assertThat(parsed.categories()).extracting(ParsedHomeBankData.SourceCategory::name)
                .contains("Food", "Groceries", "Salary", "Household", "Utilities");
        assertThat(parsed.categories()).filteredOn(category -> category.name().equals("Groceries"))
                .singleElement().satisfies(category -> {
                    assertThat(category.income()).isFalse();
                    assertThat(category.parentId()).isNotNull();
                });
        assertThat(parsed.categories()).filteredOn(category -> category.name().equals("Salary"))
                .singleElement().satisfies(category -> assertThat(category.income()).isTrue());

        List<ParsedHomeBankData.SourceTransaction> transactions = parsed.transactions();
        assertThat(transactions).hasSize(7);
        assertThat(transactions.get(0).amount()).isEqualByComparingTo("-12.34000001");
        assertThat(transactions.get(0).date().toString()).isEqualTo("2024-02-29");
        assertThat(transactions.get(0).payee()).isEqualTo("Épicerie 🥖");
        assertThat(transactions.get(0).notes()).isEqualTo("achats du mois");
        assertThat(transactions.get(2).amount()).isEqualByComparingTo("-5.00");
        assertThat(transactions.get(2).categoryId()).isNotNull();
        assertThat(transactions.get(3).transferAccountId()).isEqualTo(parsed.accounts().get(1).id());
        assertThat(transactions.get(4).amount()).isEqualByComparingTo("-4.25");
        assertThat(transactions.get(4).notes()).isEqualTo("split memo");
        assertThat(transactions.get(5).amount()).isEqualByComparingTo("-5.75");
        assertThat(transactions.get(6).transferAccountId()).isEqualTo(parsed.accounts().get(0).id());
    }

    @Test
    void producesStableDistinctIdsForRepeatedContentAndIgnoresClearedMarker() throws Exception {
        String one = "!Account\nNChecking\nTBank\n^\n!Type:Bank\n"
                + "D2024/01/02\nT-1.00\nC*\nPSame\nLFood\n^\n";
        String sameWithoutCleared = one.replace("C*\n", "");
        ParsedHomeBankData first = parser.parse(one.getBytes(StandardCharsets.UTF_8), "USD");
        ParsedHomeBankData again = parser.parse(sameWithoutCleared.getBytes(StandardCharsets.UTF_8), "USD");
        assertThat(first.transactions().get(0).id()).isEqualTo(again.transactions().get(0).id());

        String duplicated = one + "D2024/01/02\nT-1.00\nPSame\nLFood\n^\n";
        ParsedHomeBankData two = parser.parse(duplicated.getBytes(StandardCharsets.UTF_8), "USD");
        assertThat(two.transactions()).hasSize(2);
        assertThat(two.transactions()).extracting(ParsedHomeBankData.SourceTransaction::id).doesNotHaveDuplicates();
        assertThat(two.transactions().get(0).id()).isNotEqualTo(two.transactions().get(1).id());

        String withUnrelatedEarlierRow = one.replace("!Type:Bank\n", "!Type:Bank\n"
                + "D2024/01/01\nT-9.00\nPOther\n^\n");
        ParsedHomeBankData shifted = parser.parse(withUnrelatedEarlierRow.getBytes(StandardCharsets.UTF_8), "USD");
        assertThat(shifted.transactions().get(1).id()).isEqualTo(first.transactions().get(0).id());
    }

    @Test
    void rejectsMalformedOrUnsupportedQifWithoutEchoingSourceFields() throws Exception {
        assertInvalid("!Type:Bank\nD2024/01/01\nT1\n^");
        assertInvalid("!Account\nN\nTBank\n^\n!Type:Bank\nD2024/02/30\nT1\n^\n");
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nT2\n^\n");
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Type:Invst\n^");
        assertInvalid("!Account\nNSecret Account\nTBank\n^\n!Type:Bank\nDbad\nT1\n^");
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nQunknown\n^\n");
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-2\nSFood\n$-1\n^");
    }

    @Test
    void requiresValidatedCurrencyAndEnforcesInputAndStructureBounds() throws Exception {
        byte[] fixture = fixture();
        assertThatThrownBy(() -> parser.parse(fixture, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(fixture, "ZZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(new byte[10 * 1024 * 1024 + 1], "EUR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Account\nNChecking\nTBank\n^\n");
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nP" + "x".repeat(256) + "\n^\n");
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Account\nN" + "x".repeat(101) + "\nTBank\n^\n");
    }

    @Test
    void rejectsInvalidUtf8AndUnterminatedRecords() throws Exception {
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xc3, 0x28}, "EUR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertInvalid("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\n");
    }

    @Test
    void acceptsEmptyOptionalPayeeCategoryAndClearedFields() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT1\nP\nC\nL\n^\n";
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.transactions()).singleElement().satisfies(transaction -> {
            assertThat(transaction.payee()).isNull();
            assertThat(transaction.categoryId()).isNull();
        });
    }

    @Test
    void treatsPositiveRefundAsExpenseWhenCategoryHasNegativeSpending() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\n"
                + "D2024/01/01\nT-12\nLFood\n^\nD2024/01/02\nT2\nLFood\n^\n";
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.categories()).singleElement().satisfies(category -> assertThat(category.income()).isFalse());
        assertThat(parsed.transactions()).hasSize(2);
    }

    @Test
    void enforcesAccountCategoryAndNormalizedTransactionLimits() {
        StringBuilder accounts = new StringBuilder();
        for (int i = 0; i < 101; i++) accounts.append("!Account\nNAccount ").append(i).append("\nTBank\n^\n");
        assertInvalid(accounts.toString());

        StringBuilder categories = new StringBuilder("!Account\nNChecking\nTBank\n^\n");
        for (int i = 0; i < 201; i++) categories.append("!Type:Cat\nNCategory ").append(i).append("\nE\n^\n");
        assertInvalid(categories.toString());

        StringBuilder transactions = new StringBuilder("!Account\nNChecking\nTBank\n^\n!Type:Bank\n");
        for (int i = 0; i < 25_001; i++) transactions.append("D2024/01/01\nT1\n^\n");
        assertInvalid(transactions.toString());
    }

    @Test
    void flagsSignInferredCategoryKindsButKeepsExplicitDeclarationsAuthoritative() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Cat\nNSalary\nI\n^\n!Type:Bank\n"
                + "D2024/01/01\nT-1\nLFood\n^\nD2024/01/02\nT5\nLSalary\n^\n";
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.categories()).filteredOn(category -> category.name().equals("Food"))
                .singleElement().satisfies(category -> assertThat(category.kindInferred()).isTrue());
        assertThat(parsed.categories()).filteredOn(category -> category.name().equals("Salary"))
                .singleElement().satisfies(category -> assertThat(category.kindInferred()).isFalse());
    }

    @Test
    void acceptsMultipleCategoryRecordsUnderOneHeader() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Cat\nNFood\nE\n^\n"
                + "NSalary\nI\n^\n!Type:Bank\nD2024/01/01\nT-1\nLFood\n^\n";
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.categories()).extracting(ParsedHomeBankData.SourceCategory::name)
                .contains("Food", "Salary");
    }

    @Test
    void retainsParentAndSplitMemosWithoutDuplicatingIdenticalText() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\n"
                + "D2024/01/01\nT-3\nMparent note\nSFood\nEsplit note\n$-3\n^\n"
                + "D2024/01/02\nT-2\nMsame\nSFood\nEsame\n$-2\n^\n"
                + "D2024/01/03\nT-1\nMparent only\nSFood\nE\n$-1\n^\n";
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.transactions()).extracting(ParsedHomeBankData.SourceTransaction::notes)
                .containsExactly("parent note — split note", "same", "parent only");
    }

    @Test
    void rejectsCombinedMemoOverflowInsteadOfTruncating() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-1\nM"
                + "p".repeat(130) + "\nSFood\nE" + "s".repeat(130) + "\n$-1\n^\n";
        assertInvalid(qif);
    }

    @Test
    void rejectsTooManyExpandedSplitRowsBeforeAccumulatingThem() {
        StringBuilder qif = new StringBuilder("!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT25001\n");
        for (int i = 0; i < 25_001; i++) qif.append("SFood\n$1\n");
        qif.append("^\n");
        assertInvalid(qif.toString());
    }

    @Test
    void parsesCashAndCreditCardAccountsAndTheirTransactions() {
        String qif = "!Account\nNCash\nTCash\n^\n!Type:Cash\nD2024/01/01\nT-1\n^\n"
                + "!Account\nNCard\nTCCard\n^\n!Type:CCard\nD2024/01/02\nT-2\n^\n";
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.accounts()).extracting(ParsedHomeBankData.SourceAccount::type)
                .containsExactly("cash", "creditcard");
        assertThat(parsed.transactions()).hasSize(2);
    }

    @Test
    void rejectsExcessiveBlankLinesWithoutSplittingTheWholeSource() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-1\n^\n"
                + "\n".repeat(500_001);
        assertInvalid(qif);
    }

    private static final String AMBIGUOUS_DATES =
            "QIF dates are ambiguous between day/month/year and month/day/year";
    private static final String UNSUPPORTED_DATES =
            "QIF dates must all use one format: yyyy/MM/dd, dd/MM/yyyy or MM/dd/yyyy";

    @Test
    void keepsSourceIdsOfInFileTransfersAndOtherRowsUnchanged() throws Exception {
        ParsedHomeBankData parsed = parser.parse(fixture(), "EUR");
        assertThat(parsed.transactions()).extracting(ParsedHomeBankData.SourceTransaction::id).containsExactly(
                "4460717b-135d-399f-b94a-cd209584e08b", "ca113258-5dfc-31a4-9332-d2a0489ca04b",
                "4861a933-68fe-3d4f-9390-819050a2dcaf", "5ca12e73-e24b-3bf9-9fdd-4d54ba27fdbf",
                "2a6d22bd-ebfd-38cb-9967-654ebf8a27b1", "176ca3a3-e0cf-38b7-9deb-190c301368de",
                "fdf45f83-af71-390c-ba1c-f106612219ca");
    }

    @Test
    void importsTransferToAccountMissingFromFileAsUncategorisedRowKeepingTargetName() {
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\n"
                + "D2024/01/05\nT-3.10\nPOut\nL[Elsewhere]\n^\n"
                + "D2024/01/06\nT7.25\nMhello\nL[Elsewhere]\n^\n"
                + "D2024/01/07\nT-1\nL[Elsewhere]\n^\n";
        ParsedHomeBankData first = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        ParsedHomeBankData again = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");

        assertThat(first.categories()).isEmpty();
        assertThat(first.transactions()).hasSize(3).allSatisfy(transaction -> {
            assertThat(transaction.transferAccountId()).isNull();
            assertThat(transaction.categoryId()).isNull();
        });
        assertThat(first.transactions().get(0).amount()).isEqualByComparingTo("-3.10");
        assertThat(first.transactions().get(0).payee()).isEqualTo("Out");
        assertThat(first.transactions().get(0).notes()).isEqualTo("Transfer: Elsewhere");
        assertThat(first.transactions().get(1).notes()).isEqualTo("hello — Transfer: Elsewhere");
        assertThat(first.transactions().get(2).notes()).isEqualTo("Transfer: Elsewhere");
        assertThat(first.transactions()).extracting(ParsedHomeBankData.SourceTransaction::id)
                .containsExactlyElementsOf(again.transactions().stream()
                        .map(ParsedHomeBankData.SourceTransaction::id).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void omitsTransferLabelInsteadOfExceedingTheNoteLimit() {
        String longName = "n".repeat(250);
        String qif = "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/05\nT-1\nL[" + longName + "]\n^\n";
        assertThat(parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR").transactions())
                .singleElement().satisfies(transaction -> assertThat(transaction.notes()).isNull());
        String memo = "m".repeat(240);
        String withMemo = "!Account\nNChecking\nTBank\n^\n!Type:Bank\nD2024/01/05\nT-1\nM" + memo
                + "\nL[Elsewhere]\n^\n";
        assertThat(parser.parse(withMemo.getBytes(StandardCharsets.UTF_8), "EUR").transactions())
                .singleElement().satisfies(transaction -> assertThat(transaction.notes()).isEqualTo(memo));
    }

    @Test
    void parsesDayFirstDatesWhenADayExceedsTwelve() {
        String qif = dated("15/01/2024", "02/03/2024");
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.transactions()).extracting(t -> t.date().toString())
                .containsExactly("2024-01-15", "2024-03-02");
    }

    @Test
    void parsesMonthFirstDatesWhenADayExceedsTwelve() {
        String qif = dated("01/15/2024", "03/02/2024");
        ParsedHomeBankData parsed = parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR");
        assertThat(parsed.transactions()).extracting(t -> t.date().toString())
                .containsExactly("2024-01-15", "2024-03-02");
    }

    @Test
    void acceptsDashAndDotSeparatorsAndSingleDigitComponents() {
        assertThat(parser.parse(dated("15-1-2024", "2.3.2024").getBytes(StandardCharsets.UTF_8), "EUR")
                .transactions()).extracting(t -> t.date().toString())
                .containsExactly("2024-01-15", "2024-03-02");
        assertThat(parser.parse(dated("2024-01-15", "2024.03.02").getBytes(StandardCharsets.UTF_8), "EUR")
                .transactions()).extracting(t -> t.date().toString())
                .containsExactly("2024-01-15", "2024-03-02");
    }

    @Test
    void sameRowsInAnyDateFormatProduceTheSameSourceIds() {
        List<String> ids = List.of(dated("2024/01/15", "2024/03/02"), dated("15/01/2024", "02/03/2024"),
                        dated("01/15/2024", "03/02/2024")).stream()
                .map(qif -> parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR").transactions().stream()
                        .map(ParsedHomeBankData.SourceTransaction::id).toList().toString()).toList();
        assertThat(ids).containsOnly(ids.get(0));
    }

    @Test
    void rejectsDatesAmbiguousBetweenDayFirstAndMonthFirst() {
        assertInvalidWith(dated("01/02/2024", "03/04/2024"), AMBIGUOUS_DATES);
        assertInvalidWith(dated("01/02/2024"), AMBIGUOUS_DATES);
    }

    @Test
    void acceptsDayFirstAndMonthFirstDatesWhoseReadingsCoincide() {
        ParsedHomeBankData parsed = parser.parse(dated("05/05/2024", "12/12/2024").getBytes(StandardCharsets.UTF_8),
                "EUR");
        assertThat(parsed.transactions()).extracting(t -> t.date().toString())
                .containsExactly("2024-05-05", "2024-12-12");
    }

    @Test
    void rejectsMixedOrUnsupportedDateFormatsWithFixedMessage() {
        assertInvalidWith(dated("2024/01/15", "15/01/2024"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("15/01/2024", "01/15/2024"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("15/01/24"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("1/15'24"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("not a date"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("2024/02/30"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("31/02/2024"), UNSUPPORTED_DATES);
        assertInvalidWith(dated("02/31/2024"), UNSUPPORTED_DATES);
    }

    private static String dated(String... dates) {
        StringBuilder qif = new StringBuilder("!Account\nNChecking\nTBank\n^\n!Type:Bank\n");
        for (String date : dates) qif.append('D').append(date).append("\nT-1.00\nPSame\n^\n");
        return qif.toString();
    }

    private void assertInvalidWith(String qif, String message) {
        assertThatThrownBy(() -> parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private static byte[] fixture() throws Exception {
        try (InputStream input = HomeBankQifParserTest.class.getResourceAsStream("/imports/homebank/desktop-synthetic.qif")) {
            if (input == null) throw new IllegalStateException("Synthetic QIF fixture missing");
            return input.readAllBytes();
        }
    }

    private void assertInvalid(String qif) {
        assertThatThrownBy(() -> parser.parse(qif.getBytes(StandardCharsets.UTF_8), "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Secret Account");
    }
}
