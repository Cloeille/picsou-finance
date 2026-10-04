package com.picsou.imports.actual;

import com.picsou.imports.actual.ParsedActualBudget.Kind;
import com.picsou.imports.actual.ParsedActualBudget.SourceAccount;
import com.picsou.imports.actual.ParsedActualBudget.SourceCategory;
import com.picsou.imports.actual.ParsedActualBudget.SourceTransaction;
import org.sqlite.SQLiteConfig;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.picsou.imports.actual.ActualBudgetFileParser.bad;

/**
 * Reads an Actual Budget {@code db.sqlite} straight from its tables (the {@code v_*} views are
 * created by the Actual client at runtime and may be absent or stale in an export).
 *
 * <p>Schema facts this relies on, from Actual's {@code loot-core} schema: amounts are signed
 * integers in hundredths of the currency unit (outflows negative); dates are {@code YYYYMMDD}
 * integers with no time or zone; {@code transactions.description} holds the payee id;
 * {@code acct} the account id; split children carry {@code isChild = 1} and point at their
 * {@code isParent = 1} parent; a transfer leg points at its twin through {@code transferred_id}
 * and uses a payee whose {@code transfer_acct} is the other account; deleted rows keep
 * {@code tombstone = 1}; merged categories and payees resolve through {@code category_mapping}
 * and {@code payee_mapping}.
 */
class ActualBudgetDatabaseReader {

