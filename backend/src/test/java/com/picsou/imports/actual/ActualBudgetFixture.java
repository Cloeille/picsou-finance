package com.picsou.imports.actual;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds synthetic Actual Budget databases with the tables and columns of Actual's
 * {@code loot-core} schema, so parser and service tests run against a real SQLite file.
 */
public final class ActualBudgetFixture {

    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE accounts (id TEXT PRIMARY KEY, account_id TEXT, name TEXT, balance_current INTEGER,"
                    + " type TEXT, offbudget INTEGER DEFAULT 0, closed INTEGER DEFAULT 0,"
                    + " tombstone INTEGER DEFAULT 0, sort_order REAL)",
            "CREATE TABLE category_groups (id TEXT PRIMARY KEY, name TEXT UNIQUE, is_income INTEGER DEFAULT 0,"
                    + " sort_order REAL, tombstone INTEGER DEFAULT 0, hidden BOOLEAN DEFAULT 0)",
            "CREATE TABLE categories (id TEXT PRIMARY KEY, name TEXT, is_income INTEGER DEFAULT 0, cat_group TEXT,"
                    + " sort_order REAL, tombstone INTEGER DEFAULT 0, hidden BOOLEAN DEFAULT 0)",
            "CREATE TABLE category_mapping (id TEXT PRIMARY KEY, transferId TEXT)",
            "CREATE TABLE payees (id TEXT PRIMARY KEY, name TEXT, category TEXT, tombstone INTEGER DEFAULT 0,"
                    + " transfer_acct TEXT)",
            "CREATE TABLE payee_mapping (id TEXT PRIMARY KEY, targetId TEXT)",
            "CREATE TABLE transactions (id TEXT PRIMARY KEY, isParent INTEGER DEFAULT 0, isChild INTEGER DEFAULT 0,"
                    + " acct TEXT, category TEXT, amount INTEGER, description TEXT, notes TEXT, date INTEGER,"
                    + " financial_id TEXT, type TEXT, location TEXT, error TEXT, imported_description TEXT,"
                    + " starting_balance_flag INTEGER DEFAULT 0, transferred_id TEXT, sort_order REAL,"
                    + " tombstone INTEGER DEFAULT 0, cleared INTEGER DEFAULT 1, pending INTEGER DEFAULT 0,"
                    + " parent_id TEXT)",
            "CREATE TABLE preferences (id TEXT PRIMARY KEY, value TEXT)",
            "CREATE TABLE messages_crdt (id INTEGER PRIMARY KEY, timestamp TEXT, dataset TEXT, row TEXT,"
                    + " column TEXT, value BLOB)");

    private final List<String> statements = new ArrayList<>(SCHEMA);
    private boolean wal;

    public static ActualBudgetFixture empty() {
        return new ActualBudgetFixture();
    }

