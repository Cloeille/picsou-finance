package com.picsou.imports.actual;

import com.picsou.imports.actual.ParsedActualBudget.Kind;
import com.picsou.imports.actual.ParsedActualBudget.SourceAccount;
import com.picsou.imports.actual.ParsedActualBudget.SourceCategory;
import com.picsou.imports.actual.ParsedActualBudget.SourceTransaction;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteLimits;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 *
 * <p>The file is user-supplied, so every read is bounded before it reaches the heap: SQLite
 * refuses any single value over {@link #MAX_VALUE_BYTES}, each table's row count is checked
 * before its rows are read, text is cut in SQL to the width Picsou stores, and computed
 * (generated) columns and non-plain tables are refused because they can synthesise data the
 * file never held.
 */
class ActualBudgetDatabaseReader {

    private static final int MAX_ACCOUNTS = 100;
    private static final int MAX_CATEGORIES = 500;
    private static final int MAX_TRANSACTIONS = 200_000;
    private static final int MAX_TEXT = 255;
    private static final int MAX_ACCOUNT_ID = 80;
    private static final int MAX_CATEGORY_ID = 40;
    private static final int MAX_PAYEE_ID = 80;
    private static final int MAX_TRANSACTION_ID = 200;
    private static final int MAX_NUMBER_TEXT = 32;
    /** No Actual value comes near this; a larger one fails inside SQLite before it is copied out. */
    static final int MAX_VALUE_BYTES = 1024 * 1024;
    /**
     * Stored rows per table, deleted ones included. A heavy decade-long budget holds tens of
     * thousands of transactions, a few thousand payees and some hundred categories; these caps
     * leave an order of magnitude of headroom over that, and the transaction cap leaves room for
     * tombstones and split parents above the 200k live rows imported.
     */
    static final Map<String, Integer> MAX_ROWS = Map.of(
            "accounts", 10_000,
            "category_groups", 10_000,
            "categories", 10_000,
            "category_mapping", 50_000,
            "payees", 50_000,
            "payee_mapping", 50_000,
            "transactions", 300_000);
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

    /** Tables read only when present; their columns are then required like the others. */
    private static final Map<String, Set<String>> OPTIONAL_COLUMNS = Map.of(
            "category_mapping", Set.of("id", "transferId"),
            "payee_mapping", Set.of("id", "targetId"));

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
            connection.unwrap(SQLiteConnection.class).setLimit(SQLiteLimits.SQLITE_LIMIT_LENGTH, MAX_VALUE_BYTES);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA trusted_schema = OFF");
            Schema schema = requireSchema(connection);
            requireRowCounts(statement, schema);
            return readBudget(statement, schema);
        } catch (SQLException e) {
            if (e.getErrorCode() == SQLiteErrorCode.SQLITE_TOOBIG.code) {
                throw bad("The Actual Budget file holds a value larger than 1 MiB");
            }
            throw bad("The file is not a readable Actual Budget database");
        }
    }

    private static Schema requireSchema(Connection connection) throws SQLException {
        Map<String, String> tableTypes = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name, type FROM pragma_table_list WHERE schema = 'main'")) {
            while (rs.next()) {
                tableTypes.put(rs.getString("name"), rs.getString("type"));
            }
        }
        for (String table : REQUIRED_COLUMNS.keySet()) {
            if (!tableTypes.containsKey(table)) {
                throw bad("Not an Actual Budget database: table '" + table + "' is missing");
            }
        }
        Map<String, Set<String>> read = new HashMap<>(REQUIRED_COLUMNS);
        OPTIONAL_COLUMNS.forEach((table, columns) -> {
            if (tableTypes.containsKey(table)) {
                read.put(table, columns);
            }
        });
        boolean hasParentId = false;
        for (Map.Entry<String, Set<String>> table : read.entrySet()) {
            if (!"table".equals(tableTypes.get(table.getKey()))) {
                throw bad("Unsupported Actual Budget database: '" + table.getKey() + "' is not a plain table");
            }
            Map<String, Boolean> columns = columns(connection, table.getKey());
            for (String column : table.getValue()) {
                requireStoredColumn(columns, table.getKey(), column);
            }
            if (table.getKey().equals("transactions") && columns.containsKey("parent_id")) {
                requireStoredColumn(columns, "transactions", "parent_id");
                hasParentId = true;
            }
        }
        return new Schema(tableTypes.keySet(), hasParentId);
    }

    /** Column name to whether its value is computed (a generated or hidden column). */
    private static Map<String, Boolean> columns(Connection connection, String table) throws SQLException {
        Map<String, Boolean> columns = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, hidden FROM pragma_table_xinfo(?)")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.put(rs.getString("name"), rs.getInt("hidden") != 0);
                }
            }
        }
        return columns;
    }

    private static void requireStoredColumn(Map<String, Boolean> columns, String table, String column) {
        Boolean computed = columns.get(column);
        if (computed == null) {
            throw bad("Unsupported Actual Budget database: column '" + table + "." + column + "' is missing");
        }
        if (computed) {
            throw bad("Unsupported Actual Budget database: column '" + table + "." + column + "' is generated");
        }
    }

    /** Counted before reading, so an oversized table is refused without loading a row of it. */
    private static void requireRowCounts(Statement statement, Schema schema) throws SQLException {
        for (Map.Entry<String, Integer> cap : MAX_ROWS.entrySet()) {
            if (!schema.tables().contains(cap.getKey())) {
                continue;
            }
            try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + cap.getKey())) {
                if (rs.next() && rs.getLong(1) > cap.getValue()) {
                    throw bad("The Actual Budget file has more than " + cap.getValue() + " rows in '"
                            + cap.getKey() + "'");
                }
            }
        }
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
                ? readMapping(statement, "category_mapping", "transferId", MAX_CATEGORY_ID) : Map.of();
        Map<String, PayeeRow> payees = readPayees(statement, allAccounts);
        Map<String, String> payeeMapping = schema.tables().contains("payee_mapping")
                ? readMapping(statement, "payee_mapping", "targetId", MAX_PAYEE_ID) : Map.of();

        List<SourceTransaction> transactions = resolveTransactions(
                readTransactions(statement, schema.hasParentId()),
                accounts.stream().map(SourceAccount::id).collect(Collectors.toSet()),
                categories, categoryMapping, payees, payeeMapping);

        String currency = schema.tables().contains("preferences") ? readCurrency(statement) : null;
        return new ParsedActualBudget(currency, accounts, List.copyOf(categories.values()), transactions);
    }

    private static Map<String, AccountRow> readAccounts(Statement statement) throws SQLException {
        Map<String, AccountRow> accounts = new LinkedHashMap<>();
        try (ResultSet rs = statement.executeQuery("SELECT " + id("id", MAX_ACCOUNT_ID) + ", "
                + text("name") + ", offbudget, closed, tombstone FROM accounts ORDER BY rowid")) {
            while (rs.next()) {
                String id = id(rs, "id", MAX_ACCOUNT_ID);
                if (id == null) {
                    continue;
                }
                accounts.put(id, new AccountRow(id, nameOr(rs.getString("name"), "Actual account"),
                        flag(rs, "offbudget"), flag(rs, "closed"), flag(rs, "tombstone")));
            }
        }
        return accounts;
    }

    private static Map<String, SourceCategory> readCategories(Statement statement) throws SQLException {
        Map<String, GroupRow> groups = new HashMap<>();
        try (ResultSet rs = statement.executeQuery("SELECT " + id("id", MAX_CATEGORY_ID) + ", " + text("name")
                + ", tombstone FROM category_groups")) {
            while (rs.next()) {
                String id = id(rs, "id", MAX_CATEGORY_ID);
                if (id != null) {
                    groups.put(id, new GroupRow(rs.getString("name"), flag(rs, "tombstone")));
                }
            }
        }
        Map<String, SourceCategory> categories = new LinkedHashMap<>();
        try (ResultSet rs = statement.executeQuery("SELECT " + id("id", MAX_CATEGORY_ID) + ", " + text("name")
                + ", is_income, " + id("cat_group", MAX_CATEGORY_ID) + ", tombstone FROM categories ORDER BY rowid")) {
            while (rs.next()) {
                String id = id(rs, "id", MAX_CATEGORY_ID);
                String groupId = id(rs, "cat_group", MAX_CATEGORY_ID);
                GroupRow group = groups.get(groupId);
                if (id == null || flag(rs, "tombstone") || group != null && group.tombstone()) {
                    continue;
                }
                categories.put(id, new SourceCategory(id, nameOr(rs.getString("name"), "Actual category"),
                        groupId, group == null ? null : group.name(), flag(rs, "is_income")));
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
        try (ResultSet rs = statement.executeQuery("SELECT " + id("id", MAX_PAYEE_ID) + ", " + text("name") + ", "
                + id("transfer_acct", MAX_ACCOUNT_ID) + " FROM payees")) {
            while (rs.next()) {
                String id = id(rs, "id", MAX_PAYEE_ID);
                if (id == null) {
                    continue;
                }
                String transferAccount = id(rs, "transfer_acct", MAX_ACCOUNT_ID);
                AccountRow other = transferAccount == null ? null : accounts.get(transferAccount);
                String name = other != null ? other.name() : rs.getString("name");
                payees.put(id, new PayeeRow(name, transferAccount));
            }
        }
        return payees;
    }

    private static Map<String, String> readMapping(Statement statement, String table, String targetColumn,
            int maxId) throws SQLException {
        Map<String, String> mapping = new HashMap<>();
        try (ResultSet rs = statement.executeQuery("SELECT " + id("id", maxId) + ", " + id(targetColumn, maxId)
                + " FROM " + table)) {
            while (rs.next()) {
                String id = id(rs, "id", maxId);
                String target = id(rs, targetColumn, maxId);
                if (id != null && target != null) {
                    mapping.put(id, target);
                }
            }
        }
        return mapping;
    }

    private static List<TransactionRow> readTransactions(Statement statement, boolean hasParentId)
            throws SQLException {
        List<TransactionRow> rows = new ArrayList<>();
        String parentColumn = hasParentId ? id("parent_id", MAX_TRANSACTION_ID) : "NULL AS parent_id";
        try (ResultSet rs = statement.executeQuery("SELECT " + id("id", MAX_TRANSACTION_ID) + ", "
                + id("acct", MAX_ACCOUNT_ID) + ", " + id("category", MAX_CATEGORY_ID) + ", "
                + number("amount") + ", " + id("description", MAX_PAYEE_ID) + ", " + text("notes") + ", "
                + number("date") + ", isParent, isChild, " + parentColumn + ", tombstone, "
                + id("transferred_id", MAX_TRANSACTION_ID) + ", starting_balance_flag "
                + "FROM transactions ORDER BY date, rowid")) {
            while (rs.next()) {
                String id = id(rs, "id", MAX_TRANSACTION_ID);
                if (id == null) {
                    continue;
                }
                boolean child = flag(rs, "isChild");
                String parentId = id(rs, "parent_id", MAX_TRANSACTION_ID);
                if (child && parentId == null && id.contains("/")) {
                    // Splits written before the parent_id column encoded the parent in the child id.
                    parentId = id.substring(0, id.indexOf('/'));
                }
                rows.add(new TransactionRow(id, id(rs, "acct", MAX_ACCOUNT_ID), id(rs, "category", MAX_CATEGORY_ID),
                        rs.getObject("amount"), id(rs, "description", MAX_PAYEE_ID), rs.getString("notes"),
                        rs.getObject("date"), flag(rs, "isParent"), child, parentId, flag(rs, "tombstone"),
                        id(rs, "transferred_id", MAX_TRANSACTION_ID), flag(rs, "starting_balance_flag")));
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
                    amount(row.amount()), payee == null ? null : payee.name(), notes, categoryId, kind));
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
                "SELECT substr(value, 1, 8) FROM preferences WHERE id = 'defaultCurrencyCode'")) {
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
            if (value instanceof String string && string.length() <= MAX_NUMBER_TEXT) {
                return new BigDecimal(string.trim()).longValueExact();
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            // falls through to the rejection below
        }
        throw bad("Invalid transaction " + field + " in the Actual Budget file");
    }

    /** Selects an id cut one past its cap, so an overlong one is detected without being copied out. */
    private static String id(String column, int max) {
        return "substr(" + column + ", 1, " + (max + 1) + ") AS " + column;
    }

    /** Selects free text cut to the width Picsou stores. */
    private static String text(String column) {
        return "substr(" + column + ", 1, " + MAX_TEXT + ") AS " + column;
    }

    /** Keeps numbers as numbers; text in a numeric column is cut, since no valid number is longer. */
    private static String number(String column) {
        return "CASE WHEN typeof(" + column + ") IN ('integer', 'real') THEN " + column
                + " ELSE substr(" + column + ", 1, " + (MAX_NUMBER_TEXT + 1) + ") END AS " + column;
    }

    /** Source ids become Picsou external ids and slugs, whose columns are bounded. */
    private static String id(ResultSet rs, String column, int max) throws SQLException {
        String id = rs.getString(column);
        if (id != null && id.length() > max) {
            throw bad("Unsupported identifier in the Actual Budget file");
        }
        return id;
    }

    private static boolean flag(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    private static String nameOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