    private static final int MAX_ACCOUNTS = 100;
    private static final int MAX_CATEGORIES = 500;
    private static final int MAX_TRANSACTIONS = 200_000;
    private static final int MAX_TEXT = 255;
    private static final int MAX_ACCOUNT_ID = 80;
    private static final int MAX_CATEGORY_ID = 40;
    private static final int MAX_TRANSACTION_ID = 200;
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "accounts", Set.of("id", "name", "offbudget", "closed", "tombstone"),
            "category_groups", Set.of("id", "name", "is_income", "tombstone"),
            "categories", Set.of("id", "name", "is_income", "cat_group", "tombstone"),
            "payees", Set.of("id", "name", "transfer_acct", "tombstone"),
            "transactions", Set.of("id", "acct", "category", "amount", "description", "notes", "date",
                    "isParent", "isChild", "tombstone", "transferred_id", "starting_balance_flag"));

    /** The tables present, and whether split children carry an explicit {@code parent_id}. */
    private record Schema(Set<String> tables, boolean hasParentId) { }

    private record AccountRow(String id, String name, boolean offBudget, boolean closed, boolean tombstone) { }

    private record GroupRow(String name, boolean tombstone) { }

    private record PayeeRow(String name, String transferAccountId) { }

    private record TransactionRow(String id, String accountId, String categoryId, Object amount, String payeeId,
                                  String notes, Object date, boolean parent, boolean child, String parentId,
                                  boolean tombstone, String transferredId, boolean startingBalance) { }

    ParsedActualBudget read(Path database) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = config.createConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA trusted_schema = OFF");
            return readBudget(statement, requireSchema(connection));
        } catch (SQLException e) {
            throw bad("The file is not a readable Actual Budget database");
        }
    }

    private static Schema requireSchema(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> tables = new HashSet<>();
        try (ResultSet rs = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        for (Map.Entry<String, Set<String>> required : REQUIRED_COLUMNS.entrySet()) {
            if (!tables.contains(required.getKey())) {
                throw bad("Not an Actual Budget database: table '" + required.getKey() + "' is missing");
            }
            Set<String> columns = columns(metadata, required.getKey());
            for (String column : required.getValue()) {
                if (!columns.contains(column)) {
                    throw bad("Unsupported Actual Budget database: column '" + required.getKey() + "."
                            + column + "' is missing");
                }
            }
        }
        return new Schema(tables, columns(metadata, "transactions").contains("parent_id"));
    }

    private static Set<String> columns(DatabaseMetaData metadata, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = metadata.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private ParsedActualBudget readBudget(Statement statement, Schema schema) throws SQLException {
        Map<String, AccountRow> allAccounts = readAccounts(statement);
        List<SourceAccount> accounts = allAccounts.values().stream()
                .filter(account -> !account.tombstone())
                .map(account -> new SourceAccount(account.id(), account.name(), account.offBudget(), account.closed()))
                .toList();
        if (accounts.isEmpty()) {
            throw bad("The Actual Budget file contains no accounts");
        }
        if (accounts.size() > MAX_ACCOUNTS) {
            throw bad("The Actual Budget file has more than " + MAX_ACCOUNTS + " accounts");
        }

        Map<String, SourceCategory> categories = readCategories(statement);
        Map<String, String> categoryMapping = schema.tables().contains("category_mapping")
                ? readMapping(statement, "SELECT id, transferId FROM category_mapping") : Map.of();
        Map<String, PayeeRow> payees = readPayees(statement, allAccounts);
        Map<String, String> payeeMapping = schema.tables().contains("payee_mapping")
                ? readMapping(statement, "SELECT id, targetId FROM payee_mapping") : Map.of();

        List<SourceTransaction> transactions = resolveTransactions(
                readTransactions(statement, schema.hasParentId()),
                accounts.stream().map(SourceAccount::id).collect(Collectors.toSet()),
                categories, categoryMapping, payees, payeeMapping);

        String currency = schema.tables().contains("preferences") ? readCurrency(statement) : null;
        return new ParsedActualBudget(currency, accounts, List.copyOf(categories.values()), transactions);
    }

    private static Map<String, AccountRow> readAccounts(Statement statement) throws SQLException {
        Map<String, AccountRow> accounts = new LinkedHashMap<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT id, name, offbudget, closed, tombstone FROM accounts ORDER BY rowid")) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (id == null) {
                    continue;
                }
                requireIdLength(id, MAX_ACCOUNT_ID);
                accounts.put(id, new AccountRow(id, nameOr(rs.getString("name"), "Actual account"),
                        flag(rs, "offbudget"), flag(rs, "closed"), flag(rs, "tombstone")));
                if (accounts.size() > MAX_ACCOUNTS * 10) {
                    throw bad("The Actual Budget file has too many accounts");
                }
            }
        }
        return accounts;
    }

    private static Map<String, SourceCategory> readCategories(Statement statement) throws SQLException {
        Map<String, GroupRow> groups = new HashMap<>();
        try (ResultSet rs = statement.executeQuery("SELECT id, name, tombstone FROM category_groups")) {
            while (rs.next()) {
                if (rs.getString("id") != null) {
                    groups.put(rs.getString("id"), new GroupRow(text(rs.getString("name")), flag(rs, "tombstone")));
                }
            }
        }
        Map<String, SourceCategory> categories = new LinkedHashMap<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT id, name, is_income, cat_group, tombstone FROM categories ORDER BY rowid")) {
            while (rs.next()) {
                String id = rs.getString("id");
                GroupRow group = groups.get(rs.getString("cat_group"));
                if (id == null || flag(rs, "tombstone") || group != null && group.tombstone()) {
                    continue;
                }
                requireIdLength(id, MAX_CATEGORY_ID);
                if (rs.getString("cat_group") != null) {
                    requireIdLength(rs.getString("cat_group"), MAX_CATEGORY_ID);
                }
                categories.put(id, new SourceCategory(id, nameOr(rs.getString("name"), "Actual category"),
                        rs.getString("cat_group"), group == null ? null : group.name(), flag(rs, "is_income")));
                if (categories.size() > MAX_CATEGORIES) {
                    throw bad("The Actual Budget file has more than " + MAX_CATEGORIES + " categories");
                }
            }
        }
        return categories;
    }

    private static Map<String, PayeeRow> readPayees(Statement statement, Map<String, AccountRow> accounts)
            throws SQLException {
        Map<String, PayeeRow> payees = new HashMap<>();
        try (ResultSet rs = statement.executeQuery("SELECT id, name, transfer_acct FROM payees")) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (id == null) {
                    continue;
                }
                String transferAccount = rs.getString("transfer_acct");
                AccountRow other = transferAccount == null ? null : accounts.get(transferAccount);
                String name = other != null ? other.name() : text(rs.getString("name"));
                payees.put(id, new PayeeRow(name, transferAccount));
            }
        }
        return payees;
    }

    private static Map<String, String> readMapping(Statement statement, String sql) throws SQLException {
        Map<String, String> mapping = new HashMap<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                if (rs.getString(1) != null && rs.getString(2) != null) {
                    mapping.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return mapping;
    }

    private static List<TransactionRow> readTransactions(Statement statement, boolean hasParentId)
            throws SQLException {
        List<TransactionRow> rows = new ArrayList<>();
        String parentColumn = hasParentId ? "parent_id" : "NULL AS parent_id";
        try (ResultSet rs = statement.executeQuery("SELECT id, acct, category, amount, description, notes, date, "
                + "isParent, isChild, " + parentColumn + ", tombstone, transferred_id, starting_balance_flag "
                + "FROM transactions ORDER BY date, rowid")) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (id == null) {
                    continue;
                }
                requireIdLength(id, MAX_TRANSACTION_ID);
                boolean child = flag(rs, "isChild");
                String parentId = rs.getString("parent_id");
                if (child && parentId == null && id.contains("/")) {
                    // Splits written before the parent_id column encoded the parent in the child id.
                    parentId = id.substring(0, id.indexOf('/'));
                }
                rows.add(new TransactionRow(id, rs.getString("acct"), rs.getString("category"),
                        rs.getObject("amount"), rs.getString("description"), rs.getString("notes"),
                        rs.getObject("date"), flag(rs, "isParent"), child, parentId, flag(rs, "tombstone"),
                        rs.getString("transferred_id"), flag(rs, "starting_balance_flag")));
                if (rows.size() > MAX_TRANSACTIONS * 2) {
                    throw bad("The Actual Budget file has too many transactions");
                }
            }
        }
        return rows;
    }

    /**
     * Applies Actual's own visibility rules. A split is imported as its children, not its parent:
     * the children carry the categories, they sum to the parent, and Actual computes balances from
     * non-parent rows the same way. A parent whose children are all deleted is imported as is.
     */
    private static List<SourceTransaction> resolveTransactions(List<TransactionRow> rows, Set<String> liveAccounts,
            Map<String, SourceCategory> categories, Map<String, String> categoryMapping,
            Map<String, PayeeRow> payees, Map<String, String> payeeMapping) {
        Map<String, TransactionRow> parents = new HashMap<>();
        Map<String, Integer> liveChildren = new HashMap<>();
        for (TransactionRow row : rows) {
            if (row.parent()) {
                parents.put(row.id(), row);
            }
        }
        for (TransactionRow row : rows) {
            if (row.child() && !row.tombstone() && row.parentId() != null) {
                liveChildren.merge(row.parentId(), 1, Integer::sum);
            }
        }

        List<SourceTransaction> transactions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (TransactionRow row : rows) {
            if (!ids.add(row.id())) {
                throw bad("The Actual Budget file has duplicate transaction ids");
            }
            if (row.tombstone() || !liveAccounts.contains(row.accountId())) {
                continue;
            }
            if (row.parent() && liveChildren.getOrDefault(row.id(), 0) > 0) {
                continue;
            }
            TransactionRow parent = row.child() ? parents.get(row.parentId()) : null;
            if (row.child() && (parent == null || parent.tombstone())) {
                continue;
            }
            PayeeRow payee = resolve(row.payeeId(), payees, payeeMapping);
            if (payee == null && parent != null) {
                payee = resolve(parent.payeeId(), payees, payeeMapping);
            }
            String notes = row.notes() != null ? row.notes() : parent == null ? null : parent.notes();
            boolean transfer = row.transferredId() != null || payee != null && payee.transferAccountId() != null;
            Kind kind = row.startingBalance() ? Kind.STARTING_BALANCE : transfer ? Kind.TRANSFER : Kind.REGULAR;
            String categoryId = kind == Kind.REGULAR ? resolveCategory(row.categoryId(), categories, categoryMapping)
                    : null;
            transactions.add(new SourceTransaction(row.id(), row.accountId(), date(row.date()),
                    amount(row.amount()), payee == null ? null : payee.name(), text(notes), categoryId, kind));
            if (transactions.size() > MAX_TRANSACTIONS) {
                throw bad("The Actual Budget file has more than " + MAX_TRANSACTIONS + " transactions");
            }
        }
        return transactions;
    }

    private static PayeeRow resolve(String payeeId, Map<String, PayeeRow> payees, Map<String, String> mapping) {
        if (payeeId == null) {
            return null;
        }
        return payees.get(mapping.getOrDefault(payeeId, payeeId));
    }

    private static String resolveCategory(String categoryId, Map<String, SourceCategory> categories,
            Map<String, String> mapping) {
        if (categoryId == null) {
            return null;
        }
        String target = mapping.getOrDefault(categoryId, categoryId);
        return categories.containsKey(target) ? target : null;
    }

    private static String readCurrency(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
                "SELECT value FROM preferences WHERE id = 'defaultCurrencyCode'")) {
            if (rs.next() && rs.getString(1) != null) {
                String value = rs.getString(1).trim().toUpperCase(Locale.ROOT);
                return CURRENCY.matcher(value).matches() ? value : null;
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /** {@code YYYYMMDD} is a calendar date: built as a {@link LocalDate}, never through a zone. */
    private static LocalDate date(Object value) {
        long raw = integer(value, "date");
        try {
            LocalDate date = LocalDate.of((int) (raw / 10_000), (int) (raw / 100 % 100), (int) (raw % 100));
            if (date.getYear() < 1900 || date.getYear() > 2200) {
                throw bad("Invalid transaction date in the Actual Budget file");
            }
            return date;
        } catch (DateTimeException e) {
            throw bad("Invalid transaction date in the Actual Budget file");
        }
    }

    /** Integer hundredths, kept signed: an outflow stays negative. */
    private static BigDecimal amount(Object value) {
        BigDecimal amount = BigDecimal.valueOf(integer(value, "amount"), 2);
        if (amount.abs().compareTo(MAX_AMOUNT) > 0) {
            throw bad("Transaction amount out of range in the Actual Budget file");
        }
        return amount;
    }

    private static long integer(Object value, String field) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        try {
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).longValueExact();
            }
            if (value instanceof String string) {
                return new BigDecimal(string.trim()).longValueExact();
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            // falls through to the rejection below
        }
        throw bad("Invalid transaction " + field + " in the Actual Budget file");
    }

    /** Source ids become Picsou external ids and slugs, whose columns are bounded. */
    private static void requireIdLength(String id, int max) {
        if (id.length() > max) {
            throw bad("Unsupported identifier in the Actual Budget file");
        }
    }

    private static boolean flag(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    private static String nameOr(String value, String fallback) {
        String text = text(value);
        return text == null || text.isBlank() ? fallback : text.strip();
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }
}