    /**
     * A small household budget: two live accounts plus a deleted one, a transfer between the live
     * ones, a split with a deleted child, a split whose parent was deleted, merged payees and
     * categories, a starting balance and an EUR currency preference.
     */
    public static ActualBudgetFixture household() {
        return empty()
                .sql("INSERT INTO preferences VALUES ('defaultCurrencyCode', 'EUR')")
                .sql("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES"
                        + " ('acc-checking', 'Everyday', 0, 0, 0),"
                        + " ('acc-savings', 'Rainy day', 1, 0, 0),"
                        + " ('acc-old', 'Old card', 0, 1, 1)")
                .sql("INSERT INTO category_groups (id, name, is_income, tombstone) VALUES"
                        + " ('grp-food', 'Food', 0, 0), ('grp-income', 'Income', 1, 0), ('grp-dead', 'Gone', 0, 1)")
                .sql("INSERT INTO categories (id, name, is_income, cat_group, tombstone) VALUES"
                        + " ('cat-groceries', 'Groceries', 0, 'grp-food', 0),"
                        + " ('cat-restaurants', 'Restaurants', 0, 'grp-food', 0),"
                        + " ('cat-salary', 'Salary', 1, 'grp-income', 0),"
                        + " ('cat-starting', 'Starting Balances', 1, 'grp-income', 0),"
                        + " ('cat-merged', 'Supermarket', 0, 'grp-food', 1),"
                        + " ('cat-orphan', 'Orphan', 0, 'grp-dead', 0)")
                .sql("INSERT INTO category_mapping VALUES ('cat-groceries', 'cat-groceries'),"
                        + " ('cat-restaurants', 'cat-restaurants'), ('cat-salary', 'cat-salary'),"
                        + " ('cat-starting', 'cat-starting'), ('cat-merged', 'cat-groceries')")
                .sql("INSERT INTO payees (id, name, tombstone, transfer_acct) VALUES"
                        + " ('p-market', 'Market', 0, NULL), ('p-employer', 'Employer', 0, NULL),"
                        + " ('p-old-market', 'Old market', 1, NULL), ('p-starting', 'Starting Balance', 0, NULL),"
                        + " ('p-to-savings', '', 0, 'acc-savings'), ('p-to-checking', '', 0, 'acc-checking')")
                .sql("INSERT INTO payee_mapping VALUES ('p-market', 'p-market'), ('p-old-market', 'p-market')")
                .tx("t-start", "acc-checking", 100000, 20240101, "cat-starting", "p-starting", null, "starting_balance_flag = 1")
                .tx("t-groceries", "acc-checking", -1234, 20240102, "cat-merged", "p-old-market", null, null)
                .tx("t-transfer-out", "acc-checking", -50000, 20240115, null, "p-to-savings", null, "transferred_id = 't-transfer-in'")
                .tx("t-transfer-in", "acc-savings", 50000, 20240115, null, "p-to-checking", null, "transferred_id = 't-transfer-out'")
                .tx("t-split", "acc-checking", -3000, 20240120, null, "p-market", "Weekly shop", "isParent = 1")
                .tx("t-split/1", "acc-checking", -2000, 20240120, "cat-groceries", null, null, "isChild = 1, parent_id = 't-split'")
                .tx("t-split/2", "acc-checking", -1000, 20240120, "cat-restaurants", null, "Lunch", "isChild = 1, parent_id = 't-split'")
                .tx("t-split/3", "acc-checking", -999, 20240120, "cat-groceries", null, null, "isChild = 1, parent_id = 't-split', tombstone = 1")
                .tx("t-deleted", "acc-checking", -777, 20240121, "cat-groceries", "p-market", null, "tombstone = 1")
                .tx("t-old-account", "acc-old", -5000, 20240122, "cat-groceries", "p-market", null, null)
                .tx("t-dead-parent", "acc-checking", -600, 20240123, null, "p-market", null, "isParent = 1, tombstone = 1")
                .tx("t-dead-parent/1", "acc-checking", -600, 20240123, "cat-groceries", null, null, "isChild = 1, parent_id = 't-dead-parent'")
                .tx("t-lonely-parent", "acc-checking", -4200, 20240125, "cat-restaurants", "p-market", null, "isParent = 1")
                .tx("t-lonely-parent/1", "acc-checking", -4200, 20240125, "cat-restaurants", null, null, "isChild = 1, parent_id = 't-lonely-parent', tombstone = 1")
                .tx("t-salary", "acc-checking", 250000, 20240131, "cat-salary", "p-employer", "January pay", null);
    }

    public ActualBudgetFixture sql(String statement) {
        statements.add(statement);
        return this;
    }

    /** Inserts a transaction; {@code extra} is a comma-separated {@code column = value} list applied after. */
    public ActualBudgetFixture tx(String id, String account, long amount, int date, String category, String payee,
                                  String notes, String extra) {
        statements.add("INSERT INTO transactions (id, acct, amount, date, category, description, notes) VALUES ("
                + quote(id) + ", " + quote(account) + ", " + amount + ", " + date + ", " + quote(category) + ", "
                + quote(payee) + ", " + quote(notes) + ")");
        if (extra != null) {
            statements.add("UPDATE transactions SET " + extra + " WHERE id = " + quote(id));
        }
        return this;
    }

    public ActualBudgetFixture walMode() {
        wal = true;
        return this;
    }

    public byte[] sqlite() {
        try {
            Path file = Files.createTempFile("actual-fixture-", ".sqlite");
            try {
                Files.delete(file);
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                     Statement statement = connection.createStatement()) {
                    if (wal) {
                        statement.execute("PRAGMA journal_mode = WAL");
                    }
                    for (String sql : statements) {
                        statement.execute(sql);
                    }
                }
                return Files.readAllBytes(file);
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(Path.of(file + "-wal"));
                Files.deleteIfExists(Path.of(file + "-shm"));
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The budget export layout: {@code db.sqlite} next to {@code metadata.json}. */
    public byte[] zip() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("db.sqlite", sqlite());
        entries.put("metadata.json", "{\"budgetName\":\"Synthetic\"}".getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    public static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }
}
