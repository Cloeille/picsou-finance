package com.picsou.imports.actual;

import com.picsou.imports.actual.ParsedActualBudget.Kind;
import com.picsou.imports.actual.ParsedActualBudget.SourceAccount;
import com.picsou.imports.actual.ParsedActualBudget.SourceCategory;
import com.picsou.imports.actual.ParsedActualBudget.SourceTransaction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActualBudgetFileParserTest {

    private final ActualBudgetFileParser parser = new ActualBudgetFileParser();

    @Test
    void readsLiveAccountsCategoriesAndCurrency() {
        ParsedActualBudget budget = parser.parse(ActualBudgetFixture.household().zip());

        assertThat(budget.currency()).isEqualTo("EUR");
        assertThat(budget.accounts()).containsExactly(
                new SourceAccount("acc-checking", "Everyday", false, false),
                new SourceAccount("acc-savings", "Rainy day", true, false));
        assertThat(budget.categories()).containsExactly(
                new SourceCategory("cat-groceries", "Groceries", "grp-food", "Food", false),
                new SourceCategory("cat-restaurants", "Restaurants", "grp-food", "Food", false),
                new SourceCategory("cat-salary", "Salary", "grp-income", "Income", true),
                new SourceCategory("cat-starting", "Starting Balances", "grp-income", "Income", true));
    }

    @Test
    void keepsAmountsSignedAndDatesOnTheirCalendarDay() {
        Map<String, SourceTransaction> byId = byId(parser.parse(ActualBudgetFixture.household().sqlite()));

        assertThat(byId.get("t-groceries").amount()).isEqualTo(new BigDecimal("-12.34"));
        assertThat(byId.get("t-groceries").date()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(byId.get("t-salary").amount()).isEqualTo(new BigDecimal("2500.00"));
        assertThat(byId.get("t-salary").date()).isEqualTo(LocalDate.of(2024, 1, 31));
        assertThat(byId.get("t-salary").notes()).isEqualTo("January pay");
        assertThat(byId.get("t-salary").payee()).isEqualTo("Employer");
        assertThat(byId.get("t-salary").categoryId()).isEqualTo("cat-salary");
    }

    @Test
    void importsSplitChildrenInsteadOfTheirParentAndDropsDeletedRows() {
        Map<String, SourceTransaction> byId = byId(parser.parse(ActualBudgetFixture.household().zip()));

        assertThat(byId.keySet()).containsExactlyInAnyOrder("t-start", "t-groceries", "t-transfer-out",
                "t-transfer-in", "t-split/1", "t-split/2", "t-lonely-parent", "t-salary");
        assertThat(byId.get("t-split/1")).isEqualTo(new SourceTransaction("t-split/1", "acc-checking",
                LocalDate.of(2024, 1, 20), new BigDecimal("-20.00"), "Market", "Weekly shop", "cat-groceries",
                Kind.REGULAR));
        assertThat(byId.get("t-split/2").notes()).isEqualTo("Lunch");
        assertThat(byId.get("t-lonely-parent").amount()).isEqualTo(new BigDecimal("-42.00"));
    }

    @Test
    void flagsBothTransferLegsAndTheStartingBalance() {
        Map<String, SourceTransaction> byId = byId(parser.parse(ActualBudgetFixture.household().zip()));

        assertThat(byId.get("t-transfer-out")).isEqualTo(new SourceTransaction("t-transfer-out", "acc-checking",
                LocalDate.of(2024, 1, 15), new BigDecimal("-500.00"), "Rainy day", null, null, Kind.TRANSFER));
        assertThat(byId.get("t-transfer-in")).isEqualTo(new SourceTransaction("t-transfer-in", "acc-savings",
                LocalDate.of(2024, 1, 15), new BigDecimal("500.00"), "Everyday", null, null, Kind.TRANSFER));
        assertThat(byId.get("t-start").kind()).isEqualTo(Kind.STARTING_BALANCE);
        assertThat(byId.get("t-start").categoryId()).isNull();
    }

    @Test
    void resolvesMergedPayeesAndCategories() {
        SourceTransaction groceries = byId(parser.parse(ActualBudgetFixture.household().zip())).get("t-groceries");

        assertThat(groceries.payee()).isEqualTo("Market");
        assertThat(groceries.categoryId()).isEqualTo("cat-groceries");
    }

    @Test
    void readsADatabaseLeftInWriteAheadLogMode() {
        byte[] file = ActualBudgetFixture.household().walMode().sqlite();
        assertThat(file[18]).as("header marks the file as WAL").isEqualTo((byte) 2);

        ParsedActualBudget budget = parser.parse(file);

        assertThat(budget.transactions()).hasSize(8);
    }

    @Test
    void leavesTheCurrencyUnsetWhenTheBudgetDoesNotRecordOne() {
        byte[] file = ActualBudgetFixture.empty()
                .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
                .tx("t", "a", 100, 20230615, null, null, null, null)
                .sqlite();

        ParsedActualBudget budget = parser.parse(file);

        assertThat(budget.currency()).isNull();
        assertThat(budget.transactions()).extracting(SourceTransaction::date).containsExactly(LocalDate.of(2023, 6, 15));
    }

    @Test
    void reconstructsTheParentOfLegacySplitIdsWithoutParentIdColumn() {
        byte[] file = ActualBudgetFixture.empty()
                .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
                .sql("INSERT INTO payees (id, name) VALUES ('p', 'Shop')")
                .tx("s", "a", -300, 20230101, null, "p", null, "isParent = 1")
                .tx("s/1", "a", -300, 20230101, null, null, null, "isChild = 1")
                .sqlite();

        List<SourceTransaction> transactions = parser.parse(file).transactions();

        assertThat(transactions).extracting(SourceTransaction::id, SourceTransaction::payee)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("s/1", "Shop"));
    }

    @Test
    void rejectsAFileThatIsNeitherAZipNorASqliteDatabase() {
        assertThatThrownBy(() -> parser.parse("date,amount\n2024-01-01,1".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected an Actual Budget export");
    }

    @Test
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The file is empty");
    }

    @Test
    void rejectsASqliteDatabaseThatIsNotAnActualBudget() {
        byte[] other = ActualBudgetFixture.empty().sql("DROP TABLE payees").sqlite();

        assertThatThrownBy(() -> parser.parse(other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not an Actual Budget database: table 'payees' is missing");
    }

    @Test
    void rejectsAnImpossibleDate() {
        byte[] file = ActualBudgetFixture.empty()
                .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
                .tx("t", "a", 100, 20241332, null, null, null, null)
                .sqlite();

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid transaction date in the Actual Budget file");
    }

    @Test
    void rejectsAFractionalAmount() {
        byte[] file = ActualBudgetFixture.empty()
                .sql("INSERT INTO accounts (id, name) VALUES ('a', 'Cash')")
                .sql("INSERT INTO transactions (id, acct, amount, date) VALUES ('t', 'a', 12.5, 20240101)")
                .sqlite();

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid transaction amount in the Actual Budget file");
    }

    @Test
    void rejectsAZipWithoutADatabase() {
        byte[] zip = ActualBudgetFixture.zip(Map.of("metadata.json", "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> parser.parse(zip))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The archive does not contain an Actual Budget db.sqlite");
    }

    @Test
    void rejectsAZipSlipEntryEvenNextToAValidDatabase() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("db.sqlite", ActualBudgetFixture.household().sqlite());
        entries.put("../../etc/cron.d/evil", "* * * * * root id".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(ActualBudgetFixture.zip(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The Actual Budget archive contains an unsafe path");
    }

    @Test
    void rejectsADatabaseOnlyFoundUnderATraversingPath() {
        byte[] zip = ActualBudgetFixture.zip(Map.of("../db.sqlite", ActualBudgetFixture.household().sqlite()));

        assertThatThrownBy(() -> parser.parse(zip))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The Actual Budget archive contains an unsafe path");
    }

    @Test
    void rejectsAnEntryThatInflatesPastTheCap() {
        ActualBudgetFileParser capped = new ActualBudgetFileParser(32, 64 * 1024, new ActualBudgetDatabaseReader());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.json", new byte[1024 * 1024]);
        entries.put("db.sqlite", ActualBudgetFixture.household().sqlite());
        byte[] zip = ActualBudgetFixture.zip(entries);
        assertThat(zip.length).isLessThan(64 * 1024);

        assertThatThrownBy(() -> capped.parse(zip))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The Actual Budget archive is too large once uncompressed");
    }

    @Test
    void rejectsAnArchiveWithTooManyEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            entries.put("filler-" + i + ".txt", new byte[]{1});
        }
        entries.put("db.sqlite", ActualBudgetFixture.household().sqlite());

        assertThatThrownBy(() -> parser.parse(ActualBudgetFixture.zip(entries)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The Actual Budget archive has too many entries");
    }

    @Test
    void rejectsADatabaseEntryThatIsNotSqlite() {
        byte[] zip = ActualBudgetFixture.zip(Map.of("db.sqlite", "not sqlite".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> parser.parse(zip))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The archive's db.sqlite is not a SQLite database");
    }

    @Test
    void rejectsATruncatedArchive() {
        byte[] zip = ActualBudgetFixture.household().zip();
        byte[] truncated = java.util.Arrays.copyOf(zip, zip.length / 2);

        assertThatThrownBy(() -> parser.parse(truncated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesItsTemporaryCopyAfterSuccessAndFailure() throws IOException {
        long before = temporaryCopies();

        parser.parse(ActualBudgetFixture.household().zip());
        assertThatThrownBy(() -> parser.parse(ActualBudgetFixture.empty().sql("DROP TABLE payees").sqlite()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(temporaryCopies()).isEqualTo(before);
    }

    private static long temporaryCopies() throws IOException {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("picsou-actual-")).count();
        }
    }

    private static Map<String, SourceTransaction> byId(ParsedActualBudget budget) {
        return budget.transactions().stream()
                .collect(Collectors.toMap(SourceTransaction::id, Function.identity()));
    }
}
